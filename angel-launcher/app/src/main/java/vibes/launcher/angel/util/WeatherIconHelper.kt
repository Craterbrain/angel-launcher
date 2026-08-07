package vibes.launcher.angel.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import vibes.launcher.angel.R
import java.util.Locale

data class WeatherIconPack(
    val label: String,
    val packageName: String,
    val icon: Drawable?
)

object WeatherIconHelper {

    private val WEATHER_ICON_PACK_ACTIONS = listOf(
        "com.wangdaye.geometricweather.ICON_PROVIDER",
        "org.breezyweather.ICON_PROVIDER",
        "com.cyanogenmod.chronus.ICON_PACK",
        "chronus.MAIN"
    )

    fun getAvailableWeatherIconPacks(context: Context): List<WeatherIconPack> {
        val pm = context.packageManager
        val packsMap = mutableMapOf<String, WeatherIconPack>()

        for (action in WEATHER_ICON_PACK_ACTIONS) {
            val intent = Intent(action)
            val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            for (ri in resolveInfos) {
                val pkgName = ri.activityInfo.packageName
                if (!packsMap.containsKey(pkgName)) {
                    val label = ri.loadLabel(pm).toString()
                    val icon = ri.loadIcon(pm)
                    packsMap[pkgName] = WeatherIconPack(label, pkgName, icon)
                }
            }
        }

        try {
            val packages = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(PackageManager.GET_META_DATA)
            }
            for (pi in packages) {
                val pkgName = pi.packageName
                val appInfo = pi.applicationInfo
                if (!packsMap.containsKey(pkgName) && appInfo?.metaData != null) {
                    val metaData = appInfo.metaData
                    if (metaData.containsKey("com.wangdaye.geometricweather.PROVIDER_CONFIG") ||
                        metaData.containsKey("org.breezyweather.PROVIDER_CONFIG") ||
                        metaData.containsKey("chronus_icon_pack")) {
                        val label = appInfo.loadLabel(pm).toString()
                        val icon = appInfo.loadIcon(pm)
                        packsMap[pkgName] = WeatherIconPack(label, pkgName, icon)
                    }
                }
            }
        } catch (e: Exception) {}

        return packsMap.values.sortedBy { it.label }
    }

    fun getWeatherConditionCode(shortForecast: String, isDaytime: Boolean = true): Int {
        val lower = shortForecast.lowercase(Locale.US)
        return when {
            lower.contains("tornado") -> 0
            lower.contains("tropical storm") || lower.contains("hurricane") -> 1
            lower.contains("thunder") || lower.contains("t-storm") -> 4
            lower.contains("freezing rain") || lower.contains("sleet") -> 5
            lower.contains("rain and snow") || lower.contains("mix") -> 7
            lower.contains("flurries") || lower.contains("snow shower") -> 13
            lower.contains("snow") || lower.contains("blizzard") -> 16
            lower.contains("hail") -> 17
            lower.contains("dust") || lower.contains("sand") -> 19
            lower.contains("fog") || lower.contains("haze") || lower.contains("mist") || lower.contains("smoke") -> 20
            lower.contains("wind") || lower.contains("breezy") || lower.contains("gusty") -> 24
            lower.contains("cloudy") || lower.contains("overcast") -> 26
            lower.contains("mostly cloudy") -> if (isDaytime) 28 else 27
            lower.contains("partly cloudy") || lower.contains("partly sunny") || lower.contains("scattered clouds") -> if (isDaytime) 30 else 29
            lower.contains("clear") || lower.contains("sunny") || lower.contains("fair") -> if (isDaytime) 32 else 31
            lower.contains("heavy rain") || lower.contains("downpour") -> 12
            lower.contains("rain") || lower.contains("shower") || lower.contains("drizzle") -> 11
            else -> if (isDaytime) 32 else 31
        }
    }

    fun loadWeatherIconFromPack(
        context: Context,
        shortForecast: String,
        isDaytime: Boolean = true,
        packPackageName: String?
    ): Drawable? {
        if (packPackageName.isNullOrEmpty()) return null

        val pm = context.packageManager
        val packResources = try {
            pm.getResourcesForApplication(packPackageName)
        } catch (e: Exception) {
            return null
        }

        val code = getWeatherConditionCode(shortForecast, isDaytime)
        val candidateNames = mutableListOf<String>()

        candidateNames.add("weather_$code")
        candidateNames.add("weather_${code.toString().padStart(2, '0')}")

        val lower = shortForecast.lowercase(Locale.US)
        when {
            lower.contains("tornado") -> candidateNames.addAll(listOf("weather_tornado", "weather_0"))
            lower.contains("thunder") || lower.contains("t-storm") -> candidateNames.addAll(listOf("weather_thunderstorm", "weather_storm", "weather_4", "weather_04"))
            lower.contains("snow") || lower.contains("flurries") -> candidateNames.addAll(listOf("weather_snow", "weather_flurries", "weather_16", "weather_13"))
            lower.contains("heavy rain") -> candidateNames.addAll(listOf("weather_heavy_rain", "weather_rain", "weather_12", "weather_11"))
            lower.contains("rain") || lower.contains("shower") -> candidateNames.addAll(listOf("weather_rain", "weather_showers", "weather_11", "weather_12"))
            lower.contains("fog") || lower.contains("haze") -> candidateNames.addAll(listOf("weather_fog", "weather_haze", "weather_20"))
            lower.contains("wind") || lower.contains("breezy") -> candidateNames.addAll(listOf("weather_wind", "weather_breezy", "weather_24"))
            lower.contains("mostly cloudy") || lower.contains("cloudy") -> candidateNames.addAll(listOf("weather_cloudy", "weather_mostly_cloudy", "weather_26", "weather_28"))
            lower.contains("partly cloudy") || lower.contains("partly sunny") -> {
                if (isDaytime) candidateNames.addAll(listOf("weather_partly_cloudy", "weather_day_partly_cloudy", "weather_30"))
                else candidateNames.addAll(listOf("weather_partly_cloudy_night", "weather_night_partly_cloudy", "weather_29"))
            }
            lower.contains("clear") || lower.contains("sunny") || lower.contains("fair") -> {
                if (isDaytime) candidateNames.addAll(listOf("weather_sunny", "weather_clear_day", "weather_32"))
                else candidateNames.addAll(listOf("weather_clear_night", "weather_night_clear", "weather_31"))
            }
        }

        if (isDaytime) {
            candidateNames.addAll(listOf("weather_sunny", "weather_32"))
        } else {
            candidateNames.addAll(listOf("weather_clear_night", "weather_31"))
        }

        for (resName in candidateNames) {
            val resId = packResources.getIdentifier(resName, "drawable", packPackageName)
            if (resId != 0) {
                try {
                    val drawable = ResourcesCompat.getDrawable(packResources, resId, null)
                    if (drawable != null) return drawable
                } catch (e: Exception) {}
            }
        }
        return null
    }

    fun getWeatherIconRes(shortForecast: String, isDaytime: Boolean = true): Int {
        val lower = shortForecast.lowercase(Locale.US)
        return when {
            lower.contains("thunder") || lower.contains("t-storm") -> R.drawable.ic_weather_thunderstorm
            lower.contains("snow") || lower.contains("flurries") || lower.contains("blizzard") || lower.contains("sleet") -> R.drawable.ic_weather_snow
            lower.contains("heavy rain") || lower.contains("downpour") || lower.contains("torrential") -> R.drawable.ic_weather_thunderstorm
            lower.contains("rain") || lower.contains("shower") || lower.contains("drizzle") -> R.drawable.ic_weather_rain
            lower.contains("fog") || lower.contains("haze") || lower.contains("mist") || lower.contains("smoke") -> R.drawable.ic_weather_fog
            lower.contains("wind") || lower.contains("breezy") || lower.contains("gusty") -> R.drawable.ic_weather_wind
            lower.contains("mostly cloudy") || lower.contains("overcast") || lower.contains("cloudy") -> R.drawable.ic_weather_cloudy
            lower.contains("partly cloudy") || lower.contains("partly sunny") || lower.contains("scattered clouds") -> {
                if (isDaytime) R.drawable.ic_weather_partly_cloudy else R.drawable.ic_weather_partly_cloudy_night
            }
            lower.contains("clear") || lower.contains("sunny") || lower.contains("fair") -> {
                if (isDaytime) R.drawable.ic_weather_sunny else R.drawable.ic_weather_clear_night
            }
            else -> if (isDaytime) R.drawable.ic_weather_sunny else R.drawable.ic_weather_clear_night
        }
    }
}
