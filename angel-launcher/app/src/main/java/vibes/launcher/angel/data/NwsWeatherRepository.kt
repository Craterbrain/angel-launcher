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
    val shortForecast: String,
    /** Highest probability of precipitation across the day's periods, or null if NWS gave none. */
    val precipChance: Int? = null
)

data class WeatherData(
    val cityName: String,
    val state: String,
    val currentTemp: Int,
    val currentConditionEmoji: String,
    val currentShortForecast: String,
    val dailyForecasts: List<DailyForecast>,
    val timestamp: Long = System.currentTimeMillis(),
    /** Wind chill / heat index in °F from the latest observation; null when absent or ~equal to [currentTemp]. */
    val feelsLike: Int? = null,
    val windMph: Int? = null
)

data class NwsPeriod(
    val number: Int,
    val name: String,
    val temperature: Int,
    val temperatureUnit: String,
    val isDaytime: Boolean,
    val shortForecast: String,
    val detailedForecast: String,
    val iconUrl: String,
    val precipChance: Int? = null
)

/** What the gridpoint lookup tells us about a location. */
data class NwsGridpoint(val forecastUrl: String, val city: String, val state: String, val stationsUrl: String?)

private data class NwsObservation(val tempF: Int, val feelsLikeF: Int?, val windMph: Int?)

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

        private const val OBSERVATION_MAX_AGE_MS = 3 * 3600_000L

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

            val gridpoint = gridpointResult.getOrThrow()
            val forecastResult = fetchForecast(gridpoint.forecastUrl)
            if (forecastResult.isFailure) {
                return@withContext Result.failure(forecastResult.exceptionOrNull() ?: Exception("Failed to fetch forecast"))
            }

            val periods = forecastResult.getOrThrow()
            if (periods.isEmpty()) {
                return@withContext Result.failure(Exception("No forecast periods returned"))
            }

            val firstPeriod = periods.first()
            // The forecast's first period is a high/low, not what it is outside now; prefer a station reading.
            val observation = fetchObservation(gridpoint.stationsUrl)
            val currentTemp = observation?.tempF ?: firstPeriod.temperature
            val currentCondition = firstPeriod.shortForecast
            val currentEmoji = getConditionEmoji(currentCondition, firstPeriod.isDaytime)

            val dailyForecasts = processPeriodsToDaily(periods)

            val weatherData = WeatherData(
                cityName = gridpoint.city,
                state = gridpoint.state,
                currentTemp = currentTemp,
                currentConditionEmoji = currentEmoji,
                currentShortForecast = currentCondition,
                dailyForecasts = dailyForecasts,
                timestamp = System.currentTimeMillis(),
                feelsLike = observation?.feelsLikeF,
                windMph = observation?.windMph
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

    suspend fun fetchGridpoint(lat: Double, lon: Double): Result<NwsGridpoint> = withContext(Dispatchers.IO) {
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

            Result.success(NwsGridpoint(forecastUrl, city, state, properties.optString("observationStations").ifEmpty { null }))
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
                        iconUrl = p.optString("icon", ""),
                        precipChance = p.optJSONObject("probabilityOfPrecipitation")
                            ?.takeIf { !it.isNull("value") }?.optInt("value")
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
            var precip = p1.precipChance

            if (idx + 1 < periods.size) {
                val p2 = periods[idx + 1]
                if (isDay && !p2.isDaytime) {
                    // p1 is Day, p2 is Night — consume both
                    high = p1.temperature
                    low = p2.temperature
                    precip = maxOfNullable(precip, p2.precipChance)
                    idx += 2
                } else if (!isDay && p2.isDaytime) {
                    // p1 is Night, p2 is next Day — consume both to avoid duplicate day
                    low = p1.temperature
                    high = p2.temperature
                    precip = maxOfNullable(precip, p2.precipChance)
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
                    shortForecast = condition,
                    precipChance = precip
                )
            )
        }

        return dailyList
    }

    private fun maxOfNullable(a: Int?, b: Int?): Int? = if (a == null) b else if (b == null) a else maxOf(a, b)

    // --- Latest station observation (real current temperature, feels-like, wind) ---

    private fun stationIds(stationsUrl: String): List<String> {
        val prefs = context.getSharedPreferences("angel_launcher_prefs", Context.MODE_PRIVATE)
        val cached = prefs.getString("nws_stations", null)
        if (cached != null && cached.startsWith("$stationsUrl|")) {
            return cached.substringAfter('|').split(',').filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    private suspend fun fetchStationIds(stationsUrl: String): List<String> {
        val cached = stationIds(stationsUrl)
        if (cached.isNotEmpty()) return cached
        val response = executeWithRetry(newRequest(stationsUrl))
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }
        val body = response.use { it.body?.string() } ?: return emptyList()
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val ids = (0 until minOf(features.length(), 3)).mapNotNull {
            features.getJSONObject(it).optJSONObject("properties")?.optString("stationIdentifier")?.ifEmpty { null }
        }
        if (ids.isNotEmpty()) {
            context.getSharedPreferences("angel_launcher_prefs", Context.MODE_PRIVATE).edit()
                .putString("nws_stations", "$stationsUrl|${ids.joinToString(",")}").apply()
        }
        return ids
    }

    /** Best-effort: any failure just means the caller falls back to the forecast temperature. */
    private suspend fun fetchObservation(stationsUrl: String?): NwsObservation? = withContext(Dispatchers.IO) {
        if (stationsUrl == null) return@withContext null
        try {
            for (id in fetchStationIds(stationsUrl)) {
                val response = executeWithRetry(newRequest("https://api.weather.gov/stations/$id/observations/latest"))
                if (!response.isSuccessful) {
                    response.close()
                    continue
                }
                val body = response.use { it.body?.string() } ?: continue
                val props = JSONObject(body).optJSONObject("properties") ?: continue

                val stamp = props.optString("timestamp")
                val ageMs = runCatching {
                    System.currentTimeMillis() - java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(stamp)!!.time
                }.getOrNull()
                if (ageMs == null || ageMs > OBSERVATION_MAX_AGE_MS) continue

                val tempC = props.optJSONObject("temperature")?.takeIf { !it.isNull("value") }?.optDouble("value") ?: continue
                val tempF = Math.round(tempC * 9.0 / 5.0 + 32.0).toInt()

                val feelsC = listOf("heatIndex", "windChill").firstNotNullOfOrNull { key ->
                    props.optJSONObject(key)?.takeIf { !it.isNull("value") }?.optDouble("value")
                }
                val feelsF = feelsC?.let { Math.round(it * 9.0 / 5.0 + 32.0).toInt() }
                    ?.takeIf { Math.abs(it - tempF) >= 2 }

                val wind = props.optJSONObject("windSpeed")?.takeIf { !it.isNull("value") }
                val windMph = wind?.let {
                    val v = it.optDouble("value")
                    val factor = if (it.optString("unitCode").contains("m_s-1")) 2.23694 else 0.621371
                    Math.round(v * factor).toInt()
                }
                return@withContext NwsObservation(tempF, feelsF, windMph)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
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
