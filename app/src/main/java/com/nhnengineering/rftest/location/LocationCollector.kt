package com.nhnengineering.rftest.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
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

    private var started = false

    private val gpsListener = LocationListener { location -> latestGps = location }
    private val fusedListener = LocationListener { location -> latestFused = location }

    fun start() {
        if (started) return
        started = true
        request(LocationManager.GPS_PROVIDER, gpsListener)
        request(LocationManager.FUSED_PROVIDER, fusedListener)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { locationManager?.removeUpdates(gpsListener) }
        runCatching { locationManager?.removeUpdates(fusedListener) }
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
        return best.toGeoPoint()
    }

    private fun Location.toGeoPoint() = GeoPoint(
        latitudeDeg = latitude,
        longitudeDeg = longitude,
        altitudeM = if (hasAltitude()) altitude else null,
        accuracyM = if (hasAccuracy()) accuracy else null,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        fixTimeUtcMillis = time,
        fixAgeMs = elapsedRealtimeAgeMillis.takeIf { it >= 0 },
        provider = provider ?: "unknown",
    )
}
