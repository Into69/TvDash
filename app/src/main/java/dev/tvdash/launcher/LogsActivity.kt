package dev.tvdash.launcher

import android.content.ActivityNotFoundException
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

/** The TV's restart history and recent crashes, led by why it last restarted. */
class LogsActivity : ComponentActivity() {
    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((48 * density).toInt(), (27 * density).toInt(), (48 * density).toInt(), (27 * density).toInt())
        }
        // Focusable so the remote's up/down scroll it.
        setContentView(ScrollView(this).apply {
            isFocusable = true
            setBackgroundColor(getColor(R.color.bg))
            addView(rows)
        })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val log = if (SystemLogs.canRead(this@LogsActivity)) {
                withContext(Dispatchers.IO) { runCatching { SystemLogs.load(this@LogsActivity) }.getOrNull() }
            } else {
                null
            }
            rows.removeAllViews()
            show(log)
        }
    }

    private fun show(log: SystemLog?) {
        val bootedAt = log?.bootedAt ?: SystemLogs.bootedAt()
        title(getString(R.string.logs_title))
        showIssues()

        heading(getString(R.string.logs_last_restart))
        line("${time(bootedAt)}  ·  " + getString(R.string.logs_up_for, duration(System.currentTimeMillis() - bootedAt)))
        if (log == null) {
            line(getString(R.string.logs_no_access), dim = true)
            return
        }
        // The newest boot record is this boot; it describes how the previous run ended.
        val last = log.boots.firstOrNull { !it.softRestart }
        if (last == null) {
            line(getString(R.string.logs_reason_unknown), dim = true)
        } else {
            line(explain(last), bold = true)
            last.reason?.let { line(getString(R.string.logs_reported_reason, it), dim = true) }
        }

        heading(getString(R.string.logs_history))
        if (log.boots.isEmpty()) line(getString(R.string.logs_none), dim = true)
        for (boot in log.boots) line("${time(boot.time)}   ${explain(boot)}")

        heading(getString(R.string.logs_crashes))
        if (log.crashes.isEmpty()) line(getString(R.string.logs_none), dim = true)
        for (crash in log.crashes.take(MAX_CRASHES)) {
            line("${time(crash.time)}   ${crash.kind}" + (crash.process?.let { "  ·  $it" } ?: ""))
            crash.summary?.let { line(it, dim = true, detail = true) }
        }
        if (log.crashes.size > MAX_CRASHES) line(getString(R.string.logs_more, log.crashes.size - MAX_CRASHES), dim = true)
        heading("")
        line(getString(R.string.logs_retention), dim = true)
    }

    /** Plain-language reason; a crash found in the kernel log beats the generic reported reason. */
    private fun explain(boot: BootRecord): String = when {
        boot.softRestart -> getString(R.string.reason_soft_restart)
        boot.crash != null -> boot.crash
        else -> when (boot.reason?.substringBefore(',')) {
            null -> getString(R.string.reason_unknown)
            "reboot" -> when {
                boot.reason.contains("userrequested") -> getString(R.string.reason_user)
                boot.reason.contains("ota") || boot.reason.contains("recovery") -> getString(R.string.reason_update)
                else -> getString(R.string.reason_reboot)
            }
            "shutdown" -> getString(R.string.reason_shutdown)
            "cold", "hard", "power_on", "ac", "PowerKey" -> getString(R.string.reason_power)
            "kernel_panic", "panic" -> getString(R.string.reason_panic)
            "watchdog", "wdog" -> getString(R.string.reason_watchdog)
            else -> boot.reason
        }
    }

    private fun time(millis: Long): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(this)) "EEEdMMMHHmm" else "EEEdMMMhmma")
        return DateFormat.format(pattern, Date(millis)).toString()
    }

    private fun duration(millis: Long): String {
        val minutes = millis / DateUtils.MINUTE_IN_MILLIS
        val days = minutes / (60 * 24)
        val hours = minutes / 60 % 24
        return when {
            days > 0 -> getString(R.string.logs_duration_days, days, hours)
            else -> getString(R.string.logs_duration_hours, hours, minutes % 60)
        }
    }

    /**
     * TV settings to fix ([TvChecks]) lead the page: what's wrong, what it causes, the steps, and a
     * button to the screen where it's fixed. The Logs tile shows an amber dot while any are listed;
     * coming back here afterwards re-checks (onResume), so a fixed one disappears.
     */
    private fun showIssues() {
        val issues = TvChecks.issues(this)
        if (issues.isEmpty()) return
        heading(getString(R.string.issues_heading))
        issues.forEachIndexed { i, issue ->
            line(getString(issue.title), bold = true)
            line(getString(issue.effect))
            line(getString(issue.steps), dim = true)
            val button = Button(this).apply {
                text = getString(issue.fixLabel)
                setOnClickListener {
                    try {
                        startActivity(issue.fixScreen)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this@LogsActivity, getString(R.string.cannot_open, text), Toast.LENGTH_LONG).show()
                    }
                }
            }
            rows.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            // The page took focus before this existed; put it on the first fix so OK presses it.
            if (i == 0) button.post { button.requestFocus() }
        }
    }

    private fun title(s: String) = add(s, size = 27f, bold = true)

    private fun heading(s: String) = add(s, bold = true, dim = true, topGap = true)

    /** [detail] lines are indented and kept to one line, since crash messages are long unbroken tokens. */
    private fun line(s: String, bold: Boolean = false, dim: Boolean = false, detail: Boolean = false) =
        add(s, bold = bold, dim = dim, indent = detail, singleLine = detail)

    private fun add(
        s: String,
        size: Float = 18f,
        bold: Boolean = false,
        dim: Boolean = false,
        topGap: Boolean = false,
        indent: Boolean = false,
        singleLine: Boolean = false,
    ) {
        val density = resources.displayMetrics.density
        rows.addView(TextView(this).apply {
            text = s
            textSize = size
            setTextColor(getColor(if (dim) R.color.text_dim else R.color.text))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (singleLine) {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            setPadding(if (indent) (48 * density).toInt() else 0, if (topGap) (20 * density).toInt() else 0, 0, (4 * density).toInt())
        })
    }

    companion object {
        private const val MAX_CRASHES = 25
    }
}
