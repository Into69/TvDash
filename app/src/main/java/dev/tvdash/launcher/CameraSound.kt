package dev.tvdash.launcher

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/**
 * A camera's sound, on its own connection to the stream [CameraFeed] shows.
 *
 * Separate because sound can't share the video's player: Protect's audio timestamps start about
 * 16 s away from the video's, and with audio as the player's clock every video frame looks late
 * and the picture freezes. Lip sync matters little for a camera, and a separate player only costs
 * anything while the sound is on.
 *
 * Protect sends the microphone twice, as AAC and as Opus. Only AAC is played: Media3's RTSP Opus
 * reader rejects Protect's Opus ("ID Header missing").
 */
@OptIn(UnstableApi::class)
class CameraSound(
    private val context: Context,
    /** Called on the main thread when sound that was on stops because the camera has none. */
    private val onUnavailable: () -> Unit,
) {
    private var player: ExoPlayer? = null

    val isOn: Boolean get() = player != null

    fun start(streamUrl: String) {
        if (player != null) return
        val p = ExoPlayer.Builder(context).build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setPreferredAudioMimeType(MimeTypes.AUDIO_AAC)
            .build()
        // Takes audio focus, pausing music for as long as the camera is audible.
        p.setAudioAttributes(AUDIO_ATTRIBUTES, true)
        // TCP rather than UDP, as for the video.
        p.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(streamUrl)))
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.isEmpty) return // not described yet
                val aac = tracks.groups.any { group ->
                    group.type == C.TRACK_TYPE_AUDIO && group.isSelected &&
                        group.mediaTrackGroup.getFormat(0).sampleMimeType == MimeTypes.AUDIO_AAC
                }
                // No AAC: nothing, or Opus, which would fail anyway.
                if (!aac) unavailable("no AAC track")
            }

            override fun onPlayerError(error: PlaybackException) = unavailable(error.message)
        })
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    fun stop() {
        player?.release()
        player = null
    }

    private fun unavailable(reason: String?) {
        if (player == null) return
        Log.w(TAG, "No camera sound: $reason")
        stop()
        onUnavailable()
    }

    companion object {
        private const val TAG = "TvDash"

        private val AUDIO_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
    }
}
