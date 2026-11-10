package dev.tvdash.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

/**
 * Something on the TV that makes TvDash or other apps misbehave: what's wrong, what it causes, how
 * to fix it, and the settings screen where it's fixed ([fixScreen], opened by the button labelled
 * [fixLabel]). [beforeFix] runs first when the button is pressed, and calls its second argument
 * once the screen should open.
 */
data class TvIssue(
    val title: String,
    val effect: String,
    val steps: String,
    val fixLabel: String,
    val fixScreen: Intent,
    val beforeFix: ((Context, () -> Unit) -> Unit)? = null,
)

/** Settings worth flagging on the Logs tile and page. Cheap enough to run on every home-screen render. */
object TvChecks {
    /**
     * What tools/tv.ps1 debloat turns off; keep in step with its `$Bloat` list (and the manifest's
     * `<queries>`, which makes them visible here).
     */
    private val BLOAT = listOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tungsten.setupwraith",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.google.android.tvrecommendations",
        "com.google.android.leanbacklauncher.recommendations",
        "com.sony.dtv.promos",
        "com.google.android.apps.tv.dreamx",
        "com.google.android.backdrop",
    )

    fun issues(context: Context): List<TvIssue> = buildList {
        reenabledBloat(context).takeIf { it.isNotEmpty() }?.let { add(bloatIssue(context, it)) }
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.ALWAYS_FINISH_ACTIVITIES, 0) != 0) {
            add(dontKeepActivities(context))
        }
    }

    /** Labels of debloated apps that are installed and on again, typically after a system update. */
    private fun reenabledBloat(context: Context): List<String> {
        val pm = context.packageManager
        return BLOAT.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
                if (info.enabled) pm.getApplicationLabel(info).toString() else null
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    /**
     * Only adb can turn apps off again (an app can't be granted that), so the fix is the PC running
     * debloat. Android 11+ needs Wireless debugging for that on Wi-Fi TVs, which a system update
     * switches off; the button turns it back on (WRITE_SECURE_SETTINGS covers adb_wifi_enabled)
     * and opens Developer options, where its address is shown.
     */
    private fun bloatIssue(context: Context, apps: List<String>): TvIssue {
        val wireless = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        return TvIssue(
            title = context.getString(R.string.issue_bloat_title),
            effect = context.getString(R.string.issue_bloat_effect, apps.joinToString(", ")),
            steps = context.getString(if (wireless) R.string.issue_bloat_steps_wireless else R.string.issue_bloat_steps),
            fixLabel = context.getString(if (wireless) R.string.issue_turn_on_wireless_debugging else R.string.issue_open_developer_options),
            fixScreen = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            beforeFix = if (wireless) ::turnOnWirelessDebugging else null,
        )
    }

    /**
     * Android switches it straight back off when the TV isn't on Wi-Fi, so the result is read back a
     * moment later rather than trusting the write. The toast comes before [done] opens Developer
     * options: once TvDash is behind it, Android 13+ drops its toasts.
     */
    private fun turnOnWirelessDebugging(context: Context, done: () -> Unit) {
        val written = try {
            Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
        } catch (e: SecurityException) {
            false
        }
        if (!written) {
            Toast.makeText(context, R.string.wireless_debugging_failed, Toast.LENGTH_LONG).show()
            done()
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            val on = Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) != 0
            Toast.makeText(context, if (on) R.string.wireless_debugging_on else R.string.wireless_debugging_no_wifi, Toast.LENGTH_LONG).show()
            done()
        }, WIRELESS_CHECK_DELAY_MS)
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
    private fun dontKeepActivities(context: Context) = TvIssue(
        title = context.getString(R.string.issue_dka_title),
        effect = context.getString(R.string.issue_dka_effect),
        steps = context.getString(R.string.issue_dka_steps),
        fixLabel = context.getString(R.string.issue_open_developer_options),
        fixScreen = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
    )

    /** Settings.Global.ADB_WIFI_ENABLED, hidden from the SDK. */
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val WIRELESS_CHECK_DELAY_MS = 1500L
}
