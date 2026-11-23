package dev.tvdash.launcher

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Shows [HaNotification]s as cards that fade out after their duration. Where they go:
 * 1. TvDash's screensaver, when it's showing. The system one may sit above other apps' overlays.
 * 2. An overlay above whatever app is open, when TvDash may draw over other apps
 *    (`tools/tv.ps1 grant` allows it).
 * 3. The home screen, when it's open.
 * Otherwise the notification is dropped. A TV playing something in another app can't show it
 * without the overlay permission.
 */
object NotificationCards {
    private const val TAG = "HaNotifications"
    private const val MAX_CARDS = 4
    private const val FADE_MS = 250L
    private const val CARD_WIDTH_DP = 340
    private const val CENTER_WIDTH_DP = 460

    private val main = Handler(Looper.getMainLooper())

    /** Views TvDash's own screens lent, newest last; `true` means it beats the overlay (screensavers). */
    private val hosts = mutableListOf<Pair<FrameLayout, Boolean>>()
    /** Overlay windows above other apps, by position. */
    private val overlays = mutableMapOf<Int, LinearLayout>()
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Lends [host] for cards while it's on screen. Call on the main thread; pair with [detach]. */
    fun attach(host: FrameLayout, aboveOverlay: Boolean) {
        hosts.removeAll { it.first === host }
        hosts += host to aboveOverlay
    }

    fun detach(host: FrameLayout) {
        hosts.removeAll { it.first === host }
        host.findViewWithTag<View>(STACKS)?.let(host::removeView)
    }

    /** Safe from any thread. */
    fun post(notification: HaNotification) {
        main.post { show(notification) }
    }

    private fun show(n: HaNotification) {
        val stack = hosts.lastOrNull { it.second }?.first?.let { stackFor(it, n.position) }
            ?: overlayStack(n.position)
            ?: hosts.lastOrNull()?.first?.let { stackFor(it, n.position) }
        if (stack == null) {
            Log.i(TAG, "Nothing on screen to show \"${n.title}\" on (TvDash can't draw over other apps)")
            return
        }
        while (stack.childCount >= MAX_CARDS) stack.removeViewAt(0)
        val card = card(stack.context, n)
        card.alpha = 0f
        stack.addView(card)
        card.animate().alpha(1f).setDuration(FADE_MS).start()
        main.postDelayed({ dismiss(card) }, n.durationMs)
    }

    private fun dismiss(card: View) {
        card.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            val stack = card.parent as? ViewGroup ?: return@withEndAction
            stack.removeView(card)
            if (stack.childCount > 0) return@withEndAction
            if (overlays.containsValue(stack)) {
                removeOverlay(stack)
            } else {
                (stack.parent as? ViewGroup)?.removeView(stack)
            }
        }.start()
    }

    /**
     * The full-screen, see-through layer holding every stack in [parent]. It ignores keys and
     * touches, so the remote keeps working the app below.
     */
    private fun stacksIn(parent: FrameLayout): FrameLayout =
        parent.findViewWithTag(STACKS) ?: FrameLayout(parent.context).also {
            it.tag = STACKS
            it.isFocusable = false
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            parent.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

    private fun stackFor(parent: FrameLayout, position: Int): LinearLayout {
        val stacks = stacksIn(parent)
        val tag = "ha_stack_$position"
        stacks.findViewWithTag<LinearLayout>(tag)?.let { return it }
        val density = parent.resources.displayMetrics.density
        val gravity = gravityFor(position)
        return LinearLayout(parent.context).also { stack ->
            stack.tag = tag
            stack.orientation = LinearLayout.VERTICAL
            stacks.addView(
                stack,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity).apply {
                    // TV-safe area, as in the screensaver.
                    val x = (48 * density).toInt()
                    val y = (27 * density).toInt()
                    setMargins(x, y, x, y)
                },
            )
        }
    }

    private fun card(context: Context, n: HaNotification): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Number) = (v.toFloat() * density).toInt()
        val radius = dp(12).toFloat()
        val width = dp(if (n.position == HaNotification.POSITION_CENTER) CENTER_WIDTH_DP else CARD_WIDTH_DP)

        val background = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.tile), (n.backgroundAlpha * 255).toInt())
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            this.background = GradientDrawable().apply {
                setColor(background)
                cornerRadius = radius
            }
            elevation = dp(8).toFloat()
            layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        // A strip in the colour Home Assistant asked for (red for an alarm, say).
        n.color?.let { color ->
            card.addView(
                View(context).apply {
                    this.background = GradientDrawable().apply {
                        setColor(color)
                        cornerRadii = floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
                    }
                },
                LinearLayout.LayoutParams(dp(6), LinearLayout.LayoutParams.MATCH_PARENT),
            )
        }
        val pad = dp(14)
        n.icon?.let { icon ->
            card.addView(
                ImageView(context).apply {
                    setImageBitmap(icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(pad, pad, 0, pad) },
            )
        }
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(12), pad, dp(12))
        }
        fun line(value: String, sizeSp: Float, colorRes: Int, bold: Boolean, maxLines: Int) = TextView(context).apply {
            this.text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp * n.textScale)
            setTextColor(ContextCompat.getColor(context, colorRes))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
        }
        if (n.title.isNotEmpty()) text.addView(line(n.title, 17f, R.color.text, bold = true, maxLines = 2))
        if (n.message.isNotEmpty()) {
            text.addView(
                line(n.message, 15f, if (n.title.isEmpty()) R.color.text else R.color.text_dim, bold = false, maxLines = 8)
                    .apply { if (n.title.isNotEmpty()) setPadding(0, dp(4), 0, 0) },
            )
        }
        n.image?.let { image ->
            text.addView(
                ImageView(context).apply {
                    setImageBitmap(image)
                    adjustViewBounds = true
                    maxHeight = dp(190)
                    scaleType = ImageView.ScaleType.FIT_START
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (text.childCount > 0) topMargin = dp(10)
                },
            )
        }
        card.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    /**
     * A window above other apps for the cards at [position], sized to them, made on first use;
     * null without the permission. It never takes focus, so the remote keeps working the app
     * below. (The TV's Settings app hides every overlay, so cards don't show over it.)
     */
    private fun overlayStack(position: Int): LinearLayout? {
        overlays[position]?.let { return it }
        if (!::app.isInitialized || !Settings.canDrawOverlays(app)) return null
        val context = android.view.ContextThemeWrapper(app, R.style.Theme_TvDash)
        val density = context.resources.displayMetrics.density
        val stack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // Room for the cards' shadows.
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = gravityFor(position)
            // TV-safe area, as in the screensaver (less the padding above).
            x = ((48 - 12) * density).toInt()
            y = ((27 - 12) * density).toInt()
        }
        return try {
            app.getSystemService(WindowManager::class.java).addView(stack, params)
            overlays[position] = stack
            stack
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't show the notification overlay", e)
            null
        }
    }

    /** Only while cards are up, so no idle window sits over other apps. */
    private fun removeOverlay(stack: View) {
        val position = overlays.entries.firstOrNull { it.value === stack }?.key ?: return
        overlays.remove(position)
        runCatching { app.getSystemService(WindowManager::class.java).removeView(stack) }
    }

    private fun gravityFor(position: Int) = when (position) {
        HaNotification.POSITION_BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
        HaNotification.POSITION_TOP_RIGHT -> Gravity.TOP or Gravity.END
        HaNotification.POSITION_TOP_LEFT -> Gravity.TOP or Gravity.START
        HaNotification.POSITION_CENTER -> Gravity.CENTER
        else -> Gravity.BOTTOM or Gravity.END
    }

    private const val STACKS = "ha_stacks"
}
