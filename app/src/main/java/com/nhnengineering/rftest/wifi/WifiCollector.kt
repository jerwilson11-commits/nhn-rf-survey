package com.nhnengineering.rftest.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import com.nhnengineering.rftest.model.WifiBand
import com.nhnengineering.rftest.model.WifiFrequency
import com.nhnengineering.rftest.model.WifiNeighbor
import com.nhnengineering.rftest.model.WifiSample
import com.nhnengineering.rftest.model.WifiSecurity
import com.nhnengineering.rftest.model.WifiStandard

/**
 * Reads live Wi-Fi KPIs.
 *
 * Split-rate by design, because the two data sources have very different constraints:
 *
 *  - **Connected-AP values** (RSSI, link rate) arrive by push from [ConnectivityManager] and are
 *    not throttled. Safe to sample at 1 Hz or faster.
 *  - **Neighbour scans** are hard-throttled by the OS to 4 per 2 minutes for foreground apps.
 *    Requesting faster does not fail loudly — `getScanResults()` just keeps returning the previous
 *    list. So we refresh on a slow cadence and stamp every sample with the true age of its
 *    neighbour data.
 *
 * Lifecycle: [start] before sampling, [stop] when done. Both are idempotent. Not thread-confined —
 * [snapshot] may be called from any thread, which is why the cached fields are `@Volatile`.
 */
class WifiCollector(context: Context) {

    private companion object {
        const val TAG = "WifiCollector"

        /** Placeholder BSSID Android substitutes when the caller lacks location permission. */
        const val REDACTED_BSSID = "02:00:00:00:00:00"

        /** Placeholder SSID Android substitutes under the same conditions. */
        const val REDACTED_SSID = "<unknown ssid>"

        /** Don't ask the OS for a scan more often than this; it would be throttled anyway. */
        const val MIN_SCAN_INTERVAL_MS = 30_000L

        /**
         * Sentinel WifiInfo.getRssi() returns when no value is available.
         * AOSP defines WifiInfo.INVALID_RSSI with this value but marks it @hide, so it is
         * not resolvable from an app. Declared here rather than reaching for the hidden API.
         */
        const val INVALID_RSSI_DBM = -127

        /**
         * How long an observed AP stays in the neighbour set after it was last seen.
         *
         * getScanResults() returns only the MOST RECENT scan, and the OS routinely sweeps a
         * subset of channels — so consecutive calls legitimately return 22 APs then 4, with no
         * error. Replacing the list wholesale makes the neighbour set collapse and expand at
         * random, and makes co-channel counts read 0 whenever that sweep happened to miss the
         * co-channel AP. So results accumulate by BSSID and age out instead.
         *
         * Trade-off: longer retention gives a stable picture when stationary, but while walking
         * it keeps APs that are already behind you. 60 s is the compromise; every neighbour also
         * carries its own ageMs so consumers can filter harder.
         */
        const val AP_RETENTION_MS = 60_000L
    }

    private class ObservedAp(val result: ScanResult, val observedElapsedMs: Long)

    // Application context, not the passed-in one — a WifiManager holding an Activity context is a
    // classic Android memory leak.
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)

    @Volatile private var latestWifiInfo: WifiInfo? = null
    private val observedAps = ConcurrentHashMap<String, ObservedAp>()
    @Volatile private var latestScanElapsedMs: Long? = null
    @Volatile private var lastScanRequestElapsedMs: Long = 0L

    private var started = false

    // -----------------------------------------------------------------------
    // Live connected-AP updates
    // -----------------------------------------------------------------------

    // FLAG_INCLUDE_LOCATION_INFO (API 31+) is mandatory to receive un-redacted SSID and BSSID.
    // Holding ACCESS_FINE_LOCATION is necessary but NOT sufficient: without this flag the
    // platform blanks both fields unconditionally, while leaving every non-location field
    // (RSSI, frequency, security, link rates) correct. That asymmetry is the tell.
    private val networkCallback = object : ConnectivityManager.NetworkCallback(
        FLAG_INCLUDE_LOCATION_INFO
    ) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // The modern replacement for the deprecated WifiManager.getConnectionInfo(). Note this
            // WifiInfo comes back with SSID and BSSID redacted unless we hold ACCESS_FINE_LOCATION.
            latestWifiInfo = caps.transportInfo as? WifiInfo
        }

        override fun onLost(network: Network) {
            latestWifiInfo = null
        }
    }

    // -----------------------------------------------------------------------
    // Scan results
    // -----------------------------------------------------------------------

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Fires for scans triggered by *any* app on the device, so we get free refreshes
            // beyond our own throttled requests.
            refreshScanResults()
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    fun start() {
        if (started) return
        started = true

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager?.registerNetworkCallback(request, networkCallback)

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(scanReceiver, filter)
        }

        // Seed with whatever the OS already has so the first sample is not empty.
        refreshScanResults()
        requestScanRefresh()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        runCatching { appContext.unregisterReceiver(scanReceiver) }
    }

    /**
     * Asks the OS for a fresh scan, respecting our own minimum interval.
     *
     * Returns false if we skipped the request or the OS refused it. A false return is normal and
     * not an error — it means the cached neighbour list stays in use, with its age reported.
     */
    fun requestScanRefresh(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanRequestElapsedMs < MIN_SCAN_INTERVAL_MS) return false
        lastScanRequestElapsedMs = now

        return try {
            // Deprecated since API 29 but still the only public way to trigger a scan, and still
            // functional. There is no replacement.
            @Suppress("DEPRECATION")
            wifiManager?.startScan() ?: false
        } catch (e: SecurityException) {
            Log.w(TAG, "startScan denied — missing CHANGE_WIFI_STATE or location permission", e)
            false
        }
    }

    private fun refreshScanResults() {
        try {
            val results = wifiManager?.scanResults.orEmpty()
            // An empty list with permissions granted almost always means Location Services is
            // switched off at the OS level. Granting the permission is not sufficient.
            if (results.isEmpty()) return

            val now = SystemClock.elapsedRealtime()
            // Merge rather than replace — see AP_RETENTION_MS.
            for (result in results) {
                val bssid = result.BSSID ?: continue
                observedAps[bssid] = ObservedAp(result, now)
            }
            observedAps.entries.removeAll { now - it.value.observedElapsedMs > AP_RETENTION_MS }
            latestScanElapsedMs = now
        } catch (e: SecurityException) {
            Log.w(TAG, "scanResults denied — missing ACCESS_FINE_LOCATION", e)
        }
    }

    // -----------------------------------------------------------------------
    // Sampling
    // -----------------------------------------------------------------------

    /**
     * Directly queries the current WifiInfo.
     *
     * Necessary because the NetworkCallback is push-based and fires on coarse capability changes,
     * not on every RSSI update — measured on the Pixel 6 Pro, the callback's RSSI stayed pinned at
     * exactly -37 dBm for 123 consecutive samples over two minutes while the OS itself reported
     * values alternating between -36 and -37. Stale by minutes, and it would flatline entirely
     * during a walk, which is precisely when the number matters.
     *
     * getConnectionInfo() is deprecated as of API 31 but is a direct read rather than a push, so
     * it returns the current value. Used ONLY for the volatile numeric fields; identity still
     * comes from the callback, whose FLAG_INCLUDE_LOCATION_INFO path is the reliable way to get
     * un-redacted SSID and BSSID.
     */
    private fun liveWifiInfo(): WifiInfo? = try {
        @Suppress("DEPRECATION")
        wifiManager?.connectionInfo
    } catch (e: SecurityException) {
        Log.w(TAG, "connectionInfo denied", e)
        null
    }

    /**
     * Builds a sample from the current cached state. Cheap and non-blocking — safe to call at the
     * sampling loop's full rate.
     *
     * Returns null only when there is neither a Wi-Fi connection nor any scan data, i.e. nothing
     * to record. A survey with Wi-Fi off but scans available still yields a sample.
     */
    fun snapshot(): WifiSample? {
        val now = SystemClock.elapsedRealtime()
        // Identity from the callback (un-redacted); volatile values from the direct query.
        val info = latestWifiInfo
        val live = liveWifiInfo()
        val observed = observedAps.values.toList()
        val scans = observed.map { it.result }
        if (info == null && scans.isEmpty()) return null

        val bssid = info?.bssid?.takeUnless { it == REDACTED_BSSID }
        val ssid = info?.ssid
            ?.removeSurrounding("\"")            // WifiInfo returns the SSID quoted
            ?.takeUnless { it == REDACTED_SSID }

        val frequency = (live ?: info)?.frequency?.takeIf { it > 0 }

        // Channel width is not on WifiInfo — only on ScanResult. Match our own BSSID against the
        // scan list to recover it.
        val ourScanResult = bssid?.let { b -> scans.firstOrNull { it.BSSID == b } }

        val scanAgeMs = latestScanElapsedMs?.let { SystemClock.elapsedRealtime() - it }

        return WifiSample(
            ssid = ssid,
            bssid = bssid,
            rssiDbm = (live ?: info)?.rssi?.takeIf { it != INVALID_RSSI_DBM && it < 0 },
            frequencyMhz = frequency,
            channel = frequency?.let { WifiFrequency.channelOf(it) },
            band = frequency?.let { WifiFrequency.bandOf(it) } ?: WifiBand.UNKNOWN,
            channelWidthMhz = ourScanResult?.let { WifiFrequency.channelWidthMhz(it.channelWidth) },
            standard = info?.let { WifiStandard.fromScanResultConstant(it.wifiStandard) }
                ?: WifiStandard.UNKNOWN,
            security = info?.let { WifiSecurity.fromWifiInfoConstant(it.currentSecurityType) }
                ?: WifiSecurity.UNKNOWN,
            txLinkMbps = (live ?: info)?.txLinkSpeedMbps?.takeIf { it > 0 },
            rxLinkMbps = (live ?: info)?.rxLinkSpeedMbps?.takeIf { it > 0 },
            maxSupportedTxMbps = (live ?: info)?.maxSupportedTxLinkSpeedMbps?.takeIf { it > 0 },
            neighbors = observed.map { it.result.toNeighbor(now - it.observedElapsedMs) },
            neighborScanAgeMs = scanAgeMs,
            coChannelCount = ourScanResult?.let { WifiFrequency.countCoChannel(it, scans) } ?: 0,
            adjacentChannelCount = ourScanResult
                ?.let { WifiFrequency.countAdjacentChannel(it, scans) } ?: 0,
        )
    }

    private fun ScanResult.toNeighbor(ageMs: Long): WifiNeighbor {
        // ScanResult.SSID is unquoted, unlike WifiInfo.getSSID(). Easy inconsistency to trip over.
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wifiSsid?.toString()?.removeSurrounding("\"")
        } else {
            @Suppress("DEPRECATION") SSID
        }

        return WifiNeighbor(
            ssid = name?.takeIf { it.isNotBlank() },
            bssid = BSSID,
            rssiDbm = level,                     // note: `level`, not `rssi`
            frequencyMhz = frequency,
            channel = WifiFrequency.channelOf(frequency),
            band = WifiFrequency.bandOf(frequency),
            channelWidthMhz = WifiFrequency.channelWidthMhz(channelWidth),
            standard = WifiStandard.fromScanResultConstant(wifiStandard),
            security = WifiSecurity.fromCapabilitiesString(capabilities.orEmpty()),
            ageMs = ageMs,
        )
    }
}
