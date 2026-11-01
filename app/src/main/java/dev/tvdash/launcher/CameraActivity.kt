package dev.tvdash.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * One camera, fullscreen, as live video (snapshots until it connects). OK toggles the camera's
 * sound (starts muted). Back returns home.
 */
@OptIn(UnstableApi::class)
class CameraActivity : ComponentActivity() {
    private lateinit var feed: CameraFeed
    private val sound = CameraSound(this) { showMessage(getString(R.string.camera_no_audio)) }
    private var micOn = false
    private var toast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val cameraId = intent.getStringExtra(EXTRA_CAMERA_ID) ?: return finish()
        micOn = intent.getBooleanExtra(EXTRA_MIC_ON, false)
        val pad = (32 * resources.displayMetrics.density).toInt()

        val video = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }
        val still = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        val name = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_NAME)
            setTextColor(Color.WHITE)
            textSize = 20f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            // Most cameras stamp their own name on the picture, so this only needs to say where you are.
            animate().alpha(0f).setStartDelay(NAME_VISIBLE_MS).setDuration(500).start()
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(still, FrameLayout.LayoutParams(-1, -1))
            addView(name, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(pad, pad, pad, pad) })
        })
        feed = CameraFeed(this, cameraId, video, still, SNAPSHOT_INTERVAL_MS)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in SELECT_KEYS || !::feed.isInitialized) return super.dispatchKeyEvent(event)
        // On release, once: holding OK shouldn't flick the sound on and off.
        if (event.action == KeyEvent.ACTION_UP) toggleSound()
        return true
    }

    private fun toggleSound() {
        val url = feed.streamUrl
        val message = when {
            sound.isOn -> {
                sound.stop()
                getString(R.string.camera_sound_off)
            }
            !micOn || feed.noStream -> getString(R.string.camera_no_audio)
            url == null -> getString(R.string.camera_audio_connecting)
            else -> {
                // If it turns out there's nothing to hear, CameraSound says so (camera_no_audio).
                sound.start(url)
                getString(R.string.camera_sound_on)
            }
        }
        showMessage(message)
    }

    /** Replaces the last message rather than queueing behind it when OK is pressed repeatedly. */
    private fun showMessage(message: String) {
        toast?.cancel()
        toast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    override fun onResume() {
        super.onResume()
        if (::feed.isInitialized) feed.start(lifecycleScope)
    }

    override fun onPause() {
        super.onPause()
        // Leaving (Back, Home, screensaver) always ends the sound; it starts muted next time.
        sound.stop()
        if (::feed.isInitialized) feed.stop()
    }

    companion object {
        private const val EXTRA_CAMERA_ID = "camera_id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_MIC_ON = "mic_on"

        private val SELECT_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

        /** Snapshot pace while live video connects, or if it fails. */
        private const val SNAPSHOT_INTERVAL_MS = 500L
        private const val NAME_VISIBLE_MS = 3_000L

        fun intent(context: Context, camera: Camera) = Intent(context, CameraActivity::class.java)
            .putExtra(EXTRA_CAMERA_ID, camera.id)
            .putExtra(EXTRA_NAME, camera.name)
            .putExtra(EXTRA_MIC_ON, camera.micOn)
    }
}
