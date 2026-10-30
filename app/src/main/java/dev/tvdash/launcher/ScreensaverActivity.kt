package dev.tvdash.launcher

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity

/**
 * The screensaver on demand (the Now playing bar's button). Apps can't start the system
 * screensaver themselves, so this shows the same thing as a page; any key but volume closes it.
 * Left idle, the system screensaver takes over after the usual delay and looks the same.
 */
class ScreensaverActivity : ComponentActivity() {
    private lateinit var screensaver: Screensaver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dream)
        screensaver = Screensaver(this, window.decorView)
    }

    override fun onResume() {
        super.onResume()
        screensaver.start()
    }

    override fun onPause() {
        super.onPause()
        screensaver.stop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in PASS_THROUGH) return super.dispatchKeyEvent(event)
        // Close on release, so the same press doesn't also reach the home screen behind.
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }

    companion object {
        private val PASS_THROUGH = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
        )
    }
}
