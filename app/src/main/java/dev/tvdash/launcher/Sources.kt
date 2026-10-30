package dev.tvdash.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

data class AppEntry(
    val packageName: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable,
)

/** [on] is true when the TV sees the input as active: a CEC device that's powered on, or a live signal. */
data class TvInput(val id: String, val label: String, val on: Boolean)

object AppSource {
    /** Every launchable app, TV (leanback) entries preferred over phone-style ones. */
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val apps = LinkedHashMap<String, AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val info = ri.activityInfo
                val pkg = info.packageName
                if (pkg == context.packageName || pkg in apps) continue
                apps[pkg] = AppEntry(
                    packageName = pkg,
                    label = ri.loadLabel(pm).toString(),
                    banner = info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm),
                    icon = ri.loadIcon(pm),
                )
            }
        }
        return apps.values.sortedBy { it.label.lowercase() }
    }

    /** Package names only: quick enough for the main thread, unlike [load] with its icons. */
    fun launchablePackages(context: Context): Set<String> =
        listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { category ->
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .map { it.activityInfo.packageName }
        }.toSet()

    fun launchIntent(pm: PackageManager, pkg: String): Intent? =
        pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
}

object InputSource {
    /**
     * HDMI/component passthrough inputs in port order. Empty on streaming sticks that have none.
     *
     * TVs list each port ("HDMI 2") and, separately, each HDMI-CEC device found on it
     * ("PlayStation 5") as a child input. A port with devices is shown as those devices only.
     */
    fun load(context: Context): List<TvInput> {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return emptyList()
        return runCatching {
            val inputs = tim.tvInputList.filter { it.isPassthroughInput && !it.isHidden(context) }
            val byId = inputs.associateBy { it.id }
            val portsWithDevices = inputs.mapNotNull { it.parentId }.toSet()
            fun label(info: TvInputInfo) = (info.loadCustomLabel(context) ?: info.loadLabel(context)).toString()

            inputs
                .filter { it.id !in portsWithDevices }
                .sortedWith(
                    compareBy<TvInputInfo, String>(NaturalOrder) { label(it.parentId?.let(byId::get) ?: it) }
                        .thenBy(NaturalOrder) { label(it) },
                )
                .map { TvInput(it.id, label(it), tim.getInputState(it.id) == TvInputManager.INPUT_STATE_CONNECTED) }
        }.getOrDefault(emptyList())
    }

    /** Orders "HDMI 2" before "HDMI 10". */
    private object NaturalOrder : Comparator<String> {
        private val chunks = Regex("\\d+|\\D+")
        override fun compare(a: String, b: String): Int {
            val x = chunks.findAll(a.lowercase()).map { it.value }.toList()
            val y = chunks.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val n = x[i].toBigIntegerOrNull()
                val m = y[i].toBigIntegerOrNull()
                val c = if (n != null && m != null) n.compareTo(m) else x[i].compareTo(y[i])
                if (c != 0) return c
            }
            return x.size - y.size
        }
    }

    fun intent(id: String): Intent =
        Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
}
