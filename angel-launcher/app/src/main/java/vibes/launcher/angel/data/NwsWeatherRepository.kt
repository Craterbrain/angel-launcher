package vibes.launcher.angel.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DailyForecast(
    val dayName: String,
    val conditionEmoji: String,
    val highTemp: Int,
    val lowTemp: Int,
    val shortForecast: String
)

data class WeatherData(
    val cityName: String,
    val state: String,
    val currentTemp: Int,
    val currentConditionEmoji: String,
    val currentShortForecast: String,
    val dailyForecasts: List<DailyForecast>,
    val timestamp: Long = System.currentTimeMillis()
)

data class NwsPeriod(
    val number: Int,
    val name: String,
    val temperature: Int,
    val temperatureUnit: String,
    val isDaytime: Boolean,
    val shortForecast: String,
    val detailedForecast: String,
    val iconUrl: String
)

/** Non-2xx response from api.weather.gov. */
class NwsHttpException(val code: Int) : Exception(
    if (code == 429) "NWS Rate Limit Reached (429)" else "NWS API Error HTTP $code"
) {
    val isRetryable: Boolean get() = code == 429 || code in 500..599
}

/** The NWS API requires a contact email in the User-Agent; without one no requests are made. */
class NwsContactMissingException : Exception("Add a contact email in Settings to enable weather")

class NwsWeatherRepository(private val context: Context) {

    companion object {
        /** Singleton OkHttpClient — reuses thread pool and connection pool across all requests */
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /** Bounding boxes (minLat, maxLat, minLon, maxLon) for areas covered by api.weather.gov */
        private val NWS_COVERAGE_BOXES = listOf(
            doubleArrayOf(24.0, 49.6, -125.0, -66.5),   // Contiguous US
            doubleArrayOf(51.0, 72.0, -180.0, -129.0),  // Alaska
            doubleArrayOf(51.0, 54.0, 172.0, 180.0),    // Western Aleutians
            doubleArrayOf(18.5, 22.5, -161.0, -154.0),  // Hawaii
            doubleArrayOf(17.5, 18.8, -68.0, -64.0),    // Puerto Rico & US Virgin Islands
            doubleArrayOf(13.0, 21.0, 144.0, 146.5),    // Guam & Northern Marianas
            doubleArrayOf(-14.7, -14.0, -171.5, -168.0) // American Samoa
        )

        fun isWithinNwsCoverage(lat: Double, lon: Double): Boolean =
            NWS_COVERAGE_BOXES.any { lat in it[0]..it[1] && lon in it[2]..it[3] }

        private fun contactEmail(context: Context): String =
            context.getSharedPreferences("angel_launcher_prefs", Context.MODE_PRIVATE)
                .getString(SettingsManager.KEY_NWS_CONTACT_EMAIL, "")?.trim().orEmpty()

        fun hasContactEmail(context: Context): Boolean = contactEmail(context).contains("@")

        private fun userAgent(context: Context): String = "(AngelLauncher, ${contactEmail(context)})"

        /** In-memory cache to avoid JSON-parsing SharedPreferences every minute */
        @Volatile private var memoryCache: WeatherData? = null

        fun getConditionEmoji(shortForecast: String, isDaytime: Boolean = true): String {
            val lower = shortForecast.lowercase(Locale.US)
            return when {
                lower.contains("thunder") || lower.contains("t-storm") -> "⛈"
                lower.contains("snow") || lower.contains("flurries") || lower.contains("blizzard") || lower.contains("sleet") -> "❄"
                lower.contains("rain") || lower.contains("shower") || lower.contains("drizzle") -> "🌧"
                lower.contains("fog") || lower.contains("haze") || lower.contains("mist") || lower.contains("smoke") -> "🌫"
                lower.contains("wind") || lower.contains("breezy") || lower.contains("gusty") -> "💨"
                lower.contains("mostly cloudy") || lower.contains("overcast") || lower.contains("cloudy") -> "☁"
                lower.contains("partly cloudy") || lower.contains("partly sunny") -> if (isDaytime) "⛅" else "☁"
                lower.contains("clear") || lower.contains("sunny") || lower.contains("fair") -> if (isDaytime) "☀" else "🌙"
                else -> if (isDaytime) "☀" else "🌙"
            }
        }

        fun invalidateMemoryCache() {
            memoryCache = null
        }
    }

    suspend fun getWeatherData(lat: Double, lon: Double): Result<WeatherData> = withContext(Dispatchers.IO) {
        if (!hasContactEmail(context)) {
            return@withContext Result.failure(NwsContactMissingException())
        }
        try {
            // Step 1: Format coordinates to max 4 decimal places
            val roundedLat = String.format(Locale.US, "%.4f", lat).toDouble()
            val roundedLon = String.format(Locale.US, "%.4f", lon).toDouble()

            // Guard: reject unset (0,0) coordinates
            if (roundedLat == 0.0 && roundedLon == 0.0) {
                return@withContext Result.failure(Exception("Coordinates not set"))
            }
            // Guard: NWS API only covers US territory (rough bounding boxes)
            if (!isWithinNwsCoverage(roundedLat, roundedLon)) {
                return@withContext Result.failure(Exception("NWS weather is only available within the United States"))
            }

            val gridpointResult = fetchGridpoint(roundedLat, roundedLon)
            if (gridpointResult.isFailure) {
                return@withContext Result.failure(gridpointResult.exceptionOrNull() ?: Exception("Failed to fetch gridpoint"))
            }

            val (forecastUrl, city, state) = gridpointResult.getOrThrow()
            val forecastResult = fetchForecast(forecastUrl)
            if (forecastResult.isFailure) {
                return@withContext Result.failure(forecastResult.exceptionOrNull() ?: Exception("Failed to fetch forecast"))
            }

            val periods = forecastResult.getOrThrow()
            if (periods.isEmpty()) {
                return@withContext Result.failure(Exception("No forecast periods returned"))
            }

            val firstPeriod = periods.first()
            val currentTemp = firstPeriod.temperature
            val currentCondition = firstPeriod.shortForecast
            val currentEmoji = getConditionEmoji(currentCondition, firstPeriod.isDaytime)

            val dailyForecasts = processPeriodsToDaily(periods)

            val weatherData = WeatherData(
                cityName = city,
                state = state,
                currentTemp = currentTemp,
                currentConditionEmoji = currentEmoji,
                currentShortForecast = currentCondition,
                dailyForecasts = dailyForecasts,
                timestamp = System.currentTimeMillis()
            )

            memoryCache = weatherData
            Result.success(weatherData)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun newRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent(context))
        .header("Accept", "application/geo+json, application/json")
        .build()

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
    }

    private suspend fun executeWithRetry(request: Request, maxRetries: Int = 3): Response {
        var lastException: IOException? = null
        for (attempt in 1..maxRetries) {
            try {
                return client.newCall(request).await()
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries) delay(attempt * 400L)
            }
        }
        throw lastException ?: IOException("Network request failed")
    }

    suspend fun fetchGridpoint(lat: Double, lon: Double): Result<Triple<String, String, String>> = withContext(Dispatchers.IO) {
        val latStr = String.format(Locale.US, "%.4f", lat)
        val lonStr = String.format(Locale.US, "%.4f", lon)
        val url = "https://api.weather.gov/points/$latStr,$lonStr"

        val request = newRequest(url)

        try {
            val response = executeWithRetry(request)
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                return@withContext Result.failure(NwsHttpException(code))
            }

            // Use response.use{} to ensure body is always closed
            val body = response.use { it.body?.string() }
                ?: return@withContext Result.failure(Exception("Empty gridpoint response"))
            val json = JSONObject(body)
            val properties = json.optJSONObject("properties") ?: return@withContext Result.failure(Exception("Missing properties in gridpoint"))

            val forecastUrl = properties.optString("forecast")
            if (forecastUrl.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No forecast URL in gridpoint response"))
            }

            var city = "Local Area"
            var state = ""
            val relativeLoc = properties.optJSONObject("relativeLocation")
            if (relativeLoc != null) {
                val relProps = relativeLoc.optJSONObject("properties")
                if (relProps != null) {
                    city = relProps.optString("city", "Local Area")
                    state = relProps.optString("state", "")
                }
            }

            Result.success(Triple(forecastUrl, city, state))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchForecast(forecastUrl: String): Result<List<NwsPeriod>> = withContext(Dispatchers.IO) {
        val request = newRequest(forecastUrl)

        try {
            val response = executeWithRetry(request)
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                return@withContext Result.failure(NwsHttpException(code))
            }

            // Use response.use{} to ensure body is always closed
            val body = response.use { it.body?.string() }
                ?: return@withContext Result.failure(Exception("Empty forecast body"))

            val json = JSONObject(body)
            val properties = json.optJSONObject("properties") ?: return@withContext Result.failure(Exception("Missing properties in forecast response"))
            val periodsArray = properties.optJSONArray("periods") ?: return@withContext Result.failure(Exception("No periods in forecast"))

            val list = mutableListOf<NwsPeriod>()
            for (i in 0 until periodsArray.length()) {
                val p = periodsArray.getJSONObject(i)
                list.add(
                    NwsPeriod(
                        number = p.optInt("number", i + 1),
                        name = p.optString("name", ""),
                        temperature = p.optInt("temperature", 0),
                        temperatureUnit = p.optString("temperatureUnit", "F"),
                        isDaytime = p.optBoolean("isDaytime", true),
                        shortForecast = p.optString("shortForecast", ""),
                        detailedForecast = p.optString("detailedForecast", ""),
                        iconUrl = p.optString("icon", "")
                    )
                )
            }

            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun processPeriodsToDaily(periods: List<NwsPeriod>): List<DailyForecast> {
        val dailyList = mutableListOf<DailyForecast>()
        var idx = 0

        while (idx < periods.size && dailyList.size < 5) {
            val p1 = periods[idx]
            val isDay = p1.isDaytime
            val dayNameFormatted = formatDayName(p1.name, dailyList.isEmpty())

            var high = p1.temperature
            var low = p1.temperature
            var condition = p1.shortForecast

            if (idx + 1 < periods.size) {
                val p2 = periods[idx + 1]
                if (isDay && !p2.isDaytime) {
                    // p1 is Day, p2 is Night — consume both
                    high = p1.temperature
                    low = p2.temperature
                    idx += 2
                } else if (!isDay && p2.isDaytime) {
                    // p1 is Night, p2 is next Day — consume both to avoid duplicate day
                    low = p1.temperature
                    high = p2.temperature
                    idx += 2
                } else {
                    idx += 1
                }
            } else {
                idx += 1
            }

            if (low > high) {
                val temp = high
                high = low
                low = temp
            }

            val emoji = getConditionEmoji(condition, isDay)
            dailyList.add(
                DailyForecast(
                    dayName = dayNameFormatted,
                    conditionEmoji = emoji,
                    highTemp = high,
                    lowTemp = low,
                    shortForecast = condition
                )
            )
        }

        return dailyList
    }

    private fun formatDayName(rawName: String, isFirst: Boolean): String {
        val lower = rawName.lowercase(Locale.US)
        if (isFirst || lower.contains("today") || lower.contains("this afternoon") || lower.contains("tonight")) {
            return "Today"
        }
        return when {
            lower.startsWith("mon") -> "Mon"
            lower.startsWith("tue") -> "Tue"
            lower.startsWith("wed") -> "Wed"
            lower.startsWith("thu") -> "Thu"
            lower.startsWith("fri") -> "Fri"
            lower.startsWith("sat") -> "Sat"
            lower.startsWith("sun") -> "Sun"
            else -> rawName.take(3)
        }
    }
}
