package vibes.launcher.angel.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

data class UserWidgetConfig(
    val widgetId: Int,
    var heightDp: Int = 0, // 0 = Auto / Wrap Content
    var isAboveClock: Boolean = false
)

class SettingsManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("angel_launcher_prefs", Context.MODE_PRIVATE)

    private fun isDarkThemeActive(): Boolean {
        return when (val mode = prefs.getInt("theme_mode", 0)) {
            1 -> false
            2 -> true
            else -> {
                val uiMode = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    private fun getDefaultTextColor(): Int {
        return if (isDarkThemeActive()) 0xFFFFFFFF.toInt() else 0xFF222222.toInt()
    }

    private fun getDefaultSearchBarColor(): Int {
        return if (isDarkThemeActive()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }

    private fun getDefaultSearchBarOpacity(): Float {
        return if (isDarkThemeActive()) 0.20f else 0.08f
    }

    private fun getDefaultSearchDimColor(): Int {
        return if (isDarkThemeActive()) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun getDefaultSearchDimOpacity(): Float {
        return if (isDarkThemeActive()) 0.80f else 0.90f
    }

    private val memCache = ConcurrentHashMap<String, Any>()
    private val NULL_SENTINEL = Any()

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) {
            memCache.remove(key)
        } else {
            memCache.clear()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
    }

    private fun getCachedInt(key: String, default: Int): Int {
        val cached = memCache[key]
        if (cached is Int) return cached
        val value = prefs.getInt(key, default)
        memCache[key] = value
        return value
    }

    private fun setCachedInt(key: String, value: Int) {
        memCache[key] = value
        prefs.edit().putInt(key, value).apply()
    }

    private fun getCachedFloat(key: String, default: Float): Float {
        val cached = memCache[key]
        if (cached is Float) return cached
        val value = prefs.getFloat(key, default)
        memCache[key] = value
        return value
    }

    private fun setCachedFloat(key: String, value: Float) {
        memCache[key] = value
        prefs.edit().putFloat(key, value).apply()
    }

    private fun getCachedBoolean(key: String, default: Boolean): Boolean {
        val cached = memCache[key]
        if (cached is Boolean) return cached
        val value = prefs.getBoolean(key, default)
        memCache[key] = value
        return value
    }

    private fun setCachedBoolean(key: String, value: Boolean) {
        memCache[key] = value
        prefs.edit().putBoolean(key, value).apply()
    }

    private fun getCachedString(key: String, default: String?): String? {
        val cached = memCache[key]
        if (cached != null) {
            return if (cached === NULL_SENTINEL) null else cached as String
        }
        val value = prefs.getString(key, default)
        memCache[key] = value ?: NULL_SENTINEL
        return value
    }

    private fun setCachedString(key: String, value: String?) {
        if (value != null) {
            memCache[key] = value
            prefs.edit().putString(key, value).apply()
        } else {
            memCache[key] = NULL_SENTINEL
            prefs.edit().remove(key).apply()
        }
    }

    private fun getCachedLong(key: String, default: Long): Long {
        val cached = memCache[key]
        if (cached is Long) return cached
        val value = prefs.getLong(key, default)
        memCache[key] = value
        return value
    }

    private fun setCachedLong(key: String, value: Long) {
        memCache[key] = value
        prefs.edit().putLong(key, value).apply()
    }

    var globalScale: Float
        get() = getCachedFloat("global_scale", 0.75f)
        set(value) = setCachedFloat("global_scale", value)

    var iconScale: Float
        get() = getCachedFloat("icon_scale", 1.0f)
        set(value) = setCachedFloat("icon_scale", value)

    var textScale: Float
        get() = getCachedFloat("text_scale", 1.0f)
        set(value) = setCachedFloat("text_scale", value)

    var themeMode: Int // 0 = System, 1 = Light, 2 = Dark
        get() = getCachedInt("theme_mode", 0)
        set(value) = setCachedInt("theme_mode", value)

    var navBarThemeMode: Int // 0 = Transparent, 1 = Monet Secondary
        get() = getCachedInt("nav_bar_theme_mode", 0)
        set(value) = setCachedInt("nav_bar_theme_mode", value)

    var iconPackPackage: String?
        get() = getCachedString("icon_pack_package", null)
        set(value) = setCachedString("icon_pack_package", value)

    var weatherIconPackPackage: String?
        get() = getCachedString("weather_icon_pack_package", null)
        set(value) = setCachedString("weather_icon_pack_package", value)

    var iconShape: Int // 0 = System/Default, 1 = Circle, 2 = Squircle, 3 = Rounded Square, 4 = Teardrop, 5 = Hexagon
        get() = getCachedInt("icon_shape", SHAPE_SYSTEM)
        set(value) = setCachedInt("icon_shape", value)

    var favoriteOrder: List<String>
        get() = getCachedString("favorite_order", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = setCachedString("favorite_order", value.joinToString(","))

    /** Contact email sent in the NWS API User-Agent (NWS asks for contact info). Empty = not set. */
    var nwsContactEmail: String
        get() = getCachedString(KEY_NWS_CONTACT_EMAIL, "") ?: ""
        set(value) = setCachedString(KEY_NWS_CONTACT_EMAIL, value.trim())

    companion object {
        const val KEY_NWS_CONTACT_EMAIL = "nws_contact_email"
        const val SHAPE_SYSTEM = 0
        const val SHAPE_CIRCLE = 1
        const val SHAPE_SQUIRCLE = 2
        const val SHAPE_ROUNDED_SQUARE = 3
        const val SHAPE_TEARDROP = 4
        const val SHAPE_HEXAGON = 5
    }

    // --- Colors & Opacity Settings ---

    // App List & Scrubber Tint (State 1)
    var appListMonetEnabled: Boolean
        get() = getCachedBoolean("app_list_monet_enabled", true)
        set(value) = setCachedBoolean("app_list_monet_enabled", value)

    var appListCustomColor: Int
        get() = getCachedInt("app_list_custom_color", 0xFF000000.toInt())
        set(value) = setCachedInt("app_list_custom_color", value)

    var appListTintOpacity: Float
        get() = getCachedFloat("app_list_tint_opacity", 0.12f)
        set(value) = setCachedFloat("app_list_tint_opacity", value)

    fun resetAppListTint() {
        appListMonetEnabled = true
        appListCustomColor = 0xFF000000.toInt()
        appListTintOpacity = 0.12f
    }

    // App List Text & Headers Color
    var appTextColor: Int
        get() = getCachedInt("app_text_color", getDefaultTextColor())
        set(value) = setCachedInt("app_text_color", value)

    var appTextOpacity: Float
        get() = getCachedFloat("app_text_opacity", 1.0f)
        set(value) = setCachedFloat("app_text_opacity", value)

    fun resetAppTextColor() {
        appTextColor = getDefaultTextColor()
        appTextOpacity = 1.0f
    }

    // Scrubber Letters Color
    var scrubberTextColor: Int
        get() = getCachedInt("scrubber_text_color", getDefaultTextColor())
        set(value) = setCachedInt("scrubber_text_color", value)

    var scrubberTextOpacity: Float
        get() = getCachedFloat("scrubber_text_opacity", 1.0f)
        set(value) = setCachedFloat("scrubber_text_opacity", value)

    fun resetScrubberTextColor() {
        scrubberTextColor = getDefaultTextColor()
        scrubberTextOpacity = 1.0f
    }

    // Search Bar Surface Color
    var searchBarColor: Int
        get() = getCachedInt("search_bar_color", getDefaultSearchBarColor())
        set(value) = setCachedInt("search_bar_color", value)

    var searchBarOpacity: Float
        get() = getCachedFloat("search_bar_opacity", getDefaultSearchBarOpacity())
        set(value) = setCachedFloat("search_bar_opacity", value)

    fun resetSearchBarColor() {
        searchBarColor = getDefaultSearchBarColor()
        searchBarOpacity = getDefaultSearchBarOpacity()
    }

    // Search Dim Overlay Color
    var searchDimColor: Int
        get() = getCachedInt("search_dim_color", getDefaultSearchDimColor())
        set(value) = setCachedInt("search_dim_color", value)

    var searchDimOpacity: Float
        get() = getCachedFloat("search_dim_opacity", getDefaultSearchDimOpacity())
        set(value) = setCachedFloat("search_dim_opacity", value)

    fun resetSearchDimColor() {
        searchDimColor = getDefaultSearchDimColor()
        searchDimOpacity = getDefaultSearchDimOpacity()
    }

    // Search FAB Color
    var searchFabColor: Int
        get() = getCachedInt("search_fab_color", 0xFF2C2C2C.toInt())
        set(value) = setCachedInt("search_fab_color", value)

    var searchFabOpacity: Float
        get() = getCachedFloat("search_fab_opacity", 1.0f)
        set(value) = setCachedFloat("search_fab_opacity", value)

    fun resetSearchFabColor() {
        searchFabColor = 0xFF2C2C2C.toInt()
        searchFabOpacity = 1.0f
    }

    // Header Widget Clock & Date Text Color
    var clockTextColor: Int
        get() = getCachedInt("clock_text_color", getDefaultTextColor())
        set(value) = setCachedInt("clock_text_color", value)

    var clockTextOpacity: Float
        get() = getCachedFloat("clock_text_opacity", 1.0f)
        set(value) = setCachedFloat("clock_text_opacity", value)

    fun resetClockTextColor() {
        clockTextColor = getDefaultTextColor()
        clockTextOpacity = 1.0f
    }

    // Header Widget Weather Text Color
    var weatherTextColor: Int
        get() = getCachedInt("weather_text_color", getDefaultTextColor())
        set(value) = setCachedInt("weather_text_color", value)

    var weatherTextOpacity: Float
        get() = getCachedFloat("weather_text_opacity", 1.0f)
        set(value) = setCachedFloat("weather_text_opacity", value)

    fun resetWeatherTextColor() {
        weatherTextColor = getDefaultTextColor()
        weatherTextOpacity = 1.0f
    }

    // Edit Mode Banner Tint & Color
    var editModeBannerColor: Int
        get() = getCachedInt("edit_mode_banner_color", 0xFF006874.toInt())
        set(value) = setCachedInt("edit_mode_banner_color", value)

    var editModeBannerOpacity: Float
        get() = getCachedFloat("edit_mode_banner_opacity", 0.90f)
        set(value) = setCachedFloat("edit_mode_banner_opacity", value)

    fun resetEditModeBannerColor() {
        editModeBannerColor = 0xFF006874.toInt()
        editModeBannerOpacity = 0.90f
    }

    fun resetAllColors() {
        resetAppListTint()
        resetAppTextColor()
        resetScrubberTextColor()
        resetSearchBarColor()
        resetSearchDimColor()
        resetSearchFabColor()
        resetClockTextColor()
        resetWeatherTextColor()
        resetEditModeBannerColor()
    }

// --- Launcher Widget Settings ---

    var userWidgetId: Int
        get() = getCachedInt("user_widget_id", android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID)
        set(value) = setCachedInt("user_widget_id", value)

    var userWidgetAboveDefault: Boolean
        get() = getCachedBoolean("user_widget_above_default", false)
        set(value) = setCachedBoolean("user_widget_above_default", value)

    var userWidgetHeightDp: Int
        get() = getCachedInt("user_widget_height_dp", 0) // 0 = wrap_content / auto
        set(value) = setCachedInt("user_widget_height_dp", value)

    fun getUserWidgets(): List<UserWidgetConfig> {
        val json = prefs.getString("user_widgets_json", null)
        if (json.isNullOrEmpty()) {
            val singleId = userWidgetId
            return if (singleId != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
                listOf(UserWidgetConfig(singleId, userWidgetHeightDp, userWidgetAboveDefault))
            } else {
                emptyList()
            }
        }
        return try {
            val array = org.json.JSONArray(json)
            val list = mutableListOf<UserWidgetConfig>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    UserWidgetConfig(
                        widgetId = obj.getInt("widgetId"),
                        heightDp = obj.optInt("heightDp", 0),
                        isAboveClock = obj.optBoolean("isAboveClock", false)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveUserWidgets(widgets: List<UserWidgetConfig>) {
        val array = org.json.JSONArray()
        widgets.forEach { config ->
            val obj = org.json.JSONObject().apply {
                put("widgetId", config.widgetId)
                put("heightDp", config.heightDp)
                put("isAboveClock", config.isAboveClock)
            }
            array.put(obj)
        }
        val json = array.toString()
        prefs.edit().putString("user_widgets_json", json).apply()
        val first = widgets.firstOrNull()
        if (first != null) {
            userWidgetId = first.widgetId
            userWidgetHeightDp = first.heightDp
            userWidgetAboveDefault = first.isAboveClock
        } else {
            userWidgetId = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
        }
    }

    fun addUserWidget(config: UserWidgetConfig) {
        val current = getUserWidgets().toMutableList()
        current.removeAll { it.widgetId == config.widgetId }
        current.add(config)
        saveUserWidgets(current)
    }

    fun removeUserWidget(widgetId: Int) {
        val current = getUserWidgets().toMutableList()
        current.removeAll { it.widgetId == widgetId }
        saveUserWidgets(current)
    }

    fun updateWidgetConfig(widgetId: Int, heightDp: Int? = null, isAboveClock: Boolean? = null) {
        val current = getUserWidgets().toMutableList()
        val index = current.indexOfFirst { it.widgetId == widgetId }
        if (index != -1) {
            val item = current[index]
            if (heightDp != null) item.heightDp = heightDp
            if (isAboveClock != null) item.isAboveClock = isAboveClock
            saveUserWidgets(current)
        }
    }

    var widgetTopPaddingDp: Int
        get() = getCachedInt("widget_top_padding_dp", 0) // Custom top padding offset in dp
        set(value) = setCachedInt("widget_top_padding_dp", value)

    var widgetBottomPaddingDp: Int
        get() = getCachedInt("widget_bottom_padding_dp", 0) // Custom bottom padding offset in dp
        set(value) = setCachedInt("widget_bottom_padding_dp", value)

    var showWidget: Boolean
        get() = getCachedBoolean("widget_show", true)
        set(value) = setCachedBoolean("widget_show", value)

    var clock24Hour: Int // 0 = System Default, 1 = 12-Hour, 2 = 24-Hour
        get() = getCachedInt("widget_clock_24h", 0)
        set(value) = setCachedInt("widget_clock_24h", value)

    var showAmPm: Boolean
        get() = getCachedBoolean("widget_show_ampm", true)
        set(value) = setCachedBoolean("widget_show_ampm", value)

    var showDate: Boolean
        get() = getCachedBoolean("widget_show_date", true)
        set(value) = setCachedBoolean("widget_show_date", value)

    var dateFormat: String
        get() = getCachedString("widget_date_format", "EEE, MMM d") ?: "EEE, MMM d"
        set(value) = setCachedString("widget_date_format", value)

    var showWeather: Boolean
        get() = getCachedBoolean("widget_show_weather", true)
        set(value) = setCachedBoolean("widget_show_weather", value)

    var show5DayForecast: Boolean
        get() = getCachedBoolean("widget_show_5day_forecast", true)
        set(value) = setCachedBoolean("widget_show_5day_forecast", value)

    var weatherUseDeviceLocation: Boolean
        get() = getCachedBoolean("weather_use_device_location", true)
        set(value) = setCachedBoolean("weather_use_device_location", value)

    var weatherCustomLocationQuery: String
        get() = getCachedString("weather_custom_query", "") ?: ""
        set(value) = setCachedString("weather_custom_query", value)

    var weatherCustomCity: String
        get() = getCachedString("weather_custom_city", "") ?: ""
        set(value) = setCachedString("weather_custom_city", value)

    var weatherCustomState: String
        get() = getCachedString("weather_custom_state", "") ?: ""
        set(value) = setCachedString("weather_custom_state", value)

    var weatherCustomLat: Double
        get() = java.lang.Double.longBitsToDouble(getCachedLong("weather_custom_lat", java.lang.Double.doubleToLongBits(0.0)))
        set(value) = setCachedLong("weather_custom_lat", java.lang.Double.doubleToLongBits(value))

    var weatherCustomLon: Double
        get() = java.lang.Double.longBitsToDouble(getCachedLong("weather_custom_lon", java.lang.Double.doubleToLongBits(0.0)))
        set(value) = setCachedLong("weather_custom_lon", java.lang.Double.doubleToLongBits(value))

    @Volatile private var memoryCachedWeatherData: WeatherData? = null

    var weatherCachedJson: String?
        get() = getCachedString("weather_cached_json", null)
        set(value) {
            memoryCachedWeatherData = null
            setCachedString("weather_cached_json", value)
        }

    fun getCachedWeatherData(): WeatherData? {
        memoryCachedWeatherData?.let { return it }
        val jsonStr = weatherCachedJson ?: return null
        return try {
            val obj = org.json.JSONObject(jsonStr)
            val cityName = obj.getString("cityName")
            val state = obj.getString("state")
            val currentTemp = obj.getInt("currentTemp")
            val currentEmoji = obj.getString("currentConditionEmoji")
            val currentShortForecast = obj.getString("currentShortForecast")
            val timestamp = obj.optLong("timestamp", 0L)
            
            val dailyArr = obj.getJSONArray("dailyForecasts")
            val dailyList = mutableListOf<DailyForecast>()
            for (i in 0 until dailyArr.length()) {
                val d = dailyArr.getJSONObject(i)
                dailyList.add(
                    DailyForecast(
                        dayName = d.getString("dayName"),
                        conditionEmoji = d.getString("conditionEmoji"),
                        highTemp = d.getInt("highTemp"),
                        lowTemp = d.getInt("lowTemp"),
                        shortForecast = d.getString("shortForecast")
                    )
                )
            }
            val parsed = WeatherData(
                cityName = cityName,
                state = state,
                currentTemp = currentTemp,
                currentConditionEmoji = currentEmoji,
                currentShortForecast = currentShortForecast,
                dailyForecasts = dailyList,
                timestamp = timestamp
            )
            memoryCachedWeatherData = parsed
            parsed
        } catch (e: Exception) {
            null
        }
    }

    fun saveCachedWeatherData(data: WeatherData) {
        memoryCachedWeatherData = data
        try {
            val obj = org.json.JSONObject().apply {
                put("cityName", data.cityName)
                put("state", data.state)
                put("currentTemp", data.currentTemp)
                put("currentConditionEmoji", data.currentConditionEmoji)
                put("currentShortForecast", data.currentShortForecast)
                put("timestamp", data.timestamp)

                val arr = org.json.JSONArray()
                for (d in data.dailyForecasts) {
                    val dObj = org.json.JSONObject().apply {
                        put("dayName", d.dayName)
                        put("conditionEmoji", d.conditionEmoji)
                        put("highTemp", d.highTemp)
                        put("lowTemp", d.lowTemp)
                        put("shortForecast", d.shortForecast)
                    }
                    arr.put(dObj)
                }
                put("dailyForecasts", arr)
            }
            weatherCachedJson = obj.toString()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun clearWeatherCache() {
        memoryCachedWeatherData = null
        weatherCachedJson = null
    }

    fun resetWidgetSettings() {
        memoryCachedWeatherData = null
        val keysToRemove = listOf(
            "widget_show", "widget_clock_24h", "widget_show_ampm", 
            "widget_show_date", "widget_date_format", "widget_show_weather", 
            "weather_use_device_location", "weather_custom_query", 
            "weather_custom_city", "weather_custom_state", 
            "weather_custom_lat", "weather_custom_lon", "weather_cached_json"
        )
        keysToRemove.forEach { memCache.remove(it) }
        prefs.edit()
            .putBoolean("widget_show", true)
            .putInt("widget_clock_24h", 0)
            .putBoolean("widget_show_ampm", true)
            .putBoolean("widget_show_date", true)
            .putString("widget_date_format", "EEE, MMM d")
            .putBoolean("widget_show_weather", true)
            .putBoolean("weather_use_device_location", true)
            .putString("weather_custom_query", "")
            .putString("weather_custom_city", "")
            .putString("weather_custom_state", "")
            .putLong("weather_custom_lat", java.lang.Double.doubleToLongBits(0.0))
            .putLong("weather_custom_lon", java.lang.Double.doubleToLongBits(0.0))
            .remove("weather_cached_json")
            .apply()
    }
}
