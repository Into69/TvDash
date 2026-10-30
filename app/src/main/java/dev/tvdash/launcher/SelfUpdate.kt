package dev.tvdash.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates TvDash from the GitHub releases of [BuildConfig.UPDATE_REPO], which CI publishes on each
 * push as `build-<versionCode>` with the APK attached. Only when chosen from the Updates tile.
 *
 * Android installs the update only if it's signed with the same key as the installed app, so a
 * download can't replace TvDash with something else. From Android 12 the install needs no
 * confirmation once TvDash installed itself (the first update after an adb install asks once).
 */
object SelfUpdate {
    data class Release(val versionCode: Int, val apkUrl: String, val sizeBytes: Long)

    val isAvailable: Boolean get() = BuildConfig.UPDATE_REPO.isNotEmpty()

    /**
     * An update is downloading or waiting on the installer; starting another would race it.
     * Cleared when it fails. On success Android restarts TvDash, which clears it.
     */
    @Volatile
    var inProgress = false

    /** The newest published build, or null if there's none with an APK. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "TvDash")
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val versionCode = json.optString("tag_name").removePrefix(TAG_PREFIX).toIntOrNull()
                ?: return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            (0 until assets.length()).map(assets::getJSONObject)
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.let { Release(versionCode, it.getString("browser_download_url"), it.optLong("size", -1)) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads [release] into an install session and commits it; [UpdateStatusReceiver] hears how
     * it went. On success Android stops TvDash and, being the home screen, it starts again updated.
     * [onProgress] gets 0–100 when the size is known.
     */
    suspend fun install(context: Context, release: Release, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "TvDash")
            try {
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                session.openWrite("tvdash.apk", 0, total).use { out ->
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPercent = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val percent = if (total > 0) (done * 100 / total).toInt() else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) { onProgress(percent) }
                            }
                        }
                    }
                    session.fsync(out)
                }
            } finally {
                conn.disconnect()
            }
            // Mutable: the installer adds the status extras to it.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val status = PendingIntent.getBroadcast(context, sessionId, Intent(context, UpdateStatusReceiver::class.java), flags)
            session.commit(status.intentSender)
            session.close()
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }

    private const val TAG_PREFIX = "build-"
}

/** Install results: asks the user to confirm when Android requires it, and reports failures. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                // Includes the user declining Android's confirmation; they can try again.
                SelfUpdate.inProgress = false
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("TvDash", "Update failed ($status): $message")
                Toast.makeText(context, context.getString(R.string.update_failed, message ?: status.toString()), Toast.LENGTH_LONG).show()
            }
        }
    }
}
