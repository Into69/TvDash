package dev.tvdash.launcher

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var scroll: ScrollView
    private lateinit var grid: TileGrid
    private lateinit var board: TileBoard
    private lateinit var nowPlaying: NowPlaying
    private lateinit var weatherFeed: WeatherFeed
    private lateinit var clockView: TextClock
    private lateinit var dateView: TextClock

    private var apps: List<AppEntry> = emptyList()
    private var inputs: List<TvInput> = emptyList()
    private var cameras: List<Camera> = emptyList()

    /** "Later" on the setup prompt (or it was already shown this visit). */
    private var setupDeferred = false

    /** Until the console answers once, camera entries in the saved order are kept even though unknown. */
    private var camerasLoaded = false
    private var weatherJob: Job? = null
    private var snapshotJob: Job? = null


    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val tvInputs by lazy { getSystemService(TvInputManager::class.java) }

    /** Whether the network tile shows Ethernet (wired) rather than Wi-Fi. */
    private var onEthernet = false

    /** Swaps the network tile when the TV switches between Wi-Fi and a cable. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
        override fun onLost(network: Network) = update()

        private fun update() = runOnUiThread {
            val wired = isOnEthernet()
            if (wired != onEthernet) {
                onEthernet = wired
                render()
            }
        }
    }

    /** Refreshes input tiles when devices power on/off or ports appear. */
    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) = reloadInputs()
        override fun onInputAdded(inputId: String) = reloadInputs()
        override fun onInputRemoved(inputId: String) = reloadInputs()
        override fun onInputUpdated(inputId: String) = reloadInputs()
    }

    private fun isOnEthernet(): Boolean =
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true

    /** The tile (package, placeholder or input id) currently picked up for rearranging, if any. */
    private data class Moving(val id: String, val section: Section)
    private var moving: Moving? = null

    /** Dialogs on screen (menus, settings, updates), so Home can close them. */
    private val openDialogs = mutableListOf<AlertDialog>()

    /** Counts Home presses, so work that finishes after one doesn't pop a dialog up afterwards. */
    private var homePresses = 0

    /**
     * AlertDialog.Builder whose dialogs Home closes: they're in [openDialogs] from creation until
     * dismissed. Every home-screen dialog should use it (a plain AlertDialog.Builder stays open).
     */
    private inner class DialogBuilder : AlertDialog.Builder(this@MainActivity) { // home-ok: the tracking builder itself
        private var onDismiss: DialogInterface.OnDismissListener? = null

        override fun setOnDismissListener(listener: DialogInterface.OnDismissListener?): AlertDialog.Builder {
            onDismiss = listener
            return this
        }

        override fun create(): AlertDialog = super.create().also { dialog ->
            openDialogs += dialog
            dialog.setOnDismissListener {
                openDialogs -= dialog
                onDismiss?.onDismiss(it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        scroll = findViewById(R.id.scroll)
        grid = findViewById(R.id.grid)
        board = TileBoard(grid, ::onItemClick, ::onItemMenu)
        nowPlaying = NowPlaying(this, findViewById(R.id.now_playing)) { focusFirstTileAfterLayout() }
        findViewById<View>(R.id.np_screensaver).setOnClickListener {
            start(Intent(this, ScreensaverActivity::class.java), getString(R.string.np_screensaver))
        }

        clockView = findViewById(R.id.clock)
        dateView = findViewById(R.id.date)
        applyTimeZone(prefs.clockTimeZone)

        weatherFeed = WeatherFeed(this, prefs)

        // This is the home screen: Back has nowhere to go, so it just returns to the top.
        onBackPressedDispatcher.addCallback(this) { scrollToTop() }
        applyConfigIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Home pressed while already home (tv.ps1's config intents come here too, without HOME):
        // close any menu or dialog, then back to the top. Cancel rather than dismiss, so they act
        // as if Back was pressed (e.g. setup's "Later").
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            homePresses++
            openDialogs.toList().forEach { it.cancel() }
        }
        scrollToTop()
        applyConfigIntent(intent)
    }

    /**
     * Lets tools/tv.ps1 set the Weather Underground station from the PC, since typing an
     * API key with a remote is miserable: `am start ... --es wu_station ID --es wu_key KEY`.
     */
    private fun applyConfigIntent(intent: Intent) {
        intent.getStringExtra(EXTRA_LAYOUT)?.let { encoded ->
            intent.removeExtra(EXTRA_LAYOUT)
            importLayout(encoded)
        }
        if (intent.getBooleanExtra(EXTRA_WU_CLEAR, false)) {
            prefs.wuStation = null
        } else {
            val station = intent.getStringExtra(EXTRA_WU_STATION)?.trim()
            val key = intent.getStringExtra(EXTRA_WU_KEY)?.trim().orEmpty().ifEmpty { prefs.defaultWuKey }
            if (station.isNullOrEmpty() || key.isEmpty()) return
            prefs.wuStation = WuStation(station, key)
        }
        intent.removeExtra(EXTRA_WU_STATION)
        intent.removeExtra(EXTRA_WU_KEY)
        intent.removeExtra(EXTRA_WU_CLEAR)
        lifecycleScope.launch { refreshWeather() }
    }

    /** From `tv.ps1 import`: base64 of a [LayoutTransfer] export from the master TV. */
    private fun importLayout(encoded: String) {
        try {
            val layout = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
            LayoutTransfer.import(prefs, layout, AppSource.launchablePackages(this))
        } catch (e: JSONException) {
            Log.w(TAG, "Bad layout import", e)
            Toast.makeText(this, R.string.layout_import_failed, Toast.LENGTH_LONG).show()
            return
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Bad layout import", e)
            Toast.makeText(this, R.string.layout_import_failed, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.layout_imported, Toast.LENGTH_SHORT).show()
        applyTimeZone(prefs.clockTimeZone)
        weatherFeed.clearCache()
        setupDeferred = false
        // onResume follows (new intent or first start) and reloads apps, weather and the setup prompt.
    }

    /** Apps the imported layout wants that still aren't installed (installed ones are crossed off). */
    private fun pendingInstalls(): List<String> {
        val installed = apps.map { it.packageName }.toSet()
        val wanted = prefs.importWanted
        val remaining = wanted.filter { it !in installed }
        if (remaining.size != wanted.size) prefs.importWanted = remaining
        return remaining
    }

    /** One app at a time: Install opens its Play Store page; Skip drops it from the list for good. */
    private fun showSetup() {
        val pending = pendingInstalls()
        if (pending.isEmpty()) return
        val labels = prefs.importLabels
        fun skip(pkg: String) {
            prefs.importWanted = prefs.importWanted - pkg
        }
        DialogBuilder()
            .setTitle(R.string.setup_title)
            .setItems(pending.map { labels[it] ?: it }.toTypedArray()) { _, which ->
                val pkg = pending[which]
                DialogBuilder()
                    .setTitle(labels[pkg] ?: pkg)
                    .setItems(arrayOf(getString(R.string.setup_install), getString(R.string.setup_skip))) { _, choice ->
                        if (choice == 0) {
                            // Coming back from the store should continue with the rest of the list.
                            setupDeferred = false
                            openStore(pkg)
                        } else {
                            skip(pkg)
                            showSetup()
                        }
                    }
                    .show()
            }
            .setNeutralButton(R.string.setup_skip_all) { _, _ -> prefs.importWanted = emptyList() }
            .setNegativeButton(R.string.setup_later) { _, _ -> setupDeferred = true }
            .setOnCancelListener { setupDeferred = true }
            .show()
    }

    private fun openStore(pkg: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
        try {
            startActivity(market)
        } catch (e: ActivityNotFoundException) {
            start(web, prefs.importLabels[pkg] ?: pkg)
        }
    }

    override fun onResume() {
        super.onResume()
        onEthernet = isOnEthernet()
        connectivity.registerDefaultNetworkCallback(networkCallback)
        tvInputs?.registerCallback(inputCallback, Handler(Looper.getMainLooper()))
        nowPlaying.start()
        reload()
        weatherJob = lifecycleScope.launch {
            while (isActive) {
                refreshWeather()
                delay(weatherFeed.refreshIntervalMs)
            }
        }
        if (ProtectClient.isConfigured) {
            snapshotJob = lifecycleScope.launch {
                while (isActive) {
                    refreshSnapshots()
                    delay(SNAPSHOT_INTERVAL_MS)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        weatherJob?.cancel()
        snapshotJob?.cancel()
        connectivity.unregisterNetworkCallback(networkCallback)
        tvInputs?.unregisterCallback(inputCallback)
        nowPlaying.stop()
        // Leaving mid-move (e.g. Home) keeps the order so far but drops the picked-up state.
        moving = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        moving?.let { if (handleMoveKey(it, event)) return true }
        return super.dispatchKeyEvent(event)
    }

    /** While a tile is picked up, arrows move it and OK/Back drops it. Other keys (volume) pass through. */
    private fun handleMoveKey(m: Moving, event: KeyEvent): Boolean {
        val delta = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -TileGrid.COLUMNS
            KeyEvent.KEYCODE_DPAD_DOWN -> TileGrid.COLUMNS
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK -> 0
            else -> return false
        }
        if (delta != 0 && event.action == KeyEvent.ACTION_DOWN) moveBy(m, delta)
        if (delta == 0 && event.action == KeyEvent.ACTION_UP) stopMoving()
        return true
    }

    /** Returns the tile's key so the caller can keep focus on it. */
    private fun startMoving(id: String, section: Section): String {
        moving = Moving(id, section)
        Toast.makeText(this, R.string.move_hint, Toast.LENGTH_LONG).show()
        return HomeItem.keyFor(id, section)
    }

    private fun stopMoving() {
        val m = moving ?: return
        moving = null
        render(focusKey = HomeItem.keyFor(m.id, m.section))
    }

    /** Moves by position in the section's order; ±a row's worth is up/down (approximately, beside 2×2 tiles). */
    private fun moveBy(m: Moving, delta: Int) {
        val order = sectionOrder(m.section).toMutableList()
        val from = order.indexOf(m.id)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (to == from) return
        order.add(to, order.removeAt(from))
        saveSectionOrder(m.section, order)
        render(focusKey = HomeItem.keyFor(m.id, m.section))
    }

    /** Inserts an empty tile right after [id] and returns its key. */
    private fun addPlaceholderAfter(id: String, section: Section): String {
        val order = sectionOrder(section).toMutableList()
        val placeholder = HomeItem.PLACEHOLDER_PREFIX + System.currentTimeMillis()
        order.add(order.indexOf(id) + 1, placeholder)
        saveSectionOrder(section, order)
        return HomeItem.keyFor(placeholder, section)
    }

    /** Placeholders and cameras exist only in the order list, so removing them from it deletes them. */
    private fun removeEntry(id: String, section: Section) =
        saveSectionOrder(section, sectionOrder(section) - id)

    /** Pick a camera that isn't on the dashboard yet, then its size; it goes right after [afterId]. */
    private fun addCameraAfter(afterId: String) {
        val onDashboard = sectionOrder(Section.APPS).filter(HomeItem::isCamera).map(HomeItem::cameraIdOf).toSet()
        val choices = cameras.filter { it.id !in onDashboard }
        DialogBuilder()
            .setTitle(R.string.pick_camera)
            .setItems(choices.map { it.name }.toTypedArray()) { _, which ->
                val camera = choices[which]
                DialogBuilder()
                    .setTitle(R.string.pick_size)
                    .setItems(arrayOf(getString(R.string.size_small), getString(R.string.size_large))) { _, size ->
                        prefs.cameraSizes = prefs.cameraSizes + (camera.id to size + 1)
                        val order = sectionOrder(Section.APPS).toMutableList()
                        val entry = HomeItem.cameraEntry(camera.id)
                        order.add(order.indexOf(afterId) + 1, entry)
                        saveSectionOrder(Section.APPS, order)
                        render(focusKey = HomeItem.keyFor(entry, Section.APPS))
                        lifecycleScope.launch { refreshSnapshots() }
                    }
                    .show()
            }
            .show()
    }

    private fun canAddCamera() = cameras.size > sectionOrder(Section.APPS).count(HomeItem::isCamera)

    /**
     * Tile ids of a section as shown: the saved order, then anything not in it yet (new installs
     * A–Z, new inputs in port order). Uninstalled, unplugged and hidden entries are dropped.
     */
    private fun sectionOrder(section: Section): List<String> {
        val available: List<String>
        val saved: List<String>
        val hidden: Set<String>
        when (section) {
            Section.FAVORITES -> {
                val installed = apps.map { it.packageName }.toSet()
                return prefs.favorites.filter { HomeItem.isPlaceholder(it) || it in installed }
            }
            Section.APPS -> {
                available = apps.map { it.packageName }
                saved = prefs.appOrder
                hidden = prefs.hidden
            }
            Section.INPUTS -> {
                available = inputs.map { it.id }
                saved = prefs.inputOrder
                hidden = prefs.hiddenInputs
            }
        }
        val availableSet = available.toSet()
        val knownCameras = cameras.map { it.id }.toSet()
        fun keep(id: String) = when {
            HomeItem.isPlaceholder(id) -> true
            // Don't drop cameras just because the console hasn't answered (yet).
            HomeItem.isCamera(id) -> !camerasLoaded || HomeItem.cameraIdOf(id) in knownCameras
            else -> id in availableSet && id !in hidden
        }
        val kept = saved.filter(::keep)
        val keptSet = kept.toSet()
        return kept + available.filter { it !in keptSet && it !in hidden }
    }

    private fun saveSectionOrder(section: Section, order: List<String>) = when (section) {
        Section.FAVORITES -> prefs.favorites = order
        Section.APPS -> prefs.appOrder = order
        Section.INPUTS -> prefs.inputOrder = order
    }

    private fun focusItem(key: String) {
        grid.doOnLayout { board.view(key)?.requestFocus() }
    }

    private fun scrollToTop() {
        scroll.scrollTo(0, 0)
        focusFirstTileAfterLayout()
    }

    /** New tiles only have a position once the grid has laid out, so wait for that before focusing. */
    private fun focusFirstTileAfterLayout() {
        grid.doOnLayout { board.firstTile()?.requestFocus() }
    }

    private fun reload() {
        lifecycleScope.launch {
            val (loadedApps, loadedInputs) = withContext(Dispatchers.IO) {
                AppSource.load(this@MainActivity) to InputSource.load(this@MainActivity)
            }
            apps = loadedApps
            inputs = loadedInputs
            render()
            if (!setupDeferred && pendingInstalls().isNotEmpty()) {
                setupDeferred = true // once per visit; installing an app resets it
                showSetup()
            }
            if (ProtectClient.isConfigured) {
                // Keep the last list if the console is unreachable, so tiles don't vanish on a blip.
                cameras = try {
                    ProtectClient.cameras().also { camerasLoaded = true }
                } catch (e: IOException) {
                    Log.w(TAG, "Couldn't load cameras from ${ProtectClient.host}", e)
                    cameras
                } catch (e: JSONException) {
                    Log.w(TAG, "Unexpected camera list from ${ProtectClient.host}", e)
                    cameras
                }
                // Cameras arrive after the first render and can land before the focused first tile.
                // If the user hasn't moved yet, keep them on whatever is first now.
                render(toTop = board.firstTile()?.isFocused == true)
                // New camera tiles only count as on screen once laid out; without waiting, the first
                // frames would only arrive on the next refresh tick.
                grid.doOnLayout { lifecycleScope.launch { refreshSnapshots() } }
            }
        }
    }

    /** Fetches frames for camera tiles currently on screen; off-screen ones wait until scrolled to. */
    private suspend fun refreshSnapshots() {
        for (camera in board.visibleCameras()) {
            val bitmap = try {
                ProtectClient.snapshot(camera.id, SNAPSHOT_WIDTH_PX)
            } catch (e: IOException) {
                Log.w(TAG, "Snapshot failed for ${camera.name}", e)
                null
            } ?: continue
            board.setSnapshot(camera.id, bitmap)
        }
    }

    private fun reloadInputs() {
        lifecycleScope.launch {
            inputs = withContext(Dispatchers.IO) { InputSource.load(this@MainActivity) }
            render()
        }
    }

    /** [focusKey] focuses that tile afterwards; [toTop] scrolls up and focuses the first tile. */
    private fun render(focusKey: String? = null, toTop: Boolean = false) {
        val byPackage = apps.associateBy { it.packageName }
        val byInput = inputs.associateBy { it.id }
        val byCamera = cameras.associateBy { it.id }
        val sizes = prefs.cameraSizes
        val names = prefs.inputNames
        val m = moving
        fun isMoving(id: String, section: Section) = m != null && m.id == id && m.section == section
        fun tiles(section: Section) = sectionOrder(section).mapNotNull { id ->
            when {
                HomeItem.isPlaceholder(id) -> HomeItem.Placeholder(id, section, isMoving(id, section))
                HomeItem.isCamera(id) -> byCamera[HomeItem.cameraIdOf(id)]?.let {
                    HomeItem.CameraTile(it, sizes[it.id] ?: 2, isMoving(id, section))
                }
                section == Section.INPUTS ->
                    byInput[id]?.let { HomeItem.Input(it, names[id] ?: it.label, isMoving(id, section)) }
                else -> byPackage[id]?.let { HomeItem.App(it, section, isMoving(id, section)) }
            }
        }
        val favorites = tiles(Section.FAVORITES)

        val items = buildList {
            if (favorites.isNotEmpty()) {
                add(HomeItem.Header(getString(R.string.section_favorites)))
                addAll(favorites)
            }
            // No header: the apps follow straight on from the weather (or Favorites).
            addAll(tiles(Section.APPS))

            add(HomeItem.Header(getString(R.string.section_system)))
            addAll(tiles(Section.INPUTS))
            // Same id either way, so the tile swaps in place and keeps focus.
            if (onEthernet) {
                add(HomeItem.Action(ACTION_NETWORK, getString(R.string.action_ethernet), R.drawable.ic_ethernet))
            } else {
                add(HomeItem.Action(ACTION_NETWORK, getString(R.string.action_wifi), R.drawable.ic_wifi))
            }
            add(HomeItem.Action(ACTION_SETTINGS, getString(R.string.action_settings), R.drawable.ic_settings))
            add(HomeItem.Action(ACTION_UPDATES, getString(R.string.action_updates), R.drawable.ic_update))
            add(HomeItem.Action(ACTION_LOGS, getString(R.string.action_logs), R.drawable.ic_logs))
            add(HomeItem.Action(ACTION_LAUNCHER, getString(R.string.action_launcher), R.drawable.ic_tune))
        }

        val firstRender = board.items.isEmpty()
        board.submit(items)
        when {
            focusKey != null -> focusItem(focusKey)
            toTop -> scrollToTop()
            // Also covers the focused tile having just been removed (hidden, uninstalled).
            firstRender || currentFocus == null || currentFocus == scroll -> focusFirstTileAfterLayout()
        }
    }

    private fun onItemClick(item: HomeItem) {
        when (item) {
            is HomeItem.App -> AppSource.launchIntent(packageManager, item.app.packageName)
                ?.let { start(it, item.app.label) }
            is HomeItem.Input -> start(InputSource.intent(item.input.id), item.label)
            is HomeItem.CameraTile -> start(CameraActivity.intent(this, item.camera), item.camera.name)
            is HomeItem.Action -> when (item.id) {
                // There's no public Ethernet settings screen; on TVs this opens Network & Internet, which covers both.
                ACTION_NETWORK -> start(Intent(Settings.ACTION_WIFI_SETTINGS), item.label)
                ACTION_SETTINGS -> start(Intent(Settings.ACTION_SETTINGS), item.label)
                ACTION_LOGS -> start(Intent(this, LogsActivity::class.java), item.label)
                ACTION_UPDATES -> showUpdates()
                ACTION_LAUNCHER -> launcherSettings()
            }
            // Placeholders are just spacing.
            is HomeItem.Header, is HomeItem.Placeholder -> Unit
        }
    }

    private fun start(intent: Intent, label: String) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.cannot_open, label), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.cannot_open, label), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Update screens this TV has, so updates happen when it suits: the TV's software update (Google's
     * updater, which delivers Sony's firmware on BRAVIAs; NVIDIA's on the SHIELD) and the Play Store's
     * app updates. Only screens that exist are offered. TvDash itself comes first: [SelfUpdate].
     */
    private fun showUpdates() {
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        if (SelfUpdate.isAvailable) {
            entries += getString(R.string.updates_tvdash, BuildConfig.VERSION_CODE) to ::checkForTvDashUpdate
        }
        listOf(
            getString(R.string.updates_tv) to Intent(ACTION_SYSTEM_UPDATE),
            getString(R.string.updates_apps) to Intent(ACTION_PLAY_MY_APPS),
        ).filter { packageManager.resolveActivity(it.second, 0) != null }
            .forEach { (label, intent) -> entries += label to { start(intent, label) } }
        if (entries.isEmpty()) {
            Toast.makeText(this, getString(R.string.cannot_open, getString(R.string.action_updates)), Toast.LENGTH_SHORT).show()
            return
        }
        DialogBuilder()
            .setTitle(R.string.action_updates)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .show()
    }

    private fun checkForTvDashUpdate() {
        if (SelfUpdate.inProgress) {
            Toast.makeText(this, R.string.update_in_progress, Toast.LENGTH_LONG).show()
            return
        }
        val pressesBefore = homePresses
        lifecycleScope.launch {
            val release = try {
                SelfUpdate.latest()
            } catch (e: IOException) {
                Log.w(TAG, "Update check failed", e)
                Toast.makeText(this@MainActivity, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                return@launch
            } catch (e: JSONException) {
                Log.w(TAG, "Unexpected release info", e)
                Toast.makeText(this@MainActivity, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (release == null || release.versionCode <= BuildConfig.VERSION_CODE) {
                Toast.makeText(this@MainActivity, getString(R.string.update_none, BuildConfig.VERSION_CODE), Toast.LENGTH_LONG).show()
                return@launch
            }
            // Home was pressed while checking: don't pop the offer up over the home screen.
            if (homePresses != pressesBefore) return@launch
            DialogBuilder()
                .setTitle(getString(R.string.update_available, release.versionCode))
                .setMessage(getString(R.string.update_message, BuildConfig.VERSION_CODE))
                .setPositiveButton(R.string.update_install) { _, _ -> installTvDashUpdate(release) }
                .setNegativeButton(R.string.setup_later, null)
                .show()
        }
    }

    /**
     * Shows download progress; once installed, Android restarts TvDash on the new build. The
     * progress dialog isn't a [DialogBuilder] one: Home leaves it up, since the update carries on.
     */
    private fun installTvDashUpdate(release: SelfUpdate.Release) {
        if (SelfUpdate.inProgress) return
        SelfUpdate.inProgress = true
        val progress = AlertDialog.Builder(this) // home-ok: the update carries on, so Home leaves it up
            .setTitle(getString(R.string.update_downloading, release.versionCode))
            .setMessage(" ")
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            try {
                SelfUpdate.install(this@MainActivity, release) { percent ->
                    if (percent >= 0) progress.setMessage("$percent%")
                }
                // Android has it now: it restarts TvDash when done, or UpdateStatusReceiver reports
                // a failure (or a declined confirmation), so there's nothing left to wait for here.
                progress.dismiss()
                Toast.makeText(this@MainActivity, R.string.update_installing, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                SelfUpdate.inProgress = false
                Log.w(TAG, "Update download failed", e)
                progress.dismiss()
                Toast.makeText(this@MainActivity, getString(R.string.update_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Long-press menu. Each option returns a tile key to focus afterwards, if any. */
    private fun onItemMenu(item: HomeItem) {
        val options = mutableListOf<Pair<Int, () -> String?>>()
        val title: String
        when (item) {
            is HomeItem.App -> {
                val pkg = item.app.packageName
                val favorites = prefs.favorites
                title = item.app.label
                if (pkg in favorites) {
                    options += R.string.menu_remove_favorite to { prefs.favorites = favorites - pkg; null }
                } else {
                    options += R.string.menu_add_favorite to { prefs.favorites = favorites + pkg; null }
                }
                options += R.string.menu_move to { startMoving(pkg, item.section) }
                options += R.string.menu_add_placeholder to { addPlaceholderAfter(pkg, item.section) }
                if (item.section == Section.APPS && canAddCamera()) {
                    options += R.string.menu_add_camera to { addCameraAfter(pkg); null }
                }
                if (item.section == Section.APPS) {
                    options += R.string.menu_hide to {
                        prefs.hidden = prefs.hidden + pkg
                        prefs.favorites = favorites - pkg
                        null
                    }
                }
                options += R.string.menu_app_info to {
                    start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)), title)
                    null
                }
            }
            is HomeItem.Placeholder -> {
                title = getString(R.string.placeholder)
                options += R.string.menu_move to { startMoving(item.id, item.section) }
                options += R.string.menu_add_placeholder to { addPlaceholderAfter(item.id, item.section) }
                if (item.section == Section.APPS && canAddCamera()) {
                    options += R.string.menu_add_camera to { addCameraAfter(item.id); null }
                }
                // A placeholder has nothing to unhide later, so hiding it just deletes it.
                options += R.string.menu_hide_placeholder to { removeEntry(item.id, item.section); null }
            }
            is HomeItem.CameraTile -> {
                val camera = item.camera
                val entry = HomeItem.cameraEntry(camera.id)
                title = camera.name
                options += R.string.menu_move to { startMoving(entry, Section.APPS) }
                options += (if (item.size > 1) R.string.menu_make_small else R.string.menu_make_large) to {
                    prefs.cameraSizes = prefs.cameraSizes + (camera.id to if (item.size > 1) 1 else 2)
                    item.key
                }
                options += R.string.menu_add_placeholder to { addPlaceholderAfter(entry, Section.APPS) }
                if (canAddCamera()) options += R.string.menu_add_camera to { addCameraAfter(entry); null }
                // Cameras exist only on the dashboard, so hiding one removes it; it can be added again.
                options += R.string.menu_hide_camera to { removeEntry(entry, Section.APPS); null }
            }
            is HomeItem.Input -> {
                val id = item.input.id
                title = item.label
                options += R.string.menu_move to { startMoving(id, Section.INPUTS) }
                options += R.string.menu_rename to { renameInput(item); null }
                options += R.string.menu_hide_input to { prefs.hiddenInputs = prefs.hiddenInputs + id; null }
            }
            else -> return
        }

        DialogBuilder()
            .setTitle(title)
            .setItems(options.map { getString(it.first) }.toTypedArray()) { _, which ->
                render(focusKey = options[which].second())
            }
            .show()
    }

    /**
     * Starts empty (the current name is the hint), since TV keyboards make deleting text slow and
     * don't reliably replace a selection. Saving empty keeps the name; "Use TV's name" resets it.
     */
    private fun renameInput(item: HomeItem.Input) {
        val id = item.input.id
        val field = EditText(this).apply {
            hint = item.label
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        fun save(name: String?) {
            prefs.inputNames = if (name == null || name == item.input.label) {
                prefs.inputNames - id
            } else {
                prefs.inputNames + (id to name)
            }
            render(focusKey = item.key)
        }
        DialogBuilder()
            .setTitle(getString(R.string.rename_title, item.input.label))
            .setView(field)
            .setPositiveButton(R.string.save) { _, _ ->
                field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(::save)
            }
            .setNeutralButton(R.string.rename_reset) { _, _ -> save(null) }
            .show()
    }

    private fun launcherSettings() {
        val entries = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.settings_location, prefs.location?.name ?: getString(R.string.settings_location_none)) to ::pickLocation,
            getString(R.string.settings_wu, prefs.wuStation?.stationId ?: getString(R.string.settings_wu_off)) to ::wundergroundDialog,
            getString(if (prefs.fahrenheit) R.string.settings_units_f else R.string.settings_units_c) to {
                prefs.fahrenheit = !prefs.fahrenheit
                lifecycleScope.launch { refreshWeather() }
            },
            // Count only what's on this TV: an imported layout can hide apps or inputs this TV doesn't have.
            getString(R.string.settings_hidden, apps.count { it.packageName in prefs.hidden }) to ::manageHiddenApps,
            getString(R.string.settings_hidden_inputs, inputs.count { it.id in prefs.hiddenInputs }) to ::manageHiddenInputs,
            getString(R.string.settings_sort) to {
                // Placeholders only make sense in a custom order, so they go; cameras stay, first.
                prefs.appOrder = sectionOrder(Section.APPS).filter(HomeItem::isCamera)
                render()
            },
        )
        entries.add(3, getString(R.string.settings_screensaver, screensaverLabel(currentScreensaverDelay())) to ::pickScreensaverDelay)
        if (cameras.isNotEmpty()) {
            val chosen = cameras.firstOrNull { it.id == prefs.screensaverCamera }?.name ?: getString(R.string.screensaver_no_camera)
            entries.add(4, getString(R.string.settings_screensaver_camera, chosen) to ::pickScreensaverCamera)
        }
        val pending = pendingInstalls()
        if (pending.isNotEmpty()) entries.add(0, getString(R.string.settings_finish_setup, pending.size) to ::showSetup)
        DialogBuilder()
            .setTitle(R.string.action_launcher)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .show()
    }

    private fun wundergroundDialog() {
        val current = prefs.wuStation
        fun field(hintRes: Int, value: String?) = EditText(this).apply {
            hint = getString(hintRes)
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        val stationField = field(R.string.wu_station_hint, current?.stationId)
        val defaultKey = prefs.defaultWuKey
        val hasDefaultKey = defaultKey.isNotEmpty()
        val keyField = field(
            if (hasDefaultKey) R.string.wu_key_hint_default else R.string.wu_key_hint,
            current?.apiKey?.takeIf { it != defaultKey },
        )
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(stationField)
            addView(keyField)
        }
        val builder = DialogBuilder()
            .setTitle(R.string.wu_title)
            .setView(form)
            .setPositiveButton(R.string.save) { _, _ ->
                val key = keyField.text.toString().trim().ifEmpty { prefs.defaultWuKey }
                val station = WuStation(stationField.text.toString().trim(), key)
                if (station.stationId.isNotEmpty() && station.apiKey.isNotEmpty()) saveStation(station)
            }
        if (current != null) {
            builder.setNeutralButton(R.string.wu_turn_off) { _, _ ->
                prefs.wuStation = null
                lifecycleScope.launch { refreshWeather() }
            }
        }
        builder.show()
    }

    /** Checks the station answers before switching to it, so a typo doesn't blank the weather. */
    private fun saveStation(station: WuStation) {
        lifecycleScope.launch {
            val message = try {
                WundergroundClient.current(station, prefs.fahrenheit)
                prefs.wuStation = station
                getString(R.string.wu_saved, station.stationId)
            } catch (e: WundergroundClient.InvalidKey) {
                getString(R.string.wu_invalid_key)
            } catch (e: WundergroundClient.StationOffline) {
                getString(R.string.wu_offline, station.stationId)
            } catch (e: IOException) {
                getString(R.string.weather_unavailable)
            } catch (e: JSONException) {
                getString(R.string.weather_unavailable)
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            refreshWeather()
        }
    }

    /**
     * How long the TV sits idle before the screensaver starts, or null when it's off. On Android TV
     * that idle time is the system screen timeout; "off" is the secure screensaver_enabled switch.
     */
    private fun currentScreensaverDelay(): Long? {
        if (Settings.Secure.getInt(contentResolver, SCREENSAVER_ENABLED, 1) == 0) return null
        return Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 10 * 60_000L)
    }

    private fun screensaverLabel(delayMs: Long?): String = when {
        delayMs == null -> getString(R.string.screensaver_off)
        delayMs < 60_000 -> getString(R.string.screensaver_seconds_testing, delayMs / 1000)
        delayMs < 3_600_000 -> resources.getQuantityString(R.plurals.screensaver_minutes, (delayMs / 60_000).toInt(), delayMs / 60_000)
        else -> resources.getQuantityString(R.plurals.screensaver_hours, (delayMs / 3_600_000).toInt(), delayMs / 3_600_000)
    }

    private fun pickScreensaverDelay() {
        val labels = SCREENSAVER_DELAYS.map(::screensaverLabel)
        val current = SCREENSAVER_DELAYS.indexOf(currentScreensaverDelay())
        DialogBuilder()
            .setTitle(R.string.screensaver_title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                setScreensaverDelay(SCREENSAVER_DELAYS[which])
            }
            .show()
    }

    private fun setScreensaverDelay(delayMs: Long?) {
        try {
            if (delayMs == null) {
                Settings.Secure.putInt(contentResolver, SCREENSAVER_ENABLED, 0)
            } else {
                Settings.Secure.putInt(contentResolver, SCREENSAVER_ENABLED, 1)
                Settings.System.putLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, delayMs)
            }
        } catch (e: SecurityException) {
            // Only adb can grant these (tools/tv.ps1 install does).
            Log.w(TAG, "Can't change the screensaver delay", e)
            Toast.makeText(this, R.string.screensaver_no_permission, Toast.LENGTH_LONG).show()
        }
    }

    /** "None" (clock and weather on black) or one camera to fill the screensaver. */
    private fun pickScreensaverCamera() {
        val names = listOf(getString(R.string.screensaver_no_camera)) + cameras.map { it.name }
        val current = cameras.indexOfFirst { it.id == prefs.screensaverCamera } + 1
        DialogBuilder()
            .setTitle(R.string.screensaver_camera_title)
            .setSingleChoiceItems(names.toTypedArray(), current) { dialog, which ->
                prefs.screensaverCamera = if (which == 0) null else cameras[which - 1].id
                dialog.dismiss()
            }
            .show()
    }

    private fun manageHiddenApps() {
        val hidden = apps.filter { it.packageName in prefs.hidden }
        uncheckToUnhide(getString(R.string.settings_hidden, hidden.size), hidden.map { it.packageName to it.label }) {
            prefs.hidden = it
        }
    }

    private fun manageHiddenInputs() {
        val names = prefs.inputNames
        val hidden = inputs.filter { it.id in prefs.hiddenInputs }
        uncheckToUnhide(getString(R.string.settings_hidden_inputs, hidden.size), hidden.map { it.id to (names[it.id] ?: it.label) }) {
            prefs.hiddenInputs = it
        }
    }

    /** Lists hidden [entries] (id to label) checked; unchecking one brings it back. */
    private fun uncheckToUnhide(title: String, entries: List<Pair<String, String>>, save: (Set<String>) -> Unit) {
        if (entries.isEmpty()) return
        val checked = BooleanArray(entries.size) { true }
        DialogBuilder()
            .setTitle(title)
            .setMultiChoiceItems(entries.map { it.second }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.done) { _, _ ->
                save(entries.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet())
                render()
            }
            .show()
    }

    private fun pickLocation() {
        val input = EditText(this).apply {
            hint = getString(R.string.location_prompt)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
        }
        val dialog = DialogBuilder()
            .setTitle(R.string.weather_set_location)
            .setView(input)
            .setPositiveButton(R.string.search) { _, _ -> searchLocation(input.text.toString()) }
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                dialog.dismiss()
                searchLocation(input.text.toString())
                true
            } else {
                false
            }
        }
        dialog.show()
    }

    private fun searchLocation(query: String) {
        if (query.isBlank()) return
        val pressesBefore = homePresses
        lifecycleScope.launch {
            val results = try {
                WeatherClient.search(query.trim())
            } catch (e: IOException) {
                emptyList()
            } catch (e: JSONException) {
                emptyList()
            }
            if (results.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.location_none_found, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (homePresses != pressesBefore) return@launch
            DialogBuilder()
                .setTitle(R.string.weather_set_location)
                .setItems(results.map { it.name }.toTypedArray()) { _, which ->
                    prefs.location = results[which]
                    lifecycleScope.launch { refreshWeather() }
                }
                .show()
        }
    }

    private suspend fun refreshWeather() {
        val result = weatherFeed.load()
        if (result is WeatherFeed.Display) applyTimeZone(result.timeZone)
        WeatherFeed.bind(window.decorView, result)
    }

    /** Shows the clock in the weather location's zone; keeps the current one if [zone] is unusable. */
    private fun applyTimeZone(zone: String?) {
        // TextClock silently falls back to GMT for IDs the device's tz database doesn't know.
        if (zone == null || zone !in TimeZone.getAvailableIDs()) return
        prefs.clockTimeZone = zone
        clockView.timeZone = zone
        dateView.timeZone = zone
    }

    companion object {
        private const val TAG = "TvDash"
        private const val SCREENSAVER_ENABLED = "screensaver_enabled"

        /** Off, a 10-second delay for testing, then 1 minute to 1 hour. */
        private val SCREENSAVER_DELAYS = listOf(
            null, 10_000L, 60_000L, 2 * 60_000L, 5 * 60_000L, 10 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L,
        )
        private const val SNAPSHOT_INTERVAL_MS = 5_000L
        /** About a 2×2 tile's width, so large tiles stay sharp without full-size bitmaps. */
        private const val SNAPSHOT_WIDTH_PX = 720
        private const val EXTRA_WU_STATION = "wu_station"
        private const val EXTRA_WU_KEY = "wu_key"
        private const val EXTRA_WU_CLEAR = "wu_clear"
        private const val EXTRA_LAYOUT = "layout_import"
        private const val ACTION_NETWORK = "network"
        private const val ACTION_SETTINGS = "settings"
        private const val ACTION_LOGS = "logs"
        private const val ACTION_UPDATES = "updates"

        /** Settings.ACTION_SYSTEM_UPDATE_SETTINGS, which the SDK hides. */
        private const val ACTION_SYSTEM_UPDATE = "android.settings.SYSTEM_UPDATE_SETTINGS"

        /** Play Store's installed apps, where pending updates are. */
        private const val ACTION_PLAY_MY_APPS = "com.google.android.finsky.VIEW_MY_DOWNLOADS"
        private const val ACTION_LAUNCHER = "launcher"
    }
}
