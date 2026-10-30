package dev.tvdash.launcher

import android.content.Context
import android.util.Log
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException

/**
 * Live video from a Protect camera into [video], with snapshots in [still] (drawn on top) until
 * the first frame arrives and whenever the stream fails. Muted: camera microphones shouldn't
 * play over the TV.
 */
@OptIn(UnstableApi::class)
class CameraFeed(
    private val context: Context,
    private val cameraId: String,
    private val video: PlayerView,
    private val still: ImageView,
    private val snapshotIntervalMs: Long,
) {
    private var player: ExoPlayer? = null
    private var job: Job? = null
    private var live = false

    fun start(scope: CoroutineScope) {
        // The video surface has to be visible to be created; the still image covers it until it plays.
        video.isVisible = true
        still.isVisible = true
        job = scope.launch {
            launch { snapshotsWhileNotLive() }
            val url = try {
                ProtectClient.liveStreamUrl(cameraId)
            } catch (e: IOException) {
                Log.w(TAG, "No live stream for camera $cameraId", e)
                null
            } catch (e: JSONException) {
                Log.w(TAG, "Unexpected stream info for camera $cameraId", e)
                null
            }
            if (url != null) play(url)
        }
    }

    private suspend fun snapshotsWhileNotLive() {
        val width = context.resources.displayMetrics.widthPixels
        while (currentCoroutineContext().isActive) {
            if (!live) {
                try {
                    ProtectClient.snapshot(cameraId, width)?.let(still::setImageBitmap)
                } catch (e: IOException) {
                    Log.w(TAG, "Snapshot failed for camera $cameraId", e)
                }
            }
            delay(snapshotIntervalMs)
        }
    }

    private fun play(url: String) {
        val p = ExoPlayer.Builder(context).build()
        // Don't even load the audio: it's muted anyway, and Media3's RTP Opus reader rejects
        // Protect's Opus audio ("ID Header missing"), which fails the whole stream.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        p.volume = 0f
        // TCP rather than UDP: reliable over Wi-Fi and through the console's RTSP server.
        p.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(url)))
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                live = true
                still.isVisible = false
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Live video failed for camera $cameraId", error)
                live = false
                still.isVisible = true
            }
        })
        video.player = p
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    fun stop() {
        job?.cancel()
        job = null
        video.player = null
        player?.release()
        player = null
        live = false
    }

    companion object {
        private const val TAG = "TvDash"
    }
}
