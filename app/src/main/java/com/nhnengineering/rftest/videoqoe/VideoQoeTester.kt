package com.nhnengineering.rftest.videoqoe

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Video streaming QoE: launch time, load time, mid-playback stalls, and whether the resolution
 * ever dropped -- the same shape RantCell's own Video Streaming Test advertises (launch/load
 * time, stall duration/frequency, resolution consistency, a percentage score bucketed
 * Good/Fair/Poor). Their exact formula and startup-time split are not published, so this defines
 * its own, documented below, rather than guessing at theirs and silently being wrong.
 *
 * ## Definitions used here
 *
 * - **Load time**: prepare() to the first completed network load (manifest + first segment).
 *   The network side of startup.
 * - **Launch time**: prepare() to the first frame actually rendered
 *   ([AnalyticsListener.onRenderedFirstFrame]). The playback side of startup, and the one a
 *   viewer actually experiences as "how long until video".
 * - **Stall**: a [Player.STATE_BUFFERING] transition *after* the first frame has rendered.
 *   Buffering before the first frame is normal startup, not a stall -- counting it as one would
 *   double-count against launch time and inflate the stall figure for every single test.
 * - **Resolution dropped**: any later resolution has a smaller pixel area than an earlier one,
 *   read from [AnalyticsListener.onVideoSizeChanged] -- adaptive bitrate stepping down under
 *   pressure, the thing this metric exists to catch.
 *
 * ExoPlayer must be created and driven from a thread with a `Looper`; this runs on
 * [Dispatchers.Main] rather than a background dispatcher for that reason, the one tester in this
 * app that isn't Dispatchers.IO.
 *
 * ## No visible surface, but a real one
 *
 * This is a headless test with no UI to show video in, but a video renderer that is never given
 * an output [android.view.Surface] never actually decodes or renders a frame -- confirmed
 * live 2026-09-29: with no surface set, [onRenderedFirstFrame] never fired even after the full
 * test window elapsed, and the whole run silently fell back to reporting the timeout as the
 * launch time. An [ImageReader]'s surface is a real decode target with nothing to display it,
 * which is exactly what a headless measurement needs -- its own images are discarded on arrival
 * rather than read, since nothing here wants the pixels, only the timing of producing them.
 */
class VideoQoeTester(private val context: Context) {

    private companion object {
        // Arbitrary but non-trivial: large enough that no decoder treats it as a degenerate
        // surface, small enough to cost little. onVideoSizeChanged reports the stream's own
        // encoded resolution regardless of this size, so it does not affect what is measured.
        const val SINK_WIDTH = 640
        const val SINK_HEIGHT = 360
    }

    suspend fun run(config: VideoQoeConfig, onStage: (String) -> Unit = {}): VideoQoeResult =
        withContext(Dispatchers.Main) {
            val player = ExoPlayer.Builder(context).build()
            // PRIVATE, not RGBA_8888: a hardware decoder writes its output in an opaque,
            // implementation-defined GPU buffer format (confirmed live 2026-09-29 -- RGBA_8888
            // crashed with "producer output buffer format ... doesn't match the ImageReader's
            // configured buffer format"). PRIVATE is the documented format for exactly this
            // consume-and-discard case: a sink that needs the decoder to have somewhere to write
            // frames, never the pixels themselves.
            val imageReader = ImageReader.newInstance(SINK_WIDTH, SINK_HEIGHT, ImageFormat.PRIVATE, 2)
            imageReader.setOnImageAvailableListener(
                { reader -> runCatching { reader.acquireLatestImage()?.close() } },
                null,
            )
            player.setVideoSurface(imageReader.surface)
            val resolutions = mutableListOf<String>()
            var loadTimeMs: Long? = null
            var launchTimeMs: Long? = null
            var stallCount = 0
            var totalStallMs = 0L
            var bufferingStartedAt: Long? = null
            var errorMessage: String? = null

            val startAt = SystemClock.elapsedRealtime()
            val playerError = CompletableDeferred<String?>()

            val listener = object : AnalyticsListener {
                override fun onLoadCompleted(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                ) {
                    if (loadTimeMs == null) loadTimeMs = SystemClock.elapsedRealtime() - startAt
                }

                override fun onRenderedFirstFrame(
                    eventTime: AnalyticsListener.EventTime,
                    output: Any,
                    renderTimeMs: Long,
                ) {
                    if (launchTimeMs == null) launchTimeMs = SystemClock.elapsedRealtime() - startAt
                }

                override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                    if (state == Player.STATE_BUFFERING) {
                        // Startup buffering (before the first frame) is not a stall -- see the
                        // class doc for why counting it would double-count against launch time.
                        if (launchTimeMs != null) bufferingStartedAt = SystemClock.elapsedRealtime()
                    } else {
                        bufferingStartedAt?.let {
                            stallCount++
                            totalStallMs += SystemClock.elapsedRealtime() - it
                            bufferingStartedAt = null
                        }
                    }
                }

                override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
                    val label = "${videoSize.width}x${videoSize.height}"
                    if (resolutions.lastOrNull() != label) resolutions += label
                }

                override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                    errorMessage = error.message ?: error::class.java.simpleName
                    if (!playerError.isCompleted) playerError.complete(errorMessage)
                }
            }

            try {
                player.addAnalyticsListener(listener)
                onStage("Loading")
                player.setMediaItem(MediaItem.fromUri(config.url))
                player.playWhenReady = true
                player.prepare()

                // Runs for the configured duration, or stops early on a player error --
                // whichever comes first. A normal successful test always hits the timeout.
                launch {
                    val err = withTimeoutOrNull(config.testDurationMs) { playerError.await() }
                    if (err != null) errorMessage = err
                }.join()

                // Still buffering when the run ended: close out that stall rather than lose it.
                bufferingStartedAt?.let {
                    stallCount++
                    totalStallMs += SystemClock.elapsedRealtime() - it
                }

                val elapsed = SystemClock.elapsedRealtime() - startAt

                errorMessage?.let {
                    return@withContext VideoQoeResult(
                        loadTimeMs = null, launchTimeMs = null, stallCount = 0, totalStallMs = 0,
                        resolutions = emptyList(), resolutionDropped = false,
                        testDurationMs = elapsed, qualityScorePct = null, rating = null, error = it,
                    )
                }

                val load = loadTimeMs ?: elapsed
                val launch = launchTimeMs ?: elapsed
                val score = qualityScorePct(load, launch, totalStallMs, elapsed)
                VideoQoeResult(
                    loadTimeMs = load,
                    launchTimeMs = launch,
                    stallCount = stallCount,
                    totalStallMs = totalStallMs,
                    resolutions = resolutions,
                    resolutionDropped = resolutionDropped(resolutions),
                    testDurationMs = elapsed,
                    qualityScorePct = score,
                    rating = score?.let { rate(it) },
                    error = null,
                )
            } finally {
                player.removeAnalyticsListener(listener)
                player.setVideoSurface(null)
                player.release()
                imageReader.close()
            }
        }
}

data class VideoQoeConfig(
    // Apple's own long-standing public HLS test stream, published specifically for testing HLS
    // players against real adaptive-bitrate variants -- not a guessed or arbitrary third-party URL.
    val url: String = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8",
    val testDurationMs: Long = 20_000,
)

enum class VideoQoeRating(val label: String) {
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),
}

data class VideoQoeResult(
    val loadTimeMs: Long?,
    val launchTimeMs: Long?,
    val stallCount: Int,
    val totalStallMs: Long,
    val resolutions: List<String>,
    val resolutionDropped: Boolean,
    val testDurationMs: Long,
    val qualityScorePct: Double?,
    val rating: VideoQoeRating?,
    val error: String?,
)

/**
 * `(load + launch + stall) * 100 / duration` -- the same shape RantCell's FAQ describes for its
 * own Good/Fair/Poor score, with this test's own run duration standing in for "video length"
 * since this plays a live stream for a fixed window rather than one fixed-length clip.
 */
internal fun qualityScorePct(loadMs: Long, launchMs: Long, stallMs: Long, durationMs: Long): Double? =
    if (durationMs <= 0) null else (loadMs + launchMs + stallMs) * 100.0 / durationMs

/** Good 0-5%, Fair 5-10%, Poor above that -- the bands RantCell's FAQ states for the same score. */
internal fun rate(scorePct: Double): VideoQoeRating = when {
    scorePct <= 5.0 -> VideoQoeRating.GOOD
    scorePct <= 10.0 -> VideoQoeRating.FAIR
    else -> VideoQoeRating.POOR
}

/** True if any later resolution has a smaller pixel area than an earlier one. */
internal fun resolutionDropped(resolutions: List<String>): Boolean {
    val areas = resolutions.mapNotNull { parseArea(it) }
    for (i in 1 until areas.size) if (areas[i] < areas[i - 1]) return true
    return false
}

private fun parseArea(label: String): Long? {
    val parts = label.split("x")
    if (parts.size != 2) return null
    val w = parts[0].toLongOrNull() ?: return null
    val h = parts[1].toLongOrNull() ?: return null
    return w * h
}
