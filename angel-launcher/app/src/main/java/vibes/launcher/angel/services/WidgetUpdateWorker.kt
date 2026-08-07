package vibes.launcher.angel.services

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import vibes.launcher.angel.data.NwsWeatherRepository
import vibes.launcher.angel.data.SettingsManager
import vibes.launcher.angel.util.LocationHelper

class WidgetUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settingsManager = SettingsManager(applicationContext)
        if (!settingsManager.showWeather) {
            return Result.success()
        }

        var lat = settingsManager.weatherCustomLat
        var lon = settingsManager.weatherCustomLon

        if (settingsManager.weatherUseDeviceLocation) {
            val deviceLocation = LocationHelper.getLastKnownLocation(applicationContext)
            if (deviceLocation != null) {
                lat = deviceLocation.latitude
                lon = deviceLocation.longitude
            }
        }

        // Skip fetch if no coordinates have been set
        if (lat == 0.0 && lon == 0.0) {
            return Result.success()
        }

        val repository = NwsWeatherRepository(applicationContext)
        val result = repository.getWeatherData(lat, lon)

        return if (result.isSuccess) {
            val data = result.getOrThrow()
            settingsManager.saveCachedWeatherData(data)

            val intent = Intent(ACTION_WEATHER_UPDATED).apply {
                setPackage(applicationContext.packageName)
            }
            applicationContext.sendBroadcast(intent)

            Result.success()
        } else {
            val exception = result.exceptionOrNull()
            val msg = exception?.message ?: ""
            when {
                msg.contains("429") -> Result.retry()  // Rate limited
                msg.contains("50") -> Result.retry()  // Server error (500-599)
                msg.contains("timeout", ignoreCase = true) -> Result.retry()
                msg.contains("Unable to resolve", ignoreCase = true) -> Result.retry()
                else -> Result.failure()
            }
        }
    }

    companion object {
        const val ACTION_WEATHER_UPDATED = "vibes.launcher.angel.ACTION_WEATHER_UPDATED"
    }
}
