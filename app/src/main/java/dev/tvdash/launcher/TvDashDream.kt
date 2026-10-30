package dev.tvdash.launcher

import android.service.dreams.DreamService

/**
 * The TvDash screensaver, replacing Google TV's Ambient mode (tools/tv.ps1 install selects it).
 * See [Screensaver] for what it shows. Any key wakes the TV.
 */
class TvDashDream : DreamService() {
    private var screensaver: Screensaver? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(R.layout.dream)
        screensaver = Screensaver(this, window.decorView)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        screensaver?.start()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        screensaver?.stop()
    }

    override fun onDetachedFromWindow() {
        screensaver?.stop()
        screensaver = null
        super.onDetachedFromWindow()
    }
}
