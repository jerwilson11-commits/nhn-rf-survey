package com.nhnengineering.rftest.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nhnengineering.rftest.MainActivity
import com.nhnengineering.rftest.R
import com.nhnengineering.rftest.cellular.CellularCollector
import com.nhnengineering.rftest.location.LocationCollector
import com.nhnengineering.rftest.model.MeasurementSample
import com.nhnengineering.rftest.session.SessionCsvWriter
import com.nhnengineering.rftest.wifi.WifiCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Owns a recording session for its whole life.
 *
 * Recording lived in a Composable through Phases 2–5, which meant it died on a tab switch and on
 * screen lock — fine for bench testing, useless for a real site walk. A foreground service is the
 * only sanctioned way on modern Android to keep sampling with the screen off.
 *
 * Note the permission consequence: a foreground service declared with `location` type may access
 * location while backgrounded **without** `ACCESS_BACKGROUND_LOCATION`. That permission is for
 * apps wanting location with no visible service, and requesting it triggers a Play Store
 * justification review and demo video. Using the FGS path avoids that entirely.
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.nhnengineering.rftest.START"
        const val ACTION_STOP = "com.nhnengineering.rftest.STOP"
        const val EXTRA_SESSION_NAME = "session_name"

        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val SPEED_DEADBAND_MPS = 0.15f
        private const val MAX_FIX_GAP_S = 5.0

        /** Minimum gap between audible alarms, so a sustained breach does not become a siren. */
        private const val ALARM_COOLDOWN_MS = 5_000L

        fun start(context: Context, sessionName: String) {
            val i = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_NAME, sessionName)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    private lateinit var wifi: WifiCollector
    private lateinit var locations: LocationCollector
    private lateinit var cellular: CellularCollector
    private var writer: SessionCsvWriter? = null

    private var toneGenerator: ToneGenerator? = null
    private var lastAlarmAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wifi = WifiCollector(this)
        locations = LocationCollector(this)
        cellular = CellularCollector(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_SESSION_NAME).orEmpty())
        }
        // NOT_STICKY on purpose. If the OS kills us, silently resuming would append to a file the
        // operator believes is finished, producing a session with an unexplained gap in the middle.
        // Better to stop and let them see the session ended.
        return START_NOT_STICKY
    }

    private fun startRecording(sessionName: String) {
        if (RecordingState.active.value) return

        startForegroundCompat(buildNotification("Starting…"))
        RecordingState.resetCounters()
        RecordingState.active.value = true

        wifi.start()
        locations.start()
        cellular.start()

        loop = scope.launch {
            val w = runCatching { SessionCsvWriter.create(this@RecordingService, sessionName) }
                .onFailure {
                    Log.e(TAG, "could not open session file", it)
                    RecordingState.error.value = it.message ?: "could not open session file"
                }
                .getOrNull() ?: run { stopRecording(); return@launch }
            writer = w

            var sequence = 0L
            var lastSpeed: Float? = null
            var lastFixTime: Long? = null
            val startedAt = System.currentTimeMillis()

            while (RecordingState.active.value) {
                wifi.requestScanRefresh()
                val wifiNow = wifi.snapshot()
                val fixNow = locations.snapshot()
                val cellNow = cellular.snapshot()
                RecordingState.wifi.value = wifiNow
                RecordingState.fix.value = fixNow
                RecordingState.cellular.value = cellNow

                val throughput = RecordingState.pendingThroughput.value
                if (throughput != null) RecordingState.pendingThroughput.value = null

                runCatching {
                    w.writeRow(
                        MeasurementSample(
                            sessionId = w.sessionId,
                            sequence = sequence++,
                            timestampUtcMillis = System.currentTimeMillis(),
                            location = fixNow,
                            wifi = wifiNow,
                            cellular = cellNow,
                            throughput = throughput,
                            note = if (throughput != null) "speedtest" else null,
                        )
                    )
                }.onFailure {
                    Log.e(TAG, "write failed", it)
                    RecordingState.error.value = it.message ?: "write failed"
                }

                RecordingState.rowCount.value = sequence
                RecordingState.elapsedMs.value = System.currentTimeMillis() - startedAt

                // Distance by integrating Doppler velocity — see the note in the Master file on why
                // position differencing was abandoned.
                if (fixNow != null) {
                    val reported = fixNow.speedMps
                    if (reported == null) {
                        RecordingState.fixesWithoutVelocity.value++
                        lastSpeed = null
                        lastFixTime = null
                    } else {
                        RecordingState.fixesWithVelocity.value++
                        val v = if (reported < SPEED_DEADBAND_MPS) 0f else reported
                        val t = fixNow.fixTimeUtcMillis
                        val pv = lastSpeed
                        val pt = lastFixTime
                        if (pv != null && pt != null && t > pt) {
                            val dt = ((t - pt) / 1000.0).coerceIn(0.0, MAX_FIX_GAP_S)
                            RecordingState.distanceM.value += 0.5 * (pv + v) * dt
                        }
                        lastSpeed = v
                        lastFixTime = t
                    }
                }

                val breaches = RecordingState.thresholds.value.evaluate(wifiNow, fixNow)
                RecordingState.breaches.value = breaches
                if (breaches.isNotEmpty() && RecordingState.thresholds.value.audible) alarm()

                notify(
                    buildNotification(
                        String.format(
                            Locale.US,
                            "%d rows · %s · %.0f m%s",
                            sequence,
                            formatElapsed(RecordingState.elapsedMs.value),
                            RecordingState.distanceM.value,
                            if (breaches.isNotEmpty()) " · ALARM" else "",
                        )
                    )
                )
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun stopRecording() {
        if (!RecordingState.active.value && writer == null) {
            stopSelf()
            return
        }
        RecordingState.active.value = false
        loop?.cancel()
        loop = null

        val w = writer
        writer = null
        // New scope: the recording scope is being torn down, and the file still needs closing.
        CoroutineScope(Dispatchers.IO).launch {
            w?.close()
            RecordingState.lastSavedFile.value = w?.file?.name
        }

        wifi.stop()
        locations.stop()
        cellular.stop()
        RecordingState.breaches.value = emptyList()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Alarm
    // -----------------------------------------------------------------------

    private fun alarm() {
        val now = System.currentTimeMillis()
        if (now - lastAlarmAt < ALARM_COOLDOWN_MS) return
        lastAlarmAt = now
        runCatching {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
        }.onFailure { Log.w(TAG, "tone failed", it) }
    }

    // -----------------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            // LOW: the notification must exist for the foreground service, but it should not make
            // noise on every update. Threshold alarms are a separate, deliberate sound.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while a measurement session is recording" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording session")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop and save", stop).build())
            .build()
    }

    private fun notify(n: Notification) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) {
            String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        } else {
            String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }
}
