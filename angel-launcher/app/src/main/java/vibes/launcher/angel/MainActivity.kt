package vibes.launcher.angel

import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.provider.AlarmClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.view.HapticFeedbackConstants
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import vibes.launcher.angel.data.AppDatabase
import vibes.launcher.angel.data.AppListItem
import vibes.launcher.angel.data.AppModel
import vibes.launcher.angel.data.AppRepository
import vibes.launcher.angel.data.NwsWeatherRepository
import vibes.launcher.angel.data.SettingsManager
import vibes.launcher.angel.data.WeatherData
import vibes.launcher.angel.services.MemoryLockService
import vibes.launcher.angel.services.WidgetUpdateWorker
import vibes.launcher.angel.ui.AppListAdapter
import vibes.launcher.angel.ui.AppOptionsBottomSheet
import vibes.launcher.angel.ui.EditWidgetBottomSheet
import vibes.launcher.angel.ui.HomeScreenMenuBottomSheet
import vibes.launcher.angel.ui.IconPackParser
import vibes.launcher.angel.ui.IconCacheManager
import vibes.launcher.angel.ui.IconExtractor
import vibes.launcher.angel.ui.IconPackResources
import vibes.launcher.angel.ui.IconPickerBottomSheet
import vibes.launcher.angel.ui.ScrubberView
import vibes.launcher.angel.util.LocationHelper
import vibes.launcher.angel.util.WeatherIconHelper
import android.widget.ImageView
import android.widget.LinearLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : FragmentActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var scrubberView: ScrubberView
    private lateinit var appListAdapter: AppListAdapter
    private lateinit var recentAppListAdapter: AppListAdapter
    private var timeTickReceiver: BroadcastReceiver? = null
    private var packageChangeReceiver: BroadcastReceiver? = null
    private lateinit var appRepository: AppRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var iconPackParser: IconPackParser
    private lateinit var iconCacheManager: IconCacheManager

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            refreshWeather(force = true)
        }
    }
    private lateinit var iconExtractor: IconExtractor
    private var currentIconPackResources: IconPackResources? = null
    
    private var isSearchActive = false
    private var isStateOneActive = false
    private lateinit var gestureDetector: GestureDetector
    
    private lateinit var searchDimBackground: View
    private lateinit var appListBackgroundView: View
    private lateinit var searchContainer: View
    private lateinit var searchInput: EditText
    private lateinit var mainContentContainer: View
    private lateinit var searchFab: View
    private lateinit var headerWidget: View
    private var weatherCurrentContainer: View? = null
    private var forecastContainerView: View? = null
    private var weatherIconCurrentView: android.widget.ImageView? = null
    private var weatherTextView: android.widget.TextView? = null
    private var forecastViews: List<Triple<android.widget.TextView?, android.widget.ImageView?, android.widget.TextView?>> = emptyList()
    
    private var favoritesList = listOf<AppListItem>()
    private var fullAlphabetList = listOf<AppListItem>()
    private var allAppsList = listOf<AppModel>()
    private var recentAppsList = listOf<AppListItem>()
    private lateinit var searchSubtitle: TextView
    private lateinit var searchCardView: androidx.cardview.widget.CardView

    private var isEditFavoritesMode = false
    private var isEditWidgetMode = false
    val isEditMode: Boolean get() = isEditFavoritesMode || isEditWidgetMode
    private var lastAppLongPressTimestamp: Long = 0L

    private lateinit var editModeBanner: View
    private lateinit var tvEditModeBannerTitle: TextView
    private lateinit var btnDoneEditMode: View
    private lateinit var widgetAreaContainer: LinearLayout
    private lateinit var userWidgetContainer: FrameLayout
    private var statusBarHeight: Int = 0
    private var navBarHeight: Int = 0

    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private var itemTouchHelper: ItemTouchHelper? = null
    private var isDragging = false

    companion object {
        private const val APPWIDGET_HOST_ID = 2048
        private const val REQUEST_PICK_APPWIDGET = 1001
        private const val REQUEST_CREATE_APPWIDGET = 1002
    }

    private lateinit var clockText: TextView
    private lateinit var amPmText: TextView
    private lateinit var dateText: TextView
    private var cachedDateFormatPattern: String? = null
    private var cachedDateFormatter: java.text.SimpleDateFormat? = null

    private var loadAppsJob: Job? = null
    private var debouncedPackageChangeJob: Job? = null

    // Cached date formatters — created once, reused every minute
    private val timeFormat12 = java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault())
    private val timeFormat24 = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val amPmFormat = java.text.SimpleDateFormat("a", java.util.Locale.getDefault())

    private var pendingAppWidgetId = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID

    private val uninstallLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        iconCacheManager.clearCache()
        loadApps()
    }

    private val pickWidgetLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val appWidgetId = result.data?.getIntExtra(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
            android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: pendingAppWidgetId
        
        if (result.resultCode == RESULT_OK && appWidgetId != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
            configureOrCompleteWidget(appWidgetId)
        } else {
            if (appWidgetId != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
                try {
                    appWidgetHost.deleteAppWidgetId(appWidgetId)
                } catch (e: Exception) {}
            }
        }
        pendingAppWidgetId = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private val createWidgetLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val appWidgetId = result.data?.getIntExtra(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
            android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: settingsManager.userWidgetId
        
        if (result.resultCode == RESULT_OK && appWidgetId != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
            bindAndDisplayWidget(appWidgetId)
        } else {
            if (appWidgetId != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
                try {
                    appWidgetHost.deleteAppWidgetId(appWidgetId)
                } catch (e: Exception) {}
            }
        }
    }

    private fun updateCachedDateFormat() {
        val pattern = settingsManager.dateFormat
        if (pattern != cachedDateFormatPattern || cachedDateFormatter == null) {
            cachedDateFormatPattern = pattern
            cachedDateFormatter = try {
                java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
            } catch (e: Exception) {
                java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vibes.launcher.angel.util.DeviceUtils.applyOrientationLock(this)
        
        // Enable full edge-to-edge layouts drawing behind system status and navigation bars
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        
        window.navigationBarColor = 0x01000000
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        scrubberView = findViewById(R.id.scrubberView)
        searchDimBackground = findViewById(R.id.searchDimBackground)
        appListBackgroundView = findViewById(R.id.appListBackgroundView)
        searchContainer = findViewById(R.id.searchContainer)
        searchInput = findViewById(R.id.searchInput)
        mainContentContainer = findViewById(R.id.mainContentContainer)
        searchFab = findViewById(R.id.searchFab)
        headerWidget = findViewById(R.id.headerWidget)
        searchSubtitle = findViewById(R.id.searchSubtitle)
        searchCardView = findViewById(R.id.searchCardView)

        editModeBanner = findViewById(R.id.editModeBanner)
        tvEditModeBannerTitle = findViewById(R.id.tvEditModeBannerTitle)
        btnDoneEditMode = findViewById(R.id.btnDoneEditMode)
        widgetAreaContainer = findViewById(R.id.widgetAreaContainer)
        userWidgetContainer = findViewById(R.id.userWidgetContainer)

        btnDoneEditMode.setOnClickListener {
            exitAllEditModes()
        }

        mainContentContainer.setOnLongClickListener {
            if (!isStateOneActive && !isSearchActive && !isEditMode && android.os.SystemClock.uptimeMillis() - lastAppLongPressTimestamp > 1000L) {
                showHomeScreenMenu()
                true
            } else false
        }
        
        val dm = resources.displayMetrics
        
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val navInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            val statusInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            
            statusBarHeight = statusInsets.top
            navBarHeight = navInsets.bottom

            recyclerView.clipToPadding = true
            recyclerView.setPadding(
                recyclerView.paddingLeft,
                0,
                recyclerView.paddingRight,
                navInsets.bottom
            )
            
            val rvParams = recyclerView.layoutParams as android.widget.FrameLayout.LayoutParams
            rvParams.bottomMargin = 0
            recyclerView.layoutParams = rvParams
            
            val u = AppListAdapter.getBaseUnit(resources.displayMetrics, 1.0f).toFloat()
            val fabParams = searchFab.layoutParams as android.widget.FrameLayout.LayoutParams
            fabParams.width = (u * 1.2f).toInt()
            fabParams.height = (u * 1.2f).toInt()
            fabParams.marginEnd = (u * 1.2f).toInt()
            fabParams.bottomMargin = (u * 1.5f).toInt() + navInsets.bottom
            searchFab.layoutParams = fabParams

            // Apply actual status bar height as top padding so search bar sits below the status bar
            val topPadding = statusInsets.top + (16 * resources.displayMetrics.density).toInt()
            searchContainer.setPadding(
                searchContainer.paddingLeft,
                topPadding,
                searchContainer.paddingRight,
                searchContainer.paddingBottom
            )

            // Ensure Edit Mode Banner is placed safely below the notification bar
            val bannerParams = editModeBanner.layoutParams as? android.widget.LinearLayout.LayoutParams
            if (bannerParams != null) {
                bannerParams.topMargin = statusInsets.top + (8 * resources.displayMetrics.density).toInt()
                editModeBanner.layoutParams = bannerParams
            }
            
            applyWidgetLayoutSettings()
            
            insets
        }


        val database = AppDatabase.getDatabase(this)
        appRepository = AppRepository(applicationContext, database.appDao())
        settingsManager = SettingsManager(this)
        iconPackParser = IconPackParser()
        iconCacheManager = IconCacheManager(this)
        iconExtractor = IconExtractor()

        updateCachedDateFormat()
        setupHeaderWidget()

        startForegroundService(Intent(this, MemoryLockService::class.java))

        val onAppClick: (AppModel) -> Unit = { app ->
            if (app.packageName == "vibes.launcher.angel.settings") {
                startActivity(Intent(this, SettingsActivity::class.java))
            } else {
                app.launchIntent?.let { intent ->
                    val options = ActivityOptions.makeCustomAnimation(this, R.anim.launch_enter, R.anim.launch_exit)
                    startActivity(intent, options.toBundle())
                }
            }
        }
        
        val onAppLongClick: (AppModel, View) -> Unit = { app, view ->
            lastAppLongPressTimestamp = android.os.SystemClock.uptimeMillis()
            view.parent?.requestDisallowInterceptTouchEvent(true)
            showAppOptions(app)
        }

        appListAdapter = AppListAdapter(emptyList(), lifecycleScope, iconCacheManager, iconExtractor, null, null, settingsManager, onAppClick, onAppLongClick)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.itemAnimator = null
        recyclerView.adapter = appListAdapter
        setupFavoritesDragAndDrop()
        
        searchFab.setOnClickListener { showSearch() }

        scrubberView.onLetterSelected = { letter ->
            if (letter == "☆") {
                revertToStateZero()
            } else {
                if (!isStateOneActive) {
                    activateStateOne()
                }
                val index = when (letter) {
                    "○" -> appListAdapter.items.size - 1
                    else -> appListAdapter.items.indexOfFirst { it is AppListItem.Header && it.title == letter }
                }
                if (index != -1) {
                    (recyclerView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
                }
            }
        }
        
        scrubberView.onInteractionStarted = {
            if (searchFab.visibility == View.VISIBLE) {
                searchFab.animate().alpha(0f).setDuration(100).withEndAction {
                    searchFab.visibility = View.GONE
                }.start()
            }
        }
        
        scrubberView.onInteractionEnded = {
            if (isStateOneActive && !isSearchActive) {
                searchFab.visibility = View.VISIBLE
                searchFab.animate().alpha(1f).setDuration(150).start()
            }
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // Revert to State 0 via scrubber ☆ or swipe-down gesture, not just scrolling to the top item.
            }
        })

        // Search UI Setup
        val recentAppsRecyclerView = findViewById<RecyclerView>(R.id.recentAppsRecyclerView)
        recentAppListAdapter = AppListAdapter(emptyList(), lifecycleScope, iconCacheManager, iconExtractor, null, null, settingsManager, onAppClick, onAppLongClick)
        recentAppsRecyclerView.layoutManager = LinearLayoutManager(this)
        recentAppsRecyclerView.itemAnimator = null
        recentAppsRecyclerView.adapter = recentAppListAdapter
        
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                if (query.isEmpty()) {
                    searchSubtitle.text = "Recently installed"
                    recentAppListAdapter.updateItems(recentAppsList)
                } else {
                    searchSubtitle.text = "Search results"
                    val filtered = allAppsList.filter { it.displayLabel.lowercase().contains(query) }
                    recentAppListAdapter.updateItems(filtered.map { AppListItem.App(it) })
                }
            }
        })

        setupSearchGestures()

        initAppWidgetHost()

        loadApps()
    }

    override fun onStart() {
        super.onStart()
        if (::appWidgetHost.isInitialized) {
            try {
                appWidgetHost.startListening()
            } catch (e: Exception) {}
        }
        if (timeTickReceiver == null) {
            timeTickReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateClockAndDate()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(WidgetUpdateWorker.ACTION_WEATHER_UPDATED)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                timeTickReceiver!!,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        if (packageChangeReceiver == null) {
            packageChangeReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val pkgName = intent?.data?.schemeSpecificPart
                    if (pkgName != null && intent?.action == Intent.ACTION_PACKAGE_REMOVED) {
                        lifecycleScope.launch {
                            appRepository.deleteApp(pkgName)
                        }
                    }
                    debouncedPackageChangeJob?.cancel()
                    debouncedPackageChangeJob = lifecycleScope.launch {
                        delay(300)
                        iconCacheManager.clearCache()
                        loadApps()
                    }
                }
            }
            val pkgFilter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(packageChangeReceiver, pkgFilter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(packageChangeReceiver, pkgFilter)
            }
        }
        updateClockAndDate()
    }

    override fun onStop() {
        super.onStop()
        if (::appWidgetHost.isInitialized) {
            try {
                appWidgetHost.stopListening()
            } catch (e: Exception) {}
        }
        timeTickReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // ignore
            }
            timeTickReceiver = null
        }
        packageChangeReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) {}
            packageChangeReceiver = null
        }
    }

    override fun onDestroy() {
        if (::settingsManager.isInitialized) {
            settingsManager.destroy()
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        applyThemeColors()
        updateCachedDateFormat()
        updateClockAndDate()
        refreshWeather(force = false)
        loadApps()
        
        lifecycleScope.launch {
            val currentIconPackPackage = settingsManager.iconPackPackage
            val iconPackChanged = currentIconPackResources == null || appListAdapter.iconPackPackage != currentIconPackPackage
            val currentIconShape = settingsManager.iconShape
            val shapeChanged = appListAdapter.cachedIconShape != currentIconShape
            val currentGlobalScale = settingsManager.globalScale
            val currentIconScale = settingsManager.iconScale
            val currentTextScale = settingsManager.textScale
            val scaleChanged = appListAdapter.cachedGlobalScale != currentGlobalScale ||
                               appListAdapter.cachedIconScale != currentIconScale ||
                               appListAdapter.cachedTextScale != currentTextScale

            if (iconPackChanged || shapeChanged) {
                if (iconPackChanged) {
                    currentIconPackResources = iconPackParser.parseAppFilter(this@MainActivity, currentIconPackPackage)
                }
                iconCacheManager.clearCache()
            }
            
            appListAdapter.iconPackPackage = currentIconPackPackage
            appListAdapter.iconPackResources = currentIconPackResources
            recentAppListAdapter.iconPackPackage = currentIconPackPackage
            recentAppListAdapter.iconPackResources = currentIconPackResources

            // Refresh cached settings values (scale, colors, shape) in adapters
            appListAdapter.refreshSettings()
            recentAppListAdapter.refreshSettings()

            if (iconPackChanged || shapeChanged || scaleChanged) {
                // Full adapter swap when pack, shape, or scale changed (forces ViewHolder recreation for new icon sizes/shapes)
                recyclerView.adapter = null
                recyclerView.recycledViewPool.clear()
                recyclerView.adapter = appListAdapter
                appListAdapter.notifyDataSetChanged()

                val recentAppsRecyclerView = findViewById<RecyclerView>(R.id.recentAppsRecyclerView)
                recentAppsRecyclerView.adapter = null
                recentAppsRecyclerView.recycledViewPool.clear()
                recentAppsRecyclerView.adapter = recentAppListAdapter
                recentAppListAdapter.notifyDataSetChanged()
            } else {
                // Just notify so colors/text are refreshed without full rebind
                appListAdapter.notifyDataSetChanged()
                recentAppListAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun setupSearchGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (!isStateOneActive && !isSearchActive && !isEditMode) {
                    if (android.os.SystemClock.uptimeMillis() - lastAppLongPressTimestamp < 1000L) {
                        return
                    }

                    // Check if touch landed on an item in the apps list
                    val location = IntArray(2)
                    recyclerView.getLocationOnScreen(location)
                    val rx = e.rawX - location[0]
                    val ry = e.rawY - location[1]
                    if (rx >= 0 && rx <= recyclerView.width && ry >= 0 && ry <= recyclerView.height) {
                        val childView = recyclerView.findChildViewUnder(rx, ry)
                        if (childView != null) {
                            return
                        }
                    }

                    // Check if touch landed on user widget
                    if (userWidgetContainer.visibility == View.VISIBLE) {
                        val rect = Rect()
                        userWidgetContainer.getGlobalVisibleRect(rect)
                        if (rect.contains(e.rawX.toInt(), e.rawY.toInt())) {
                            return
                        }
                    }

                    // Check if touch landed on header weather/clock widget
                    if (headerWidget.visibility == View.VISIBLE) {
                        val rect = Rect()
                        headerWidget.getGlobalVisibleRect(rect)
                        if (rect.contains(e.rawX.toInt(), e.rawY.toInt())) {
                            return
                        }
                    }

                    // Check if touch landed on search FAB
                    if (searchFab.visibility == View.VISIBLE) {
                        val rect = Rect()
                        searchFab.getGlobalVisibleRect(rect)
                        if (rect.contains(e.rawX.toInt(), e.rawY.toInt())) {
                            return
                        }
                    }

                    showHomeScreenMenu()
                }
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                if (scrubberView.isActive) return false

                val scrubberThreshold = resources.displayMetrics.widthPixels - (60 * resources.displayMetrics.density)
                if (e1.x >= scrubberThreshold) return false

                val deltaY = e2.y - e1.y
                val deltaX = e2.x - e1.x

                if (Math.abs(deltaY) > Math.abs(deltaX) * 1.1f && Math.abs(velocityY) > 150) {
                    if (deltaY < -80) {
                        // Swipe UP: on State 0 (favorites screen), launch search!
                        if (!isSearchActive && !isStateOneActive && !isEditMode) {
                            showSearch()
                            return true
                        }
                    } else if (deltaY > 80) {
                        // Swipe DOWN
                        if (isSearchActive) {
                            hideSearch()
                            return true
                        } else if (isEditMode) {
                            exitAllEditModes()
                            return true
                        } else if (isStateOneActive && !recyclerView.canScrollVertically(-1)) {
                            revertToStateZero()
                            return true
                        }
                    }
                }
                return false
            }
        })

        searchDimBackground.setOnClickListener { hideSearch() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSearchActive) {
                    hideSearch()
                } else if (isEditMode) {
                    exitAllEditModes()
                } else if (isStateOneActive) {
                    revertToStateZero()
                } else {
                    // Do nothing, we are launcher home
                }
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isEditMode) {
            return super.dispatchTouchEvent(ev)
        }
        if (::gestureDetector.isInitialized && gestureDetector.onTouchEvent(ev)) {
            val cancelEvent = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancelEvent)
            cancelEvent.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.categories?.contains(Intent.CATEGORY_HOME) == true) {
            if (isSearchActive) hideSearch()
            if (isEditMode) exitAllEditModes()
            if (isStateOneActive) revertToStateZero()
        }
    }

    private fun isDarkThemeActive(): Boolean {
        return when (settingsManager.themeMode) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun getAppListTintColor(): Int {
        val baseColor = if (settingsManager.appListMonetEnabled) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (isDarkThemeActive()) {
                    androidx.core.content.ContextCompat.getColor(this, android.R.color.system_neutral1_900)
                } else {
                    androidx.core.content.ContextCompat.getColor(this, android.R.color.system_neutral1_100)
                }
            } else {
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
                typedValue.data
            }
        } else {
            settingsManager.appListCustomColor
        }
        val alpha = (settingsManager.appListTintOpacity.coerceIn(0f, 1f) * 255).toInt()
        return androidx.core.graphics.ColorUtils.setAlphaComponent(baseColor, alpha)
    }

    private fun applyThemeColors() {
        val isDark = isDarkThemeActive()
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark

        window.navigationBarColor = 0x01000000

        scrubberView.setTextColor(settingsManager.scrubberTextColor, settingsManager.scrubberTextOpacity)
        
        val dimColor = androidx.core.graphics.ColorUtils.setAlphaComponent(
            settingsManager.searchDimColor,
            (settingsManager.searchDimOpacity.coerceIn(0f, 1f) * 255).toInt()
        )
        searchDimBackground.setBackgroundColor(dimColor)
        
        val searchPillColor = androidx.core.graphics.ColorUtils.setAlphaComponent(
            settingsManager.searchBarColor,
            (settingsManager.searchBarOpacity.coerceIn(0f, 1f) * 255).toInt()
        )

        if (isDark) {
            searchCardView.cardElevation = 0f
            searchCardView.setCardBackgroundColor(searchPillColor)
        } else {
            // Elevated solid white search card in light mode
            searchCardView.cardElevation = 2f * resources.displayMetrics.density
            searchCardView.setCardBackgroundColor(android.graphics.Color.WHITE)
        }
        
        val fabColor = androidx.core.graphics.ColorUtils.setAlphaComponent(
            settingsManager.searchFabColor,
            (settingsManager.searchFabOpacity.coerceIn(0f, 1f) * 255).toInt()
        )
        searchFab.backgroundTintList = android.content.res.ColorStateList.valueOf(fabColor)

        // Dynamic Search Input & Subtitle Text Colors
        val searchInput = findViewById<android.widget.EditText>(R.id.searchInput)
        if (searchInput != null) {
            searchInput.setTextColor(settingsManager.appTextColor)
            searchInput.setHintTextColor(
                androidx.core.graphics.ColorUtils.setAlphaComponent(
                    settingsManager.appTextColor,
                    (settingsManager.appTextOpacity.coerceIn(0f, 1f) * 0.5f * 255).toInt()
                )
            )
        }
        val searchSubtitle = findViewById<android.widget.TextView>(R.id.searchSubtitle)
        if (searchSubtitle != null) {
            searchSubtitle.setTextColor(
                androidx.core.graphics.ColorUtils.setAlphaComponent(
                    settingsManager.appTextColor,
                    (settingsManager.appTextOpacity.coerceIn(0f, 1f) * 0.5f * 255).toInt()
                )
            )
        }

        // Clock & Date Text Color
        val clockColor = androidx.core.graphics.ColorUtils.setAlphaComponent(
            settingsManager.clockTextColor,
            (settingsManager.clockTextOpacity.coerceIn(0f, 1f) * 255).toInt()
        )
        clockText.setTextColor(clockColor)
        amPmText.setTextColor(clockColor)
        dateText.setTextColor(clockColor)

        if (isDark) {
            clockText.setShadowLayer(4f, 1f, 2f, 0x80000000.toInt())
            amPmText.setShadowLayer(4f, 1f, 2f, 0x80000000.toInt())
            dateText.setShadowLayer(4f, 1f, 2f, 0x80000000.toInt())
        } else {
            // Subtle white blur (shadow) behind dark text in Light Mode
            clockText.setShadowLayer(3f, 0f, 0f, 0xFFFFFFFF.toInt())
            amPmText.setShadowLayer(3f, 0f, 0f, 0xFFFFFFFF.toInt())
            dateText.setShadowLayer(3f, 0f, 0f, 0xFFFFFFFF.toInt())
        }

        // Weather & Forecast Text Color
        val weatherColor = androidx.core.graphics.ColorUtils.setAlphaComponent(
            settingsManager.weatherTextColor,
            (settingsManager.weatherTextOpacity.coerceIn(0f, 1f) * 255).toInt()
        )
        weatherTextView?.setTextColor(weatherColor)
        
        if (isDark) {
            weatherTextView?.setShadowLayer(4f, 1f, 2f, 0x80000000.toInt())
        } else {
            weatherTextView?.setShadowLayer(3f, 0f, 0f, 0xFFFFFFFF.toInt())
        }

        for (triple in forecastViews) {
            triple.first?.setTextColor(weatherColor)
            triple.third?.setTextColor(weatherColor)
            if (isDark) {
                triple.first?.setShadowLayer(4f, 1f, 2f, 0x80000000.toInt())
                triple.third?.setShadowLayer(4f, 1f, 2f, 0x80000000.toInt())
            } else {
                triple.first?.setShadowLayer(3f, 0f, 0f, 0xFFFFFFFF.toInt())
                triple.third?.setShadowLayer(3f, 0f, 0f, 0xFFFFFFFF.toInt())
            }
        }

        // Edit Mode Banner Color
        val bannerColor = androidx.core.graphics.ColorUtils.setAlphaComponent(
            settingsManager.editModeBannerColor,
            (settingsManager.editModeBannerOpacity.coerceIn(0f, 1f) * 255).toInt()
        )
        editModeBanner.setBackgroundColor(bannerColor)
        
        // Refresh adapters settings & colors
        appListAdapter.refreshSettings()
        recentAppListAdapter.refreshSettings()
        
        if (isStateOneActive) {
            val tintColor = getAppListTintColor()
            appListBackgroundView.setBackgroundColor(tintColor)
            appListBackgroundView.visibility = View.VISIBLE
            recyclerView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            scrubberView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    private fun activateStateOne() {
        if (isStateOneActive) return
        exitAllEditModes()
        isStateOneActive = true
        widgetAreaContainer.visibility = View.GONE
        headerWidget.visibility = View.GONE
        headerWidget.alpha = 0f
        userWidgetContainer.visibility = View.GONE
        
        if (!scrubberView.isActive) {
            searchFab.visibility = View.VISIBLE
            searchFab.alpha = 1f
        } else {
            searchFab.visibility = View.GONE
            searchFab.alpha = 0f
        }
        
        val tintColor = getAppListTintColor()
        appListBackgroundView.setBackgroundColor(tintColor)
        appListBackgroundView.visibility = View.VISIBLE
        recyclerView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        scrubberView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        window.navigationBarColor = 0x01000000
        
        appListAdapter.setItemsImmediate(fullAlphabetList)
    }

    private fun revertToStateZero() {
        if (!isStateOneActive) return
        isStateOneActive = false
        widgetAreaContainer.visibility = View.VISIBLE
        if (settingsManager.showWidget) {
            headerWidget.visibility = View.VISIBLE
            headerWidget.alpha = 1f
        } else {
            headerWidget.visibility = View.GONE
        }

        if (settingsManager.userWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            userWidgetContainer.visibility = View.VISIBLE
        } else {
            userWidgetContainer.visibility = View.GONE
        }
        
        searchFab.visibility = View.GONE
        searchFab.alpha = 0f
        
        appListBackgroundView.visibility = View.GONE
        recyclerView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        scrubberView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        window.navigationBarColor = 0x01000000
        
        applyWidgetLayoutSettings()
        appListAdapter.setItemsImmediate(favoritesList)
        recyclerView.scrollToPosition(0)
    }

    private fun showSearch() {
        exitAllEditModes()
        isSearchActive = true
        searchDimBackground.visibility = View.VISIBLE
        searchContainer.visibility = View.VISIBLE
        
        mainContentContainer.animate().alpha(0f).setDuration(150).start()
        searchDimBackground.animate().alpha(1f).setDuration(150).start()
        searchContainer.animate().alpha(1f).setDuration(150).start()
        
        if (searchFab.visibility == View.VISIBLE) {
            searchFab.animate().alpha(0f).setDuration(100).start()
        }
        
        searchInput.post {
            searchInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideSearch() {
        isSearchActive = false
        
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        
        mainContentContainer.animate().alpha(1f).setDuration(150).start()
        searchDimBackground.animate().alpha(0f).setDuration(150).withEndAction {
            searchDimBackground.visibility = View.GONE
        }.start()
        searchContainer.animate().alpha(0f).setDuration(150).withEndAction {
            searchContainer.visibility = View.GONE
        }.start()
        
        if (isStateOneActive) {
            searchFab.animate().alpha(1f).setDuration(150).start()
        }
        
        searchInput.setText("")
    }

    private fun setupHeaderWidget() {
        val weatherWidgetContainer = findViewById<View>(R.id.weatherWidgetContainer)
        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)
        amPmText = findViewById(R.id.amPmText)
        
        weatherWidgetContainer?.post {
            val dm = resources.displayMetrics
            val sidePadding = dm.widthPixels / 8
            val topPadding = dm.heightPixels / 13
            val bottomPadding = (16 * dm.density).toInt()
            weatherWidgetContainer.setPadding(sidePadding, topPadding, sidePadding, bottomPadding)
        }
        
        weatherCurrentContainer = findViewById(R.id.weatherCurrentContainer)
        forecastContainerView = findViewById(R.id.forecastContainer)
        weatherIconCurrentView = findViewById(R.id.weatherIconCurrent)
        weatherTextView = findViewById(R.id.weatherText)
        forecastViews = listOf(
            Triple(findViewById(R.id.forecastDay1Name), findViewById(R.id.forecastDay1Icon), findViewById(R.id.forecastDay1Temp)),
            Triple(findViewById(R.id.forecastDay2Name), findViewById(R.id.forecastDay2Icon), findViewById(R.id.forecastDay2Temp)),
            Triple(findViewById(R.id.forecastDay3Name), findViewById(R.id.forecastDay3Icon), findViewById(R.id.forecastDay3Temp)),
            Triple(findViewById(R.id.forecastDay4Name), findViewById(R.id.forecastDay4Icon), findViewById(R.id.forecastDay4Temp)),
            Triple(findViewById(R.id.forecastDay5Name), findViewById(R.id.forecastDay5Icon), findViewById(R.id.forecastDay5Temp))
        )
        
        clockText.setOnClickListener {
            val intents = listOf(
                Intent(AlarmClock.ACTION_SHOW_ALARMS),
                Intent(AlarmClock.ACTION_SET_ALARM)
            )
            var launched = false
            for (intent in intents) {
                if (intent.resolveActivity(packageManager) != null) {
                    try {
                        startActivity(intent)
                        launched = true
                        break
                    } catch (e: Exception) {}
                }
            }
            if (!launched) {
                val clockPackages = listOf(
                    "com.google.android.deskclock",
                    "com.android.deskclock",
                    "com.sec.android.app.clockpackage",
                    "com.transsion.deskclock"
                )
                for (pkg in clockPackages) {
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        try {
                            startActivity(launchIntent)
                            break
                        } catch (e: Exception) {}
                    }
                }
            }
        }
        
        dateText.setOnClickListener {
            val builder = android.net.Uri.parse("content://com.android.calendar/time/").buildUpon()
            android.content.ContentUris.appendId(builder, System.currentTimeMillis())
            val intent = Intent(Intent.ACTION_VIEW).setData(builder.build())
            var launched = false
            if (intent.resolveActivity(packageManager) != null) {
                try {
                    startActivity(intent)
                    launched = true
                } catch (e: Exception) {}
            }
            if (!launched) {
                val calendarPackages = listOf(
                    "com.google.android.calendar",
                    "com.android.calendar",
                    "com.sec.android.app.calendar",
                    "com.transsion.calendar"
                )
                for (pkg in calendarPackages) {
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        try {
                            startActivity(launchIntent)
                            break
                        } catch (e: Exception) {}
                    }
                }
            }
        }

        val onWeatherClick: (View) -> Unit = {
            if (settingsManager.weatherUseDeviceLocation && !LocationHelper.hasLocationPermission(this)) {
                locationPermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            } else {
                refreshWeather(force = true)
            }
            launchWeatherApp()
        }
        weatherCurrentContainer?.setOnClickListener(onWeatherClick)
        forecastContainerView?.setOnClickListener(onWeatherClick)

        updateClockAndDate()
        refreshWeather(force = false)
        
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(2, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "WeatherUpdate",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun updateClockAndDate() {
        if (!settingsManager.showWidget) {
            headerWidget.visibility = View.GONE
            return
        } else if (!isStateOneActive) {
            headerWidget.visibility = View.VISIBLE
            headerWidget.alpha = 1f
        }

        val now = java.util.Date()
        
        val is24Hour = when (settingsManager.clock24Hour) {
            1 -> false
            2 -> true
            else -> android.text.format.DateFormat.is24HourFormat(this)
        }

        val timeFormat = if (is24Hour) timeFormat24 else timeFormat12
        clockText.text = timeFormat.format(now)

        if (!is24Hour && settingsManager.showAmPm) {
            amPmText.text = amPmFormat.format(now)
            amPmText.visibility = View.VISIBLE
        } else {
            amPmText.visibility = View.GONE
        }

        if (settingsManager.showDate) {
            dateText.text = cachedDateFormatter?.format(now)
            dateText.visibility = View.VISIBLE
        } else {
            dateText.visibility = View.GONE
        }

        updateWeatherUI()
    }

    private fun updateWeatherUI() {
        val weatherCurrent = weatherCurrentContainer ?: return
        val forecastContainer = forecastContainerView ?: return
        val weatherIconCurrent = weatherIconCurrentView ?: return
        val weatherText = weatherTextView ?: return

        if (!settingsManager.showWeather) {
            weatherCurrent.visibility = View.GONE
            forecastContainer.visibility = View.GONE
            return
        }

        weatherCurrent.visibility = View.VISIBLE
        forecastContainer.visibility = if (settingsManager.show5DayForecast) View.VISIBLE else View.GONE

        val cached = settingsManager.getCachedWeatherData()
        if (cached != null) {
            val packPkg = settingsManager.weatherIconPackPackage
            val currentDrawable = WeatherIconHelper.loadWeatherIconFromPack(this, cached.currentShortForecast, true, packPkg)
            if (currentDrawable != null) {
                weatherIconCurrent.setImageDrawable(currentDrawable)
            } else {
                val iconRes = WeatherIconHelper.getWeatherIconRes(cached.currentShortForecast)
                weatherIconCurrent.setImageResource(iconRes)
            }
            weatherText.text = "${cached.cityName} ${cached.currentTemp}°F"

            for (i in forecastViews.indices) {
                val (nameView, iconView, tempView) = forecastViews[i]
                if (i < cached.dailyForecasts.size) {
                    val d = cached.dailyForecasts[i]
                    nameView?.text = d.dayName
                    val dayDrawable = WeatherIconHelper.loadWeatherIconFromPack(this, d.shortForecast, true, packPkg)
                    if (dayDrawable != null) {
                        iconView?.setImageDrawable(dayDrawable)
                    } else {
                        val dayIconRes = WeatherIconHelper.getWeatherIconRes(d.shortForecast)
                        iconView?.setImageResource(dayIconRes)
                    }
                    tempView?.text = "${d.highTemp}°/${d.lowTemp}°"
                }
            }
        } else {
            // Show loading placeholder on first launch when no cache exists yet
            weatherIconCurrent.setImageResource(R.drawable.ic_weather_temp)
            weatherText.text = "Loading weather…"
            forecastContainer.visibility = View.GONE
        }
    }

    private fun refreshWeather(force: Boolean) {
        if (!settingsManager.showWeather) return
        val cached = settingsManager.getCachedWeatherData()
        val now = System.currentTimeMillis()
        if (!force && cached != null && (now - cached.timestamp < 3600_000L)) {
            return
        }

        if (settingsManager.weatherUseDeviceLocation && !LocationHelper.hasLocationPermission(this)) {
            locationPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var lat = settingsManager.weatherCustomLat
            var lon = settingsManager.weatherCustomLon
            if (settingsManager.weatherUseDeviceLocation) {
                val devLoc = LocationHelper.getDeviceLocation(this@MainActivity)
                if (devLoc != null) {
                    lat = devLoc.latitude
                    lon = devLoc.longitude
                }
            }
            
            if (lat == 0.0 && lon == 0.0) {
                withContext(Dispatchers.Main) {
                    if (settingsManager.getCachedWeatherData() == null) {
                        weatherIconCurrentView?.setImageResource(R.drawable.ic_weather_location)
                        weatherTextView?.text = "Tap to set location"
                        forecastContainerView?.visibility = View.GONE
                    }
                }
                return@launch
            }

            android.util.Log.d("AngelWeather", "Fetching weather for lat=$lat, lon=$lon, useDeviceLoc=${settingsManager.weatherUseDeviceLocation}")
            val repository = NwsWeatherRepository(this@MainActivity)
            val result = repository.getWeatherData(lat, lon)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val data = result.getOrThrow()
                    android.util.Log.d("AngelWeather", "Weather fetch SUCCESS: ${data.cityName} ${data.currentTemp}F")
                    settingsManager.saveCachedWeatherData(data)
                    updateWeatherUI()
                } else {
                    val ex = result.exceptionOrNull()
                    android.util.Log.e("AngelWeather", "Weather fetch FAILED: ${ex?.message}", ex)
                    if (settingsManager.getCachedWeatherData() == null) {
                        weatherIconCurrentView?.setImageResource(R.drawable.ic_weather_alert)
                        weatherTextView?.text = "Tap to retry weather"
                        forecastContainerView?.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun launchWeatherApp() {
        val weatherPackages = listOf(
            "com.google.android.apps.weather",
            "com.weather.Weather",
            "com.accuweather.android",
            "com.sec.android.daemonapp",
            "com.samsung.android.weather",
            "com.asus.weather",
            "com.coloros.weather",
            "com.coloros.weather2",
            "com.miui.weather2",
            "com.transsion.weather",
            "com.huawei.android.totemweather",
            "com.htc.Weather",
            "com.motorola.timeweatherwidget",
            "com.lenovo.weather",
            "com.lge.sizechangable.weather",
            "org.breezyweather",
            "cz.martykan.forecastie",
            "de.bendix.weather"
        )
        for (pkg in weatherPackages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                try {
                    startActivity(launchIntent)
                    return
                } catch (e: Exception) {}
            }
        }
        
        // Dynamic search in installed launcher apps
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
            for (info in resolveInfos) {
                val pkg = info.activityInfo.packageName
                val label = info.loadLabel(packageManager).toString()
                if ((pkg.contains("weather", ignoreCase = true) || label.contains("weather", ignoreCase = true)) && pkg != packageName) {
                    val intent = packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        startActivity(intent)
                        return
                    }
                }
            }
        } catch (e: Exception) {}

        // Fallback: Web search for weather / current city
        try {
            val cached = settingsManager.getCachedWeatherData()
            val query = if (cached != null && cached.cityName.isNotBlank()) "weather ${cached.cityName}" else "weather"
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"))
            startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun showAppOptions(app: AppModel) {
        lastAppLongPressTimestamp = android.os.SystemClock.uptimeMillis()
        if (app.packageName == "vibes.launcher.angel.settings") return
        (supportFragmentManager.findFragmentByTag("HomeScreenMenuBottomSheet") as? androidx.fragment.app.DialogFragment)?.dismissAllowingStateLoss()
        val optionsSheet = AppOptionsBottomSheet(
            app = app,
            iconPackPackage = settingsManager.iconPackPackage,
            iconPackResources = currentIconPackResources,
            iconCacheManager = iconCacheManager,
            onToggleFavorite = {
                lifecycleScope.launch {
                    val updatedApp = app.copy(isPinned = !app.isPinned)
                    appRepository.updateApp(updatedApp)
                    loadApps()
                }
            },
            onRenameApp = { customName ->
                lifecycleScope.launch {
                    val updatedApp = app.copy(customLabel = customName)
                    appRepository.updateApp(updatedApp)
                    loadApps()
                }
            },
            onOpenIconPicker = {
                val bottomSheet = IconPickerBottomSheet(
                    app = app,
                    iconPackPackage = settingsManager.iconPackPackage,
                    iconPackResources = currentIconPackResources
                ) { customPack, customDrawable ->
                    lifecycleScope.launch {
                        val updatedApp = app.copy(
                            customIconPack = customPack,
                            customIconDrawable = customDrawable,
                            useDefaultIcon = (customPack == "system" || customDrawable == "system")
                        )
                        appRepository.updateApp(updatedApp)
                        iconCacheManager.clearCache()
                        loadApps()
                    }
                }
                bottomSheet.show(supportFragmentManager, "IconPickerBottomSheet")
            },
            onToggleHide = {
                lifecycleScope.launch {
                    val updatedApp = app.copy(isHidden = !app.isHidden)
                    appRepository.updateApp(updatedApp)
                    loadApps()
                }
            },
            onAppInfo = {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", app.packageName, null)
                }
                startActivity(intent)
            },
            onUninstall = {
                uninstallApp(app.packageName)
            }
        )
        optionsSheet.show(supportFragmentManager, "AppOptionsBottomSheet")
    }

    private fun uninstallApp(packageName: String) {
        if (packageName == "vibes.launcher.angel.settings" || packageName == "vibes.launcher.angel.spacer" || packageName == applicationContext.packageName) {
            Toast.makeText(this, "Cannot uninstall launcher component", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:$packageName")
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        try {
            uninstallLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to uninstall app: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFavoritesDragAndDrop() {
        val itemTouchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            private var hasMoved = false

            override fun isLongPressDragEnabled(): Boolean {
                // In Edit Favorites Mode, allow long press dragging on items (or dragging via dragHandle)
                return isEditFavoritesMode && !isStateOneActive && !isSearchActive
            }

            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION) {
                    hasMoved = true
                    return appListAdapter.onItemMove(fromPos, toPos)
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    isDragging = true
                    hasMoved = false
                    viewHolder?.itemView?.animate()?.scaleX(1.04f)?.scaleY(1.04f)?.alpha(0.88f)?.setDuration(120)?.start()
                    viewHolder?.itemView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(120).start()
                if (isDragging) {
                    isDragging = false
                    if (hasMoved) {
                        // Persist the newly arranged favorites order
                        favoritesList = appListAdapter.items.toList()
                        val newOrder = favoritesList.filterIsInstance<AppListItem.App>().map { it.appModel.packageName }
                        settingsManager.favoriteOrder = newOrder
                    }
                }
            }
        }

        itemTouchHelper = ItemTouchHelper(itemTouchCallback).apply {
            attachToRecyclerView(recyclerView)
        }

        appListAdapter.onStartDragListener = { viewHolder ->
            itemTouchHelper?.startDrag(viewHolder)
        }
    }

    private fun applyWidgetLayoutSettings() {
        val density = resources.displayMetrics.density
        // Custom Spacing
        val topPadPx = (settingsManager.widgetTopPaddingDp * density).toInt()
        val bottomPadPx = (settingsManager.widgetBottomPaddingDp * density).toInt()
        
        val areaParams = widgetAreaContainer.layoutParams as? android.widget.LinearLayout.LayoutParams
        if (areaParams != null) {
            val statusOffset = if (statusBarHeight > 0) statusBarHeight else (28 * density).toInt()
            val baseTopMargin = if (isEditMode) 0 else statusOffset + (8 * density).toInt()
            areaParams.topMargin = baseTopMargin + topPadPx
            areaParams.bottomMargin = (4 * density).toInt() + bottomPadPx
            widgetAreaContainer.layoutParams = areaParams
        }
    }

    private fun enterEditFavoritesMode() {
        if (isStateOneActive || isSearchActive) return
        isEditFavoritesMode = true
        isEditWidgetMode = false
        scrubberView.visibility = View.GONE
        scrubberView.isEnabled = false
        appListAdapter.isEditMode = true
        editModeBanner.visibility = View.VISIBLE
        tvEditModeBannerTitle.text = "Edit Home Screen"
        loadUserWidget()
    }

    private fun enterEditWidgetCanvasMode() {
        if (isStateOneActive || isSearchActive) return
        isEditWidgetMode = true
        isEditFavoritesMode = false
        scrubberView.visibility = View.GONE
        scrubberView.isEnabled = false
        appListAdapter.isEditMode = false
        editModeBanner.visibility = View.VISIBLE
        tvEditModeBannerTitle.text = "Edit Widgets & Bounding Boxes"
        loadUserWidget()
    }

    private fun exitAllEditModes() {
        if (!isEditMode) return
        isEditFavoritesMode = false
        isEditWidgetMode = false
        scrubberView.visibility = View.VISIBLE
        scrubberView.isEnabled = true
        appListAdapter.isEditMode = false
        editModeBanner.visibility = View.GONE
        loadUserWidget()
    }

    private fun showHomeScreenMenu() {
        if (android.os.SystemClock.uptimeMillis() - lastAppLongPressTimestamp < 1000L) return
        if (supportFragmentManager.findFragmentByTag("AppOptionsBottomSheet")?.isAdded == true) return
        if (supportFragmentManager.findFragmentByTag("HomeScreenMenuBottomSheet")?.isAdded == true) return
        if (supportFragmentManager.findFragmentByTag("EditWidgetBottomSheet")?.isAdded == true) return
        if (supportFragmentManager.findFragmentByTag("EditLayoutSpacingBottomSheet")?.isAdded == true) return

        val sheet = HomeScreenMenuBottomSheet(
            onEditHomeScreen = {
                enterEditFavoritesMode()
            },
            onEditWidget = {
                showEditWidgetBottomSheet()
            },
            onEditLayout = {
                showEditLayoutSpacingBottomSheet()
            },
            onOpenSettings = {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
            onChangeWallpaper = {
                try {
                    val intent = Intent(Intent.ACTION_SET_WALLPAPER)
                    startActivity(Intent.createChooser(intent, "Select Wallpaper"))
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open wallpaper chooser", Toast.LENGTH_SHORT).show()
                }
            }
        )
        sheet.show(supportFragmentManager, "HomeScreenMenuBottomSheet")
    }

    private fun showEditWidgetBottomSheet() {
        val sheet = EditWidgetBottomSheet(
            settingsManager = settingsManager,
            onPickWidget = {
                launchWidgetPicker()
            },
            onRemoveWidget = { id ->
                removeUserWidget(id)
            },
            onToggleCanvasEditMode = {
                enterEditWidgetCanvasMode()
            },
            onLayoutChanged = {
                loadUserWidget()
            }
        )
        sheet.show(supportFragmentManager, "EditWidgetBottomSheet")
    }

    private fun showEditLayoutSpacingBottomSheet() {
        val hasActive = settingsManager.getUserWidgets().isNotEmpty()
        val sheet = vibes.launcher.angel.ui.EditLayoutSpacingBottomSheet(
            settingsManager = settingsManager,
            hasActiveWidget = hasActive,
            onLayoutChanged = {
                applyWidgetLayoutSettings()
            }
        )
        sheet.show(supportFragmentManager, "EditLayoutSpacingBottomSheet")
    }

    private fun initAppWidgetHost() {
        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = AppWidgetHost(this, APPWIDGET_HOST_ID)
        loadUserWidget()
    }

    private fun loadUserWidget() {
        applyWidgetLayoutSettings()
        widgetAreaContainer.removeAllViews()

        val allWidgets = settingsManager.getUserWidgets()
        val aboveClock = allWidgets.filter { it.isAboveClock }
        val belowClock = allWidgets.filter { !it.isAboveClock }

        // Render widgets above clock
        aboveClock.forEach { config ->
            val frame = createWidgetFrame(config)
            if (frame != null) {
                widgetAreaContainer.addView(frame)
            }
        }

        // Add Header Clock Widget
        widgetAreaContainer.addView(headerWidget)

        // Render widgets below clock
        belowClock.forEach { config ->
            val frame = createWidgetFrame(config)
            if (frame != null) {
                widgetAreaContainer.addView(frame)
            }
        }
    }

    private fun createWidgetFrame(config: vibes.launcher.angel.data.UserWidgetConfig): vibes.launcher.angel.ui.ResizableWidgetFrame? {
        val appWidgetInfo = appWidgetManager.getAppWidgetInfo(config.widgetId) ?: run {
            settingsManager.removeUserWidget(config.widgetId)
            return null
        }

        return try {
            val hostView = appWidgetHost.createView(this, config.widgetId, appWidgetInfo)
            hostView.setAppWidget(config.widgetId, appWidgetInfo)
            
            vibes.launcher.angel.ui.ResizableWidgetFrame(this).apply {
                setWidgetHostView(hostView, config)
                isEditMode = isEditWidgetMode
                
                onHeightChangedListener = { newHeight ->
                    settingsManager.updateWidgetConfig(config.widgetId, heightDp = newHeight)
                }
                onRemoveClickListener = {
                    removeUserWidget(config.widgetId)
                }
                onMoveUpClickListener = {
                    val list = settingsManager.getUserWidgets().toMutableList()
                    val index = list.indexOfFirst { it.widgetId == config.widgetId }
                    if (index > 0) {
                        val temp = list[index]
                        list[index] = list[index - 1]
                        list[index - 1] = temp
                        settingsManager.saveUserWidgets(list)
                        loadUserWidget()
                    }
                }
                onMoveDownClickListener = {
                    val list = settingsManager.getUserWidgets().toMutableList()
                    val index = list.indexOfFirst { it.widgetId == config.widgetId }
                    if (index != -1 && index < list.size - 1) {
                        val temp = list[index]
                        list[index] = list[index + 1]
                        list[index + 1] = temp
                        settingsManager.saveUserWidgets(list)
                        loadUserWidget()
                    }
                }
                onTogglePositionListener = {
                    settingsManager.updateWidgetConfig(config.widgetId, isAboveClock = !config.isAboveClock)
                    loadUserWidget()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun launchWidgetPicker() {
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        pendingAppWidgetId = appWidgetId
        val pickIntent = Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        pickWidgetLauncher.launch(pickIntent)
    }

    private fun removeUserWidget(widgetId: Int) {
        try {
            appWidgetHost.deleteAppWidgetId(widgetId)
        } catch (e: Exception) {}
        settingsManager.removeUserWidget(widgetId)
        loadUserWidget()
    }

    private fun configureOrCompleteWidget(appWidgetId: Int) {
        val appWidgetInfo = appWidgetManager.getAppWidgetInfo(appWidgetId)
        if (appWidgetInfo?.configure != null) {
            val intent = Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = appWidgetInfo.configure
                putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            createWidgetLauncher.launch(intent)
        } else {
            bindAndDisplayWidget(appWidgetId)
        }
    }

    private fun bindAndDisplayWidget(appWidgetId: Int) {
        settingsManager.addUserWidget(vibes.launcher.angel.data.UserWidgetConfig(widgetId = appWidgetId))
        loadUserWidget()
    }

    private fun loadApps() {
        loadAppsJob?.cancel()
        loadAppsJob = lifecycleScope.launch {
            val rawInstalledApps = withContext(Dispatchers.IO) {
                appRepository.getInstalledApps()
            }
            allAppsList = rawInstalledApps.filter { it.packageName != applicationContext.packageName }
            val allApps = allAppsList.filter { !it.isHidden }
            val favorites = allApps.filter { it.isPinned }.map { it.copy(isFavoriteSection = true) }

            val settingsApp = AppModel(
                label = "Angel Launcher Settings",
                packageName = "vibes.launcher.angel.settings",
                isHidden = false,
                isPinned = false,
                launchIntent = null,
                isFavoriteSection = false
            )
            
            // Build favorites list (State 0) with custom user ordering preserved
            val savedOrder = settingsManager.favoriteOrder
            val allFavApps = favorites.toMutableList()

            val sortedFavApps = if (savedOrder.isNotEmpty()) {
                allFavApps.sortedWith(Comparator { a, b ->
                    val indexA = savedOrder.indexOf(a.packageName)
                    val indexB = savedOrder.indexOf(b.packageName)
                    when {
                        indexA != -1 && indexB != -1 -> indexA.compareTo(indexB)
                        indexA != -1 -> -1
                        indexB != -1 -> 1
                        else -> a.displayLabel.compareTo(b.displayLabel, ignoreCase = true)
                    }
                })
            } else {
                allFavApps
            }
            
            favoritesList = sortedFavApps.map { AppListItem.App(it) } + listOf(AppListItem.Header(""))
            
            // Build full alphabet list (State 1)
            val tempFullAlphabetList = mutableListOf<AppListItem>()
            
            var currentLetter = ""
            for (app in allApps) {
                val firstChar = app.displayLabel.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
                val groupLetter = if (firstChar in "A".."Z") firstChar else "#"
                
                if (groupLetter != currentLetter) {
                    currentLetter = groupLetter
                    tempFullAlphabetList.add(AppListItem.Header(currentLetter))
                }
                tempFullAlphabetList.add(AppListItem.App(app))
            }
            tempFullAlphabetList.add(AppListItem.Divider)
            tempFullAlphabetList.add(AppListItem.App(settingsApp))
            tempFullAlphabetList.add(AppListItem.Header(""))
            fullAlphabetList = tempFullAlphabetList
            
            // Set initial state
            if (!isDragging) {
                if (isStateOneActive) {
                    appListAdapter.updateItems(fullAlphabetList)
                } else {
                    appListAdapter.updateItems(favoritesList)
                }
            }
            
            // Populate recent apps — use bulk getInstalledPackages to avoid O(n) IPC calls
            val installTimes: Map<String, Long> = withContext(Dispatchers.IO) {
                try {
                    packageManager.getInstalledPackages(0).associate { it.packageName to it.firstInstallTime }
                } catch (e: Exception) { emptyMap() }
            }
            val recentApps = allApps
                .sortedByDescending { installTimes[it.packageName] ?: 0L }
                .take(3)
            recentAppsList = recentApps.map { AppListItem.App(it) }
            
            // Only update adapter if search isn't active/filtering it right now
            val query = searchInput.text?.toString()?.trim() ?: ""
            if (query.isEmpty()) {
                recentAppListAdapter.updateItems(recentAppsList)
            }
        }
    }
}
