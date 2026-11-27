package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.airplay.android.ReceiverService
import ai.aneesiqbal.rozan.launcher.apps.AppEntry
import ai.aneesiqbal.rozan.launcher.apps.InstalledApps
import ai.aneesiqbal.rozan.launcher.apps.PackageChangeReceiver
import ai.aneesiqbal.rozan.launcher.backdrop.BackdropController
import ai.aneesiqbal.rozan.launcher.backdrop.BackdropSource
import ai.aneesiqbal.rozan.launcher.home.AppOptionsPanel
import ai.aneesiqbal.rozan.launcher.home.ClockController
import ai.aneesiqbal.rozan.launcher.home.HomeActions
import ai.aneesiqbal.rozan.launcher.home.HomeChrome
import ai.aneesiqbal.rozan.launcher.home.HomeLayout
import ai.aneesiqbal.rozan.launcher.home.HomeTiles
import ai.aneesiqbal.rozan.launcher.home.TileArtLoader
import ai.aneesiqbal.rozan.launcher.inputs.InputsButtonMode
import ai.aneesiqbal.rozan.launcher.inputs.InputsController
import ai.aneesiqbal.rozan.launcher.inputs.TvInputSources
import ai.aneesiqbal.rozan.launcher.screensaver.IdleTimer
import ai.aneesiqbal.rozan.launcher.settings.LauncherSettings
import ai.aneesiqbal.rozan.ui.FocusScale
import ai.aneesiqbal.rozan.ui.TileGridView
import ai.aneesiqbal.rozan.ui.TileView
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen: backdrop, clock, Inputs and settings buttons, and the app grid.
 *
 * It is the HOME activity (`singleTask`); pressing HOME while it is already
 * showing scrolls back to the first tile.
 */
class HomeActivity :
    LocalizedActivity(),
    TileGridView.Listener,
    IdleTimer.Listener {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: LauncherSettings
    private lateinit var root: HomeLayout
    private lateinit var grid: TileGridView
    private lateinit var settingsButton: View
    private lateinit var caption: TextView
    private lateinit var inputsButton: View
    private lateinit var inputs: InputsController
    private lateinit var chrome: HomeChrome
    private lateinit var tiles: HomeTiles
    private lateinit var backdrop: BackdropController
    private lateinit var clock: ClockController
    private lateinit var options: AppOptionsPanel
    private lateinit var idle: IdleTimer
    private lateinit var actions: HomeActions
    private val packages = PackageChangeReceiver(::onPackageChanged)
    private var appsJob: Job? = null
    private var appsStale = false
    private var resumed = false
    private var started = false

    /** Packages that changed while Home was stopped; handled in [onStart]. */
    private val changedWhileStopped = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        settings = LauncherSettings(this)
        root = findViewById(R.id.home_root)
        grid = findViewById(R.id.app_grid)
        settingsButton = findViewById(R.id.settings_button)
        caption = findViewById(R.id.screensaver_caption)
        inputsButton = findViewById(R.id.inputs_button)
        options = findViewById(R.id.app_options)
        backdrop =
            BackdropController(
                lower = findViewById(R.id.backdrop_lower),
                upper = findViewById(R.id.backdrop_upper),
                source = BackdropSource(this),
                scope = scope,
                fallback = { requireNotNull(getDrawable(R.drawable.backdrop_fallback)) },
                listener = ::onBackdropCoverageChanged,
                onPhotoShown = { image -> caption.text = image?.title.orEmpty() },
            )
        chrome =
            HomeChrome(
                grid = grid,
                restScrim = findViewById(R.id.scrim_rest),
                gridScrim = findViewById(R.id.scrim_grid),
                buttons = findViewById(R.id.top_buttons),
                clock = findViewById(R.id.clock),
                caption = findViewById(R.id.screensaver_caption_band),
                backdrop = backdrop,
                onShown = ::onScreensaverShown,
            )
        idle = IdleTimer(HandlerScheduler(handler), this)
        actions = HomeActions(this, options) { idle.startNow() }
        tiles = HomeTiles(this, grid, TileArtLoader(this, scope), actions)
        clock = ClockController(this, findViewById(R.id.clock))

        grid.listener = this
        grid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            tiles.onTileSizeChanged(grid.tileWidth, grid.tileHeight)
        }
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            backdrop.setViewport(right - left, bottom - top)
        }
        root.focusRouter = HomeLayout.FocusRouter(::routeFocus)
        options.listener = actions
        settingsButton.setOnClickListener { actions.openLauncherSettings() }
        FocusScale.attach(settingsButton, SETTINGS_BUTTON_SCALE)
        FocusScale.attach(inputsButton, SETTINGS_BUTTON_SCALE)
        inputs =
            InputsController(
                activity = this,
                source = TvInputSources.create(this),
                pickerResolver = TvInputSources.pickerResolver(this),
                fireOs = TvInputSources.isFireOs(this),
                scope = scope,
                button = inputsButton,
                gear = settingsButton,
                panel = findViewById(R.id.inputs_panel),
            )

        tiles.setApps(emptyList())
        grid.focusTile(0)
        packages.register(this)
        loadApps()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerBackCallback()
    }

    override fun onStart() {
        super.onStart()
        started = true
        tiles.restoreArt()
        if (changedWhileStopped.isNotEmpty()) {
            for (packageName in changedWhileStopped) tiles.refreshPackage(packageName)
            changedWhileStopped.clear()
            loadApps()
        }
        // The AirPlay receiver runs in the :airplay process; this starts it if it is enabled, and
        // retries a start that failed earlier (port 7000 taken). A running receiver is left alone.
        // Off the main thread: the setting is read from a file shared with that process.
        val appContext = applicationContext
        scope.launch(Background.work) { ReceiverService.start(appContext) }
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // The screensaver never runs while stopped (onPause ends it), so this is the normal interval.
        backdrop.intervalMs = settings.backdropIntervalMs
        backdrop.start()
        clock.start()
        inputs.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        idle.resume(settings.screensaverDelayMs)
        inputs.resume()
    }

    override fun onPause() {
        resumed = false
        options.hide()
        inputs.hide()
        idle.pause()
        super.onPause()
    }

    override fun onStop() {
        started = false
        inputs.stop()
        clock.stop()
        backdrop.stop()
        super.onStop()
    }

    override fun onDestroy() {
        packages.unregister(this)
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Our process is in the background LRU list: give the photos' and the tiles' GPU memory back.
        // The window's display lists went with its surface when Home stopped, so the tiles' bitmaps are
        // only held by the tiles and the cache; onStart loads them again.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            backdrop.release()
            if (started) TileArtLoader.trimCache() else tiles.releaseArt()
        }
    }

    /**
     * A Home press (or a redirect to home). Like Launcher3: close menus, leave
     * the screensaver and return to the first row; if home was already in
     * front, also move focus to the first tile. Never finishes, never starts
     * anything.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val alreadyHome = hasWindowFocus() && intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT == 0
        options.hide()
        inputs.hide()
        idle.stopNow()
        if (alreadyHome) {
            goToTop()
        } else {
            grid.focusCell(row = 0, column = grid.columnOf(grid.focusedIndex))
            chrome.showRow(0, animate = false)
        }
    }

    // API 28-32 have no OnBackInvokedDispatcher (and AndroidX Activity is not a dependency), so Back
    // is read from the key event there; from API 33 the registered OnBackInvokedCallback handles it.
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        // From API 33 Back arrives through the OnBackInvokedCallback instead.
        if (keyCode == KeyEvent.KEYCODE_BACK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return super.dispatchKeyEvent(event)
        }
        if (idle.onKey(keyCode, event.action == KeyEvent.ACTION_DOWN)) return true
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) handleBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (options.isShowing || inputs.isShowing) {
                options.hide()
                inputs.hide()
            } else {
                val focused = currentFocus as? TileView
                val app = tiles.appFor(focused)
                if (focused != null && app != null) options.show(focused, app)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBackCallback() {
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            handleBack()
        }
    }

    /** Back: close a panel, leave the screensaver, or return to the first tile. Never leaves home. */
    private fun handleBack() {
        when {
            options.isShowing -> options.hide()
            inputs.isShowing -> inputs.hide()
            idle.isScreensaverActive -> idle.stopNow()
            else -> goToTop()
        }
        idle.onUserActivity()
    }

    private fun goToTop() {
        grid.focusTile(0)
        chrome.showRow(0, animate = true)
    }

    private fun routeFocus(
        focused: View?,
        direction: Int,
    ): View? =
        when {
            options.isShowing || inputs.isShowing -> {
                null
            }

            (focused === settingsButton || focused === inputsButton) && direction == View.FOCUS_DOWN -> {
                if (grid.tileCount > 0) grid.tileAt(grid.focusedIndex.coerceAtMost(grid.tileCount - 1)) else null
            }

            focused === settingsButton || focused === inputsButton -> {
                if (direction == View.FOCUS_UP) null else inputs.topBarNeighbour(focused, direction)
            }

            focused is TileView && focused.parent === grid && direction == View.FOCUS_UP -> {
                settingsButton
            }

            else -> {
                null
            }
        }

    private fun loadApps() {
        if (appsJob?.isActive == true) {
            appsStale = true
            return
        }
        appsStale = false
        appsJob =
            scope.launch {
                val apps = withContext(Background.work) { InstalledApps(this@HomeActivity).load() }
                tiles.setApps(apps)
                reportFullyDrawn()
                if (appsStale) loadApps()
            }
    }

    private fun onPackageChanged(packageName: String) {
        // A picker for the Inputs button may have come or gone, or one that failed may start now.
        inputs.onPackagesChanged()
        // While another app is in front (store updates install in the background), only note it:
        // the app list and the tile art are refreshed once, when Home is visible again.
        if (!started) {
            changedWhileStopped += packageName
            return
        }
        tiles.refreshPackage(packageName)
        loadApps()
    }

    private fun onBackdropCoverageChanged(covered: Boolean) {
        // Once a photo covers the screen, the window background under it is pure overdraw.
        if (covered) {
            window.setBackgroundDrawable(null)
        } else {
            window.setBackgroundDrawableResource(ai.aneesiqbal.rozan.ui.R.color.rozan_background)
        }
        // Only a covering backdrop can stand in for the grid scrim.
        if (::chrome.isInitialized) chrome.onBackdropCoverageChanged()
    }

    override fun onFocusedRowChanged(row: Int) {
        chrome.showRow(row, animate = true)
    }

    override fun onScreensaverStarted() {
        options.hide()
        inputs.hide()
        // The backdrop switches to the screensaver's interval once the chrome has faded out (below):
        // a photo change that is due starts then, instead of stacking a second full-screen photo on
        // the frames of the fade.
        chrome.setScreensaver(active = true, animate = true)
    }

    private fun onScreensaverShown() {
        if (idle.isScreensaverActive) backdrop.intervalMs = LauncherSettings.SCREENSAVER_BACKDROP_INTERVAL_MS
    }

    override fun onScreensaverStopped() {
        chrome.setScreensaver(active = false, animate = resumed)
        backdrop.intervalMs = settings.backdropIntervalMs
    }

    /** The title of the backdrop photo on screen, if any (for tests). */
    internal val shownBackdropTitle: String? get() = backdrop.shownImage?.title

    /** Whether the screensaver is showing (for tests). */
    internal val isScreensaverActive: Boolean get() = idle.isScreensaverActive

    /** Whether the backdrop draws itself darkened in place of the grid scrim (for tests). */
    internal val isBackdropTinted: Boolean get() = chrome.backdropTinted

    /** False draws the grid scrim as a view even at rest, as before the backdrop could darken itself (tests). */
    internal fun setBackdropTintAllowed(allowed: Boolean) {
        chrome.tintsBackdrop = allowed
    }

    /** How many times the TV's inputs and pickers have been looked up since the home screen was created (tests). */
    internal val inputsReads: Int get() = inputs.reads

    /** Whether the TV's inputs have been read since the home screen was created (for tests). */
    internal val inputsRead: Boolean get() = inputs.reads > 0

    /** What the Inputs button does now (for tests). */
    internal val inputsMode: InputsButtonMode get() = inputs.mode

    /** Tile labels in grid order (for tests). */
    internal fun tileLabels(): List<String> = tiles.labels()

    /** Apps in grid order (for tests). */
    internal fun apps(): List<AppEntry> = tiles.apps

    private class HandlerScheduler(
        private val handler: Handler,
    ) : IdleTimer.Scheduler {
        override fun schedule(
            task: Runnable,
            delayMs: Long,
        ) {
            handler.postDelayed(task, delayMs)
        }

        override fun cancel(task: Runnable) {
            handler.removeCallbacks(task)
        }
    }

    companion object {
        /**
         * Set on the intent the Home button redirect starts Rozan with
         * (docs/research/home-button-override.md, 6.5). [onNewIntent] treats it
         * like any other `ACTION_MAIN`: back to the first row.
         */
        const val EXTRA_FROM_REDIRECT = "ai.aneesiqbal.rozan.launcher.extra.FROM_REDIRECT"

        private const val SETTINGS_BUTTON_SCALE = 1.15f
    }
}
