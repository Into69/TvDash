package dev.tvdash.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.children
import kotlin.math.max
import kotlin.math.roundToInt

/** The rearrangeable parts of the home screen. The same app can be in Favorites and Apps. */
enum class Section(val keyPrefix: String) {
    FAVORITES("fav:"),
    APPS("app:"),
    INPUTS("input:"),
}

sealed class HomeItem {
    abstract val key: String

    /** Tiles are [span]×[span] grid cells. */
    open val span: Int get() = 1

    data class Header(val title: String) : HomeItem() {
        override val key get() = "header:$title"
    }

    /** [section] is Favorites or Apps; [moving] while being rearranged. */
    data class App(val app: AppEntry, val section: Section, val moving: Boolean = false) : HomeItem() {
        override val key get() = keyFor(app.packageName, section)
    }

    /** An empty tile used for spacing; stored in the section order as [id]. */
    data class Placeholder(val id: String, val section: Section, val moving: Boolean = false) : HomeItem() {
        override val key get() = keyFor(id, section)
    }

    /** A UniFi Protect camera in the Apps section, 1×1 or 2×2. Its snapshot lives in [TileBoard]. */
    data class CameraTile(val camera: Camera, val size: Int, val moving: Boolean = false) : HomeItem() {
        override val key get() = keyFor(cameraEntry(camera.id), Section.APPS)
        override val span get() = size
    }

    /** An HDMI port or device; [label] is the user's name for it if they renamed it. */
    data class Input(val input: TvInput, val label: String, val moving: Boolean = false) : HomeItem() {
        override val key get() = keyFor(input.id, Section.INPUTS)
    }

    /** [warning]: show an amber dot, e.g. on Logs when a TV setting needs fixing ([TvChecks]). */
    data class Action(val id: String, val label: String, @DrawableRes val icon: Int, val warning: Boolean = false) : HomeItem() {
        override val key get() = "action:$id"
    }

    companion object {
        /** Prefixes of placeholder and camera ids, which share the saved order lists with package names. */
        const val PLACEHOLDER_PREFIX = "placeholder:"
        private const val CAMERA_PREFIX = "camera:"

        fun isPlaceholder(id: String) = id.startsWith(PLACEHOLDER_PREFIX)
        fun isCamera(id: String) = id.startsWith(CAMERA_PREFIX)
        fun cameraEntry(cameraId: String) = CAMERA_PREFIX + cameraId
        fun cameraIdOf(entry: String) = entry.removePrefix(CAMERA_PREFIX)

        /** The same app can be in Favorites and Apps, so keys include the section. */
        fun keyFor(id: String, section: Section) = section.keyPrefix + id
    }
}

/**
 * Lays children out on a [COLUMNS]-wide grid of 16:9 cells. Unlike GridLayoutManager it supports
 * tiles spanning rows as well as columns, which 2×2 camera tiles need. Headers take a full row
 * at their own height. Positions come from [LayoutParams]; [TileBoard] works them out.
 */
class TileGrid @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val margin = (10 * resources.displayMetrics.density).roundToInt()
    private var cellWidth = 0f
    private var rowTops = IntArray(1)

    class LayoutParams(val col: Int, val row: Int, val span: Int, val isHeader: Boolean) :
        ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    private val View.grid get() = layoutParams as LayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cellWidth = width.toFloat() / COLUMNS
        val cellHeight = ((cellWidth - 2 * margin) * 9 / 16).roundToInt() + 2 * margin

        val rows = children.maxOfOrNull { it.grid.row + if (it.grid.isHeader) 1 else it.grid.span } ?: 0
        val rowHeights = IntArray(rows) { cellHeight }
        for (child in children.filter { it.grid.isHeader }) {
            child.measure(exactly(width), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            rowHeights[child.grid.row] = child.measuredHeight
        }
        rowTops = IntArray(rows + 1)
        for (i in 0 until rows) rowTops[i + 1] = rowTops[i] + rowHeights[i]

        for (child in children.filter { !it.grid.isHeader }) {
            val lp = child.grid
            val w = (cellWidth * lp.span).roundToInt() - 2 * margin
            val h = rowTops[lp.row + lp.span] - rowTops[lp.row] - 2 * margin
            child.measure(exactly(w), exactly(h))
        }
        setMeasuredDimension(width, rowTops[rows])
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (child in children) {
            val lp = child.grid
            val inset = if (lp.isHeader) 0 else margin
            val left = (cellWidth * lp.col).roundToInt() + inset
            val top = rowTops[lp.row] + inset
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(max(size, 0), MeasureSpec.EXACTLY)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams
    override fun generateDefaultLayoutParams() = LayoutParams(0, 0, 1, false)

    companion object {
        const val COLUMNS = 5
    }
}

/**
 * Turns the list of [HomeItem]s into views on a [TileGrid]. Views are kept per item key, so a
 * re-render (moving a tile, a new snapshot, a renamed input) updates them in place and focus stays put.
 */
class TileBoard(
    private val grid: TileGrid,
    private val onClick: (HomeItem) -> Unit,
    private val onMenu: (HomeItem) -> Unit,
) {
    private val inflater = LayoutInflater.from(grid.context)
    private val views = mutableMapOf<String, View>()
    private val snapshots = mutableMapOf<String, Bitmap>()

    /** Keys in placement order, top-left first. */
    private var order: List<String> = emptyList()

    var items: List<HomeItem> = emptyList()
        private set

    private class Placement(val item: HomeItem, val col: Int, val row: Int)

    fun submit(newItems: List<HomeItem>) {
        items = newItems
        val placements = pack(newItems)
        val keys = placements.map { it.item.key }.toSet()
        views.keys.filter { it !in keys }.forEach { grid.removeView(views.remove(it)) }

        for (p in placements) {
            val item = p.item
            val lp = TileGrid.LayoutParams(p.col, p.row, if (item is HomeItem.Header) TileGrid.COLUMNS else item.span, item is HomeItem.Header)
            val view = views[item.key] ?: create(item).also {
                views[item.key] = it
                grid.addView(it, lp)
            }
            view.layoutParams = lp
            if (item is HomeItem.Header) (view as TextView).text = item.title else (view.tag as Tile).bind(item)
        }
        order = placements.map { it.item.key }
        grid.requestLayout()
        updateDim()
    }

    fun view(key: String): View? = views[key]

    fun firstTile(): View? = order.asSequence().mapNotNull { views[it] }.firstOrNull { it.isFocusable }

    /** Swaps a camera's image without touching anything else. */
    fun setSnapshot(cameraId: String, bitmap: Bitmap) {
        snapshots[cameraId] = bitmap
        (views[HomeItem.keyFor(HomeItem.cameraEntry(cameraId), Section.APPS)]?.tag as? Tile)?.showSnapshot()
    }

    /** Cameras whose tiles are at least partly on screen. */
    fun visibleCameras(): List<Camera> = items.filterIsInstance<HomeItem.CameraTile>()
        .filter { views[it.key]?.getGlobalVisibleRect(Rect()) == true }
        .map { it.camera }

    /**
     * Places tiles section by section, each item at the first free spot that fits it (like CSS
     * grid's dense packing), so small tiles fill in beside large ones instead of leaving holes.
     */
    private fun pack(items: List<HomeItem>): List<Placement> {
        val placements = mutableListOf<Placement>()
        var sectionTop = 0
        val taken = mutableListOf<BooleanArray>()

        fun free(row: Int, col: Int, span: Int) = (row until row + span).all { r ->
            r >= taken.size || (col until col + span).none { taken[r][it] }
        }

        for (item in items) {
            if (item is HomeItem.Header) {
                val row = sectionTop + taken.size
                placements += Placement(item, 0, row)
                sectionTop = row + 1
                taken.clear()
                continue
            }
            val span = item.span.coerceIn(1, TileGrid.COLUMNS)
            var row = 0
            var col = -1
            while (col < 0) {
                col = (0..TileGrid.COLUMNS - span).firstOrNull { free(row, it, span) } ?: -1
                if (col < 0) row++
            }
            while (taken.size < row + span) taken += BooleanArray(TileGrid.COLUMNS)
            for (r in row until row + span) for (c in col until col + span) taken[r][c] = true
            placements += Placement(item, col, sectionTop + row)
        }
        return placements
    }

    private fun create(item: HomeItem): View =
        if (item is HomeItem.Header) {
            inflater.inflate(R.layout.item_header, grid, false)
        } else {
            inflater.inflate(R.layout.item_tile, grid, false).also { it.tag = Tile(it) }
        }

    /** While a tile has focus, fade the others; when focus leaves the grid, restore them. */
    private fun updateDim() {
        // Runs after focus settles, so moving between tiles doesn't flash everything bright.
        grid.post {
            val focused = grid.focusedChild
            for (view in views.values) {
                if (view.tag !is Tile) continue
                val alpha = if (focused == null || view == focused) 1f else DIMMED_ALPHA
                view.animate().alpha(alpha).setDuration(150).start()
            }
        }
    }

    private inner class Tile(private val view: View) {
        private val banner: ImageView = view.findViewById(R.id.banner)
        private val iconGroup: View = view.findViewById(R.id.icon_group)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val label: TextView = view.findViewById(R.id.label)
        private val statusDot: View = view.findViewById(R.id.status_dot)
        private val caption: TextView = view.findViewById(R.id.caption)
        private lateinit var item: HomeItem

        init {
            view.clipToOutline = true
            view.setOnFocusChangeListener { v, focused ->
                // Large tiles grow less, or they'd cover their neighbours.
                val scale = if (!focused) 1f else if (item.span > 1) LARGE_FOCUSED_SCALE else FOCUSED_SCALE
                v.animate().scaleX(scale).scaleY(scale).translationZ(if (focused) 12f else 0f)
                    .setDuration(150).start()
                updateDim()
            }
            view.setOnClickListener { onClick(item) }
            view.setOnLongClickListener { onMenu(item); true }
            view.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                    onMenu(item); true
                } else {
                    false
                }
            }
        }

        fun bind(item: HomeItem) {
            this.item = item
            statusDot.visibility = View.GONE
            caption.visibility = View.GONE
            view.isActivated = false
            when (item) {
                is HomeItem.App -> bindApp(item)
                is HomeItem.Action -> bindAction(item)
                is HomeItem.Input -> bindInput(item)
                is HomeItem.Placeholder -> bindPlaceholder(item)
                is HomeItem.CameraTile -> bindCamera(item)
                is HomeItem.Header -> Unit
            }
        }

        private fun bindApp(item: HomeItem.App) {
            val app = item.app
            view.contentDescription = app.label
            view.isActivated = item.moving
            if (app.banner != null) {
                banner.setImageDrawable(app.banner)
                banner.visibility = View.VISIBLE
                iconGroup.visibility = View.GONE
            } else {
                icon.setImageDrawable(app.icon)
                showIcon(app.label)
            }
        }

        private fun bindAction(item: HomeItem.Action) {
            view.contentDescription =
                if (item.warning) view.context.getString(R.string.logs_needs_attention, item.label) else item.label
            statusDot.setBackgroundResource(R.drawable.status_dot_warn)
            statusDot.visibility = if (item.warning) View.VISIBLE else View.GONE
            icon.setImageResource(item.icon)
            showIcon(item.label)
        }

        private fun bindInput(item: HomeItem.Input) {
            view.contentDescription =
                if (item.input.on) view.context.getString(R.string.input_on, item.label) else item.label
            view.isActivated = item.moving
            statusDot.setBackgroundResource(R.drawable.status_dot)
            statusDot.visibility = if (item.input.on) View.VISIBLE else View.GONE
            icon.setImageResource(R.drawable.ic_input)
            showIcon(item.label)
        }

        private fun bindPlaceholder(item: HomeItem.Placeholder) {
            view.contentDescription = view.context.getString(R.string.placeholder)
            view.isActivated = item.moving
            banner.setImageDrawable(null)
            banner.visibility = View.GONE
            iconGroup.visibility = View.GONE
        }

        private fun bindCamera(item: HomeItem.CameraTile) {
            view.contentDescription = item.camera.name
            view.isActivated = item.moving
            caption.text = item.camera.name
            icon.setImageResource(R.drawable.ic_camera)
            showSnapshot()
        }

        /** Until the first frame arrives the tile shows a camera icon and the name. */
        fun showSnapshot() {
            val camera = (item as? HomeItem.CameraTile)?.camera ?: return
            val snapshot = snapshots[camera.id]
            if (snapshot == null) {
                caption.visibility = View.GONE
                showIcon(camera.name)
                return
            }
            banner.setImageBitmap(snapshot)
            banner.visibility = View.VISIBLE
            iconGroup.visibility = View.GONE
            caption.visibility = View.VISIBLE
        }

        private fun showIcon(text: String) {
            banner.setImageDrawable(null)
            banner.visibility = View.GONE
            iconGroup.visibility = View.VISIBLE
            label.text = text
        }
    }

    companion object {
        private const val FOCUSED_SCALE = 1.12f
        private const val LARGE_FOCUSED_SCALE = 1.04f
        private const val DIMMED_ALPHA = 0.55f
    }
}
