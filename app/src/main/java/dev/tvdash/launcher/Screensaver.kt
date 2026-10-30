package dev.tvdash.launcher

import android.content.Context
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextClock
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.TimeZone
import kotlin.random.Random

/**
 * Drives the screensaver layout (R.layout.dream) under [root]: clock, weather, what's playing and,
 * with a camera picked in the Home screen settings, that camera filling the screen behind them.
 * The text block moves to a new spot periodically. Shared by the system screensaver
 * ([TvDashDream]) and the on-demand one ([ScreensaverActivity]).
 */
class Screensaver(private val context: Context, private val root: View) {
    private val content: View = root.findViewById(R.id.dream_content)
    private var scope: CoroutineScope? = null
    private var nowPlaying: NowPlaying? = null
    private var cameraFeed: CameraFeed? = null
    private var placed = false

    init {
        // Pinned top-left and placed with translation, so it can be put anywhere on screen.
        content.layoutParams = (content.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(0, 0, 0, 0)
        }
        // Start in the middle; keep it inside the screen if it grows (a song starts, weather loads).
        content.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) {
                if (!placed) {
                    placed = true
                    place(v, centre = true)
                    v.animate().alpha(1f).setDuration(FADE_MS).start()
                } else {
                    keepInside(v)
                }
            }
        }
    }

    fun start() {
        val prefs = Prefs(context)
        placed = false
        content.alpha = 0f
        // If it's already laid out (restarting), the layout listener won't fire: place it now.
        if (content.width > 0) {
            placed = true
            place(content, centre = true)
            content.animate().alpha(1f).setDuration(FADE_MS).start()
        }

        // Same zone as the home screen's clock: the weather location's.
        prefs.clockTimeZone?.takeIf { it in TimeZone.getAvailableIDs() }?.let { zone ->
            root.findViewById<TextClock>(R.id.clock).timeZone = zone
            root.findViewById<TextClock>(R.id.date).timeZone = zone
        }

        val cameraId = prefs.screensaverCamera?.takeIf { ProtectClient.isConfigured }
        root.findViewById<View>(R.id.dream_shade).isVisible = cameraId != null

        nowPlaying = NowPlaying(context, root.findViewById(R.id.now_playing)) {}.also { it.start() }

        val feed = WeatherFeed(context, prefs)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        scope.launch {
            while (isActive) {
                WeatherFeed.bind(root, feed.load())
                delay(feed.refreshIntervalMs)
            }
        }
        scope.launch { drift() }
        if (cameraId != null) {
            cameraFeed = CameraFeed(context, cameraId, root.findViewById(R.id.dream_video), root.findViewById(R.id.dream_camera), CAMERA_INTERVAL_MS)
                .also { it.start(scope) }
        }
    }

    fun stop() {
        cameraFeed?.stop()
        cameraFeed = null
        scope?.cancel()
        scope = null
        nowPlaying?.stop()
        nowPlaying = null
    }

    /**
     * Moves the text to a random spot every half of the screensaver delay (5 s with the 10-second
     * test setting), capped at 10 minutes so long delays still protect OLED panels. Fades out,
     * jumps, fades back in.
     */
    private suspend fun drift() {
        val delayMs = (Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 2 * MAX_MOVE_INTERVAL_MS) / 2)
            .coerceIn(MIN_MOVE_INTERVAL_MS, MAX_MOVE_INTERVAL_MS)
        while (scope?.isActive == true) {
            delay(delayMs)
            content.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                place(content, centre = false)
                content.animate().alpha(1f).setDuration(FADE_MS).start()
            }.start()
        }
    }

    /** The area the text's top-left corner can occupy while the whole block stays inside the safe margins. */
    private fun room(view: View): Pair<ClosedFloatingPointRange<Float>, ClosedFloatingPointRange<Float>> {
        val parent = view.parent as View
        val density = context.resources.displayMetrics.density
        val marginX = SAFE_MARGIN_X_DP * density
        val marginY = SAFE_MARGIN_Y_DP * density
        // If the block is ever wider/taller than the safe area, pin it to the margin rather than overflow.
        val maxX = (parent.width - view.width - marginX).coerceAtLeast(marginX)
        val maxY = (parent.height - view.height - marginY).coerceAtLeast(marginY)
        return marginX..maxX to marginY..maxY
    }

    private fun place(view: View, centre: Boolean) {
        val (xs, ys) = room(view)
        view.translationX = if (centre) (xs.start + xs.endInclusive) / 2 else xs.start + Random.nextFloat() * (xs.endInclusive - xs.start)
        view.translationY = if (centre) (ys.start + ys.endInclusive) / 2 else ys.start + Random.nextFloat() * (ys.endInclusive - ys.start)
    }

    private fun keepInside(view: View) {
        val (xs, ys) = room(view)
        view.translationX = view.translationX.coerceIn(xs)
        view.translationY = view.translationY.coerceIn(ys)
    }

    companion object {
        /** Snapshot pace while live video connects, or if it fails. */
        private const val CAMERA_INTERVAL_MS = 2_000L
        private const val MIN_MOVE_INTERVAL_MS = 5_000L
        private const val MAX_MOVE_INTERVAL_MS = 10 * 60_000L
        private const val FADE_MS = 600L

        /** TV-safe area: content within ~5% of the edges can be hidden by overscan. */
        private const val SAFE_MARGIN_X_DP = 48
        private const val SAFE_MARGIN_Y_DP = 27
    }
}
