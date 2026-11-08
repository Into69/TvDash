package dev.tvdash.launcher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes

/**
 * A TV setting that makes TvDash or other apps misbehave: what's wrong, what it causes, how to fix
 * it, and the settings screen where it's fixed ([fixScreen], opened by the button labelled [fixLabel]).
 */
data class TvIssue(
    @StringRes val title: Int,
    @StringRes val effect: Int,
    @StringRes val steps: Int,
    @StringRes val fixLabel: Int,
    val fixScreen: Intent,
)

/** Settings worth flagging on the Logs tile and page. Cheap enough to run on every home-screen render. */
object TvChecks {
    fun issues(context: Context): List<TvIssue> = buildList {
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.ALWAYS_FINISH_ACTIVITIES, 0) != 0) {
            add(DONT_KEEP_ACTIVITIES)
        }
    }

    /**
     * Developer option "Don't keep activities": every activity is destroyed once it's left. Prime
     * Video then relaunches itself after the TV is switched off, turning it back on (seen on the
     * CT1), and TvDash reloads every time Home is pressed.
     *
     * Fixed in Developer options rather than by writing the setting: Android reads it only at boot
     * or when Developer options changes it, so a write alone would read "off" but change nothing
     * until the next restart.
     */
    private val DONT_KEEP_ACTIVITIES = TvIssue(
        title = R.string.issue_dka_title,
        effect = R.string.issue_dka_effect,
        steps = R.string.issue_dka_steps,
        fixLabel = R.string.issue_open_developer_options,
        fixScreen = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
    )
}
