package dev.tvdash.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.DropBoxManager
import android.os.SystemClock
import kotlin.math.abs

/** One boot. [crash] describes a kernel crash found in the log of the run that ended with this boot. */
data class BootRecord(val time: Long, val reason: String?, val crash: String?, val softRestart: Boolean = false)

data class CrashRecord(val time: Long, val kind: String, val process: String?, val summary: String?)

data class SystemLog(val bootedAt: Long, val boots: List<BootRecord>, val crashes: List<CrashRecord>)

/**
 * Reads the system DropBox, where Android files boot records, the kernel log from before each
 * restart (SYSTEM_LAST_KMSG) and crash/ANR reports. It keeps a few days of history.
 *
 * Needs READ_LOGS plus the usage-stats app-op, which only adb can grant (tools/tv.ps1 install does).
 * The boot-reason system properties would be simpler, but apps aren't allowed to read them.
 */
object SystemLogs {
    fun canRead(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun bootedAt() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private val CRASH_TAGS = mapOf(
        "system_server_crash" to "System crash",
        "system_server_anr" to "System froze",
        "system_server_native_crash" to "System crash",
        "SYSTEM_TOMBSTONE" to "Native crash",
        "system_app_crash" to "App crashed",
        "system_app_native_crash" to "App crashed",
        "system_app_anr" to "App froze",
        "data_app_crash" to "App crashed",
        "data_app_native_crash" to "App crashed",
        "data_app_anr" to "App froze",
    )

    fun load(context: Context): SystemLog {
        val dropBox = context.getSystemService(DropBoxManager::class.java)
        val kmsgs = entries(dropBox, "SYSTEM_LAST_KMSG", maxBytes = 512 * 1024)
        val boots = entries(dropBox, "SYSTEM_BOOT", maxBytes = 4 * 1024).map { (time, _) ->
            // The kernel log of the previous run is filed at (nearly) the same moment as the boot record.
            val kmsg = kmsgs.firstOrNull { abs(it.first - time) < MATCH_WINDOW_MS }?.second
            BootRecord(time, kmsg?.let(::reportedReason), kmsg?.let(::kernelCrash))
        } + entries(dropBox, "SYSTEM_RESTART", maxBytes = 4 * 1024).map { (time, _) ->
            BootRecord(time, null, null, softRestart = true)
        }
        val crashes = CRASH_TAGS.flatMap { (tag, kind) ->
            entries(dropBox, tag, maxBytes = 16 * 1024).map { (time, text) ->
                CrashRecord(time, kind, process(text), summary(text))
            }
        }
        return SystemLog(bootedAt(), boots.sortedByDescending { it.time }, crashes.sortedByDescending { it.time })
    }

    /** "reboot", "cold", "kernel_panic"… as written to the kernel log before the restart. */
    private fun reportedReason(kmsg: String) =
        Regex("Last boot reason: (\\S+)").findAll(kmsg).lastOrNull()?.groupValues?.get(1)

    /**
     * A one-line description if the kernel log ends in a crash, e.g. "Kernel crash in foo (ksoftirqd/3)"
     * or "Kernel crash in skb_release_data (driver mtk_gmac_mt5896)".
     */
    private fun kernelCrash(kmsg: String): String? {
        Regex("Kernel panic - not syncing: (.+)").find(kmsg)?.let { return "Kernel panic: ${it.groupValues[1].trim()}" }
        // MediaTek TVs keep only the tail of the log, which can start after the "Unable to handle
        // kernel" line; their crash handler (mrdump/AEE) still leaves its mark in the call trace.
        if ("Internal error: Oops" in kmsg || "Unable to handle kernel" in kmsg ||
            "aee dump flow and reset" in kmsg || "ipanic_die" in kmsg
        ) {
            val function = Regex("pc : (?:\\[[^\\]]*\\] )?([\\w.]+)\\+").find(kmsg)?.groupValues?.get(1)
                ?: faultingFrame(kmsg)
            val driver = crashDriver(kmsg)
            val task = Regex("Comm: (.+?) +(?:Not tainted|Tainted)").find(kmsg)?.groupValues?.get(1)
            return buildString {
                append("Kernel crash")
                if (function != null) append(" in ").append(function)
                if (driver != null) append(" (driver ").append(driver).append(')')
                else if (task != null) append(" (").append(task).append(')')
            }
        }
        if (Regex("(?i)watchdog.*(bark|bite|timeout|reset)").containsMatchIn(kmsg)) return "Hardware watchdog reset"
        // A normal restart or power-off ends with the kernel saying so. A log that just stops, yet
        // survived (so the power stayed on), means the system hung and the watchdog reset it.
        val lines = kmsg.substringBefore(BOOT_INFO).lines().filter { KERNEL_LINE.containsMatchIn(it) }
        if (lines.size >= MIN_LOG_LINES && lines.none { "reboot: " in it }) return "System froze (watchdog reset)"
        return null
    }

    /**
     * The frame the CPU faulted in: the one just below the first arm64 exception entry in the call
     * trace. Later entries (el1h_64_irq…) are interrupts the faulting code ran on top of.
     */
    private fun faultingFrame(kmsg: String): String? {
        val frames = Regex("(?m)^\\[[^\\]]*\\]\\[[^\\]]*\\] +([\\w.]+)\\+0x[0-9a-f]+/0x[0-9a-f]+").findAll(kmsg)
            .map { it.groupValues[1].substringBefore('.') }.toList()
        var i = frames.indexOfFirst { it.matches(EXCEPTION_ENTRY) }
        if (i < 0) return null
        while (i < frames.size && frames[i].matches(EXCEPTION_ENTRY)) i++
        return frames.getOrNull(i)
    }

    /** The first loadable module in the call trace other than the crash handler itself, if any. */
    private fun crashDriver(kmsg: String) =
        Regex("\\+0x[0-9a-f]+/0x[0-9a-f]+ \\[(\\w+)\\]").findAll(kmsg)
            .map { it.groupValues[1] }.firstOrNull { it != "mrdump" }

    private fun process(report: String) =
        Regex("(?m)^(?:Process|Cmd line|Cmdline): (\\S+)").find(report)?.groupValues?.get(1)

    /** The first exception or "ANR in" line, which is usually the useful one. */
    private fun summary(report: String) =
        report.lineSequence().map { it.trim() }.firstOrNull {
            Regex("(Exception|Error|ANR in|Abort message|signal \\d+)").containsMatchIn(it) && !it.startsWith("Process")
        }

    private fun entries(dropBox: DropBoxManager, tag: String, maxBytes: Int): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        var after = 0L
        while (true) {
            val entry = dropBox.getNextEntry(tag, after) ?: break
            entry.use {
                // Kernel logs are long and the crash is at the end, so keep the tail.
                val text = it.inputStream?.bufferedReader()?.use { r -> r.readText() }.orEmpty()
                out += it.timeMillis to text.takeLast(maxBytes)
                after = it.timeMillis
            }
        }
        return out
    }

    private const val MATCH_WINDOW_MS = 5 * 60 * 1000L

    /** Where Android appends its own boot summary to the saved kernel log. */
    private const val BOOT_INFO = "Boot info:"
    private val KERNEL_LINE = Regex("^\\[ *\\d+\\.\\d+\\]")

    /** Fewer lines than this is too little log to tell how the run ended. */
    private const val MIN_LOG_LINES = 20

    /** arm64 exception entry frames (el1h_64_sync, el1_abort…); the faulting function comes next. */
    private val EXCEPTION_ENTRY = Regex("el1h?_\\w+")
}
