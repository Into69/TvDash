package dev.tvdash.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Copies a TvDash layout from one TV (the master) to another, via tools/tv.ps1:
 * `export` triggers [LayoutExportReceiver] and reads [FILE] back with run-as; `import`
 * hands the JSON to MainActivity as an intent extra, which calls [import].
 */
object LayoutTransfer {
    const val FILE = "layout.json"
    private const val VERSION = 1

    fun export(context: Context): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("exportedAt", System.currentTimeMillis())
        .put("model", Build.MODEL)
        .put("settings", Prefs(context).exportAll())
        // Names for every app, so a TV that lacks one can still say what it is.
        .put("labels", JSONObject(AppSource.load(context).associate { it.packageName to it.label }))

    /**
     * Applies [layout] and remembers which of its apps this TV still needs. [installed] is every
     * launchable package here, used to spot device editions of the same app (see [editionOf]).
     */
    fun import(prefs: Prefs, layout: JSONObject, installed: Set<String>) {
        require(layout.optInt("version") == VERSION) { "Unsupported layout version" }
        prefs.importAll(layout.getJSONObject("settings"))
        fun swapEditions(ids: List<String>) = ids.map { id ->
            if (HomeItem.isPlaceholder(id) || HomeItem.isCamera(id) || id in installed) id else editionOf(id, installed) ?: id
        }
        prefs.favorites = swapEditions(prefs.favorites)
        prefs.appOrder = swapEditions(prefs.appOrder)
        val labels = layout.optJSONObject("labels") ?: JSONObject()
        val wanted = (prefs.favorites + prefs.appOrder)
            .filter { !HomeItem.isPlaceholder(it) && !HomeItem.isCamera(it) && it !in installed }
            .distinct()
        prefs.importWanted = wanted
        prefs.importLabels = wanted.associateWith { labels.optString(it).ifEmpty { it } }
    }

    /**
     * Some devices ship their own build of an app under a longer package name, e.g. the SHIELD's
     * com.amazon.amazonvideo.livingroom.nvidia for Prime Video. Treat that as the same app.
     */
    private fun editionOf(pkg: String, installed: Set<String>): String? =
        installed.singleOrNull { it.startsWith("$pkg.") }
}

/** `am broadcast -n dev.tvdash.launcher/.LayoutExportReceiver`: writes the layout without showing anything. */
class LayoutExportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        // Listing apps for their names takes a moment; keep it off the main thread.
        thread {
            try {
                File(context.filesDir, LayoutTransfer.FILE).writeText(LayoutTransfer.export(context).toString(2))
            } finally {
                result.finish()
            }
        }
    }
}
