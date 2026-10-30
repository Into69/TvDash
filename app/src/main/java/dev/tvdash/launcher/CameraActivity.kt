package dev.tvdash.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** One camera, fullscreen, as live video (snapshots until it connects). Back returns home. */
@OptIn(UnstableApi::class)
class CameraActivity : ComponentActivity() {
    private lateinit var feed: CameraFeed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val cameraId = intent.getStringExtra(EXTRA_CAMERA_ID) ?: return finish()
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

    override fun onResume() {
        super.onResume()
        if (::feed.isInitialized) feed.start(lifecycleScope)
    }

    override fun onPause() {
        super.onPause()
        if (::feed.isInitialized) feed.stop()
    }

    companion object {
        private const val EXTRA_CAMERA_ID = "camera_id"
        private const val EXTRA_NAME = "name"

        /** Snapshot pace while live video connects, or if it fails. */
        private const val SNAPSHOT_INTERVAL_MS = 500L
        private const val NAME_VISIBLE_MS = 3_000L

        fun intent(context: Context, camera: Camera) = Intent(context, CameraActivity::class.java)
            .putExtra(EXTRA_CAMERA_ID, camera.id)
            .putExtra(EXTRA_NAME, camera.name)
    }
}
