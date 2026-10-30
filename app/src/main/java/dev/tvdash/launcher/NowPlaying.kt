package dev.tvdash.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible

/**
 * Exists only so the system will hand TvDash other apps' media sessions: reading them requires
 * an enabled notification listener. tools/tv.ps1 install enables it over adb.
 */
class MediaListener : NotificationListenerService()

/**
 * The now-playing bar: whatever is playing on the TV (Spotify, including Spotify Connect from a
 * phone, YouTube Music, emby…), with art, title, artist and transport controls.
 */
class NowPlaying(
    /** The home screen, or the screensaver (which shows the bar without its buttons). */
    private val activity: Context,
    private val bar: View,
    /** Called when the bar disappears while it had focus, so focus can go somewhere sensible. */
    private val onHiddenWhileFocused: () -> Unit,
) {
    private val sessions = activity.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(activity, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null

    private val art: ImageView = bar.findViewById(R.id.np_art)
    private val title: TextView = bar.findViewById(R.id.np_title)
    private val subtitle: TextView = bar.findViewById(R.id.np_subtitle)
    private val playPause: ImageButton = bar.findViewById(R.id.np_play)

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onSessionDestroyed() = refresh()
    }

    init {
        bar.findViewById<View>(R.id.np_info).setOnClickListener { openApp() }
        bar.findViewById<View>(R.id.np_prev).setOnClickListener { controller?.transportControls?.skipToPrevious() }
        bar.findViewById<View>(R.id.np_next).setOnClickListener { controller?.transportControls?.skipToNext() }
        playPause.setOnClickListener {
            val controls = controller?.transportControls ?: return@setOnClickListener
            if (isPlaying(controller)) controls.pause() else controls.play()
        }
    }

    fun start() {
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler)
            refresh()
        } catch (e: SecurityException) {
            // Listener access not granted (yet): no bar.
            hide()
        }
    }

    fun stop() {
        handler.removeCallbacks(hideAfterPause)
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun refresh() {
        val active = try {
            sessions.getActiveSessions(listener)
        } catch (e: SecurityException) {
            emptyList()
        }
        pick(active)
    }

    /**
     * Whatever is playing. A session that was showing and just paused is kept for [PAUSE_GRACE_MS],
     * so pressing pause on the bar doesn't snatch the play button away.
     */
    private fun pick(active: List<MediaController>) {
        val best = active.firstOrNull { it.playbackState?.state in PLAYING }
            ?: active.firstOrNull { it.sessionToken == controller?.sessionToken && it.playbackState?.state == PlaybackState.STATE_PAUSED }
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, handler)
        }
        update()
    }

    private fun update() {
        val c = controller
        val meta = c?.metadata
        val state = c?.playbackState?.state
        val songTitle = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.description?.title?.toString()
        val paused = state == PlaybackState.STATE_PAUSED
        if (c == null || songTitle.isNullOrBlank() || (state !in PLAYING && !paused)) {
            hide()
            return
        }
        // Paused: hide after a grace period unless it resumes; playing: cancel any pending hide.
        handler.removeCallbacks(hideAfterPause)
        if (paused) {
            // Only something already on screen gets the grace period; a paused app found later stays hidden.
            if (!bar.isVisible) return
            handler.postDelayed(hideAfterPause, PAUSE_GRACE_MS)
        }
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        title.text = songTitle
        subtitle.text = listOfNotNull(artist, album, appName(c.packageName))
            .filter { it.isNotBlank() }.distinct().joinToString("  ·  ")
        val bitmap: Bitmap? = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bitmap != null) art.setImageBitmap(bitmap) else art.setImageResource(R.drawable.ic_music)
        playPause.setImageResource(if (isPlaying(c)) R.drawable.ic_pause else R.drawable.ic_play)
        playPause.contentDescription = activity.getString(if (isPlaying(c)) R.string.np_pause else R.string.np_play)
        bar.isVisible = true
    }

    private val hideAfterPause = Runnable { hide() }

    private fun hide() {
        handler.removeCallbacks(hideAfterPause)
        val hadFocus = bar.hasFocus()
        bar.isVisible = false
        if (hadFocus) onHiddenWhileFocused()
    }

    private fun openApp() {
        val pkg = controller?.packageName ?: return
        val pm = activity.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
        runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun appName(pkg: String) =
        runCatching { activity.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrNull()

    private fun isPlaying(c: MediaController?) = c?.playbackState?.state == PlaybackState.STATE_PLAYING

    companion object {
        private const val PAUSE_GRACE_MS = 10_000L

        /** Counts as playing: actually playing, or about to (loading, changing track). */
        private val PLAYING = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        )
    }
}
