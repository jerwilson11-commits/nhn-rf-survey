package com.nhnengineering.rftest.location

import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.os.SystemClock
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.nhnengineering.rftest.model.GeoPoint

/**
 * GPS fixes for geo-tagging samples.
 *
 * Uses the platform [LocationManager] rather than Play Services' FusedLocationProviderClient:
 * Android 12+ ships a fused provider inside the platform, so there is no dependency to add, and
 * raw GPS_PROVIDER is the honest choice for a drive test — a fused fix can be smoothed or derived
 * from Wi-Fi and cell, which is exactly what you do not want when the thing being measured is
 * Wi-Fi and cell.
 *
 * GPS is requested first. The fused provider is registered as a fallback so that indoors, where
 * GPS commonly yields nothing, samples still carry an approximate position rather than none — and
 * every [GeoPoint] records which provider produced it, so the two are never silently conflated.
 */
class LocationCollector(context: Context) {

    private companion object {
        const val TAG = "LocationCollector"

        /** Fastest update the providers should deliver. The sampling loop decides the log rate. */
        const val MIN_INTERVAL_MS = 1_000L

        /** Zero: report every fix regardless of movement. A stationary survey point is a valid
         *  measurement, and distance filtering would silently drop it. */
        const val MIN_DISTANCE_M = 0f

        /** Beyond this, a fix is too stale to attach to a sample taken now. */
        const val MAX_FIX_AGE_MS = 30_000L
    }

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    @Volatile private var latestGps: Location? = null
    @Volatile private var latestFused: Location? = null

    /** Most recent satellite snapshot from the GPS chip. Updated on the GNSS HAL's own cadence,
     *  independent of [gpsListener] -- not guaranteed to be from the exact same instant as
     *  [latestGps], just the most recent one available. Only meaningful for the GPS fix; the
     *  fused provider has no satellite data of its own. */
    @Volatile private var latestGnssStatus: GnssStatus? = null

    private var started = false

    private val gpsListener = LocationListener { location -> latestGps = location }
    private val fusedListener = LocationListener { location -> latestFused = location }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            latestGnssStatus = status
        }
    }

    fun start() {
        if (started) return
        started = true
        request(LocationManager.GPS_PROVIDER, gpsListener)
        request(LocationManager.FUSED_PROVIDER, fusedListener)
        try {
            locationManager?.registerGnssStatusCallback(
                ContextCompat.getMainExecutor(appContext),
                gnssStatusCallback,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "GNSS status updates denied", e)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { locationManager?.removeUpdates(gpsListener) }
        runCatching { locationManager?.removeUpdates(fusedListener) }
        runCatching { locationManager?.unregisterGnssStatusCallback(gnssStatusCallback) }
    }

    private fun request(provider: String, listener: LocationListener) {
        try {
            if (locationManager?.isProviderEnabled(provider) != true) {
                Log.w(TAG, "provider '$provider' is not enabled")
                return
            }
            locationManager.requestLocationUpdates(
                provider,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "location updates denied for '$provider'", e)
        } catch (e: IllegalArgumentException) {
            // FUSED_PROVIDER is guaranteed from API 31, but a provider can still be absent on
            // unusual builds. Not fatal — GPS alone is enough for an outdoor drive test.
            Log.w(TAG, "provider '$provider' unavailable", e)
        }
    }

    /** True if neither provider is switched on — worth surfacing, since the symptom is silence. */
    fun isAnyProviderEnabled(): Boolean = try {
        locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
            locationManager?.isProviderEnabled(LocationManager.FUSED_PROVIDER) == true
    } catch (e: SecurityException) {
        false
    }

    /**
     * Best current fix, or null if nothing usable.
     *
     * Prefers GPS whenever it is fresh, falling back to fused rather than attaching a stale GPS
     * fix to a fresh sample. Returning null is correct and better than a plausible-looking
     * position that is thirty seconds and a hundred metres old.
     */
    fun snapshot(): GeoPoint? {
        val now = System.currentTimeMillis()
        val gps = latestGps?.takeIf { now - it.time <= MAX_FIX_AGE_MS }
        val fused = latestFused?.takeIf { now - it.time <= MAX_FIX_AGE_MS }
        val best = gps ?: fused ?: return null
        // Satellite data only means something for the GPS fix itself, not a fused fallback.
        val gnss = latestGnssStatus.takeIf { best === gps }
        return best.toGeoPoint(gnss)
    }

    private fun Location.toGeoPoint(gnss: GnssStatus?) = GeoPoint(
        latitudeDeg = latitude,
        longitudeDeg = longitude,
        altitudeM = if (hasAltitude()) altitude else null,
        accuracyM = if (hasAccuracy()) accuracy else null,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        fixTimeUtcMillis = time,
        // Computed rather than taken from Location.getElapsedRealtimeAgeMillis(), which is API 33
        // and this app's minSdk is 31. That method was used first and crashed every device below
        // Android 13 with NoSuchMethodError the moment a fix arrived -- invisible on the Android 17
        // development handset, and invisible to unit tests, because it is a link-time fact about
        // the device rather than anything the code can express. getElapsedRealtimeNanos() is API 17
        // and the subtraction is exactly what the newer method does.
        fixAgeMs = ((SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L)
            .takeIf { it >= 0 },
        provider = provider ?: "unknown",
        gnssSatellitesUsed = gnss?.usedSatelliteCount(),
        gnssSatellitesInView = gnss?.satelliteCount,
        gnssAvgCn0DbHz = gnss?.usedCn0Values()?.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size },
        gnssMinCn0DbHz = gnss?.usedCn0Values()?.minOrNull(),
    )

    /** Only satellites that actually informed this fix -- the ones relevant to explaining it,
     *  not every satellite merely visible. */
    private fun GnssStatus.usedSatelliteCount(): Int =
        (0 until satelliteCount).count { usedInFix(it) }

    private fun GnssStatus.usedCn0Values(): List<Float> =
        (0 until satelliteCount).filter { usedInFix(it) }.map { getCn0DbHz(it) }
}
