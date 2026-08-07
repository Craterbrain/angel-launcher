package vibes.launcher.angel.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

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

class NwsWeatherRepository(private val context: Context) {

    companion object {
        private class ResilientDns : Dns {
            private val sharedRandom = java.util.Random()

            override fun lookup(hostname: String): List<InetAddress> {
                try {
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    if (addresses.isNotEmpty()) return addresses
                } catch (e: Exception) {
                    android.util.Log.w("AngelWeather", "Dns.SYSTEM failed for $hostname: ${e.message}")
                }

                val publicDnsServers = listOf("8.8.8.8", "1.1.1.1", "9.9.9.9")
                for (dnsIp in publicDnsServers) {
                    try {
                        val addrs = queryDnsUdp(hostname, dnsIp)
                        if (addrs.isNotEmpty()) {
                            android.util.Log.d("AngelWeather", "Resolved $hostname via $dnsIp: $addrs")
                            return addrs
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AngelWeather", "queryDnsUdp failed for $dnsIp: ${e.message}", e)
                    }
                }

                throw UnknownHostException("Unable to resolve host \"$hostname\"")
            }

            private fun queryDnsUdp(hostname: String, dnsServerIp: String): List<InetAddress> {
                val socket = java.net.DatagramSocket()
                try {
                    socket.soTimeout = 3000
                    val dnsServer = InetAddress.getByName(dnsServerIp)

                    val baos = java.io.ByteArrayOutputStream()
                    val dos = java.io.DataOutputStream(baos)

                    val txId = (sharedRandom.nextInt(65536)).toShort()
                    dos.writeShort(txId.toInt())
                    dos.writeShort(0x0100) // Standard query + recursion desired
                    dos.writeShort(1) // 1 question
                    dos.writeShort(0)
                    dos.writeShort(0)
                    dos.writeShort(0)

                    for (part in hostname.split(".")) {
                        val bytes = part.toByteArray(Charsets.US_ASCII)
                        dos.writeByte(bytes.size)
                        dos.write(bytes)
                    }
                    dos.writeByte(0)
                    dos.writeShort(1) // Type A
                    dos.writeShort(1) // Class IN
                    dos.flush()

                    val queryBytes = baos.toByteArray()
                    val packet = java.net.DatagramPacket(queryBytes, queryBytes.size, dnsServer, 53)
                    socket.send(packet)

                    val buf = ByteArray(1024)
                    val respPacket = java.net.DatagramPacket(buf, buf.size)
                    socket.receive(respPacket)

                    val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(respPacket.data, 0, respPacket.length))
                    dis.readShort() // ID
                    dis.readShort() // Flags
                    val qdCount = dis.readShort().toInt() and 0xFFFF
                    val anCount = dis.readShort().toInt() and 0xFFFF
                    dis.readShort() // NS
                    dis.readShort() // AR

                    // Skip Question section
                    for (q in 0 until qdCount) {
                        while (true) {
                            val len = dis.readByte().toInt() and 0xFF
                            if (len == 0) break
                            if (len >= 192) {
                                dis.readByte()
                                break
                            }
                            dis.skipBytes(len)
                        }
                        dis.skipBytes(4) // QTYPE, QCLASS
                    }

                    val addresses = mutableListOf<InetAddress>()
                    // Parse Answer section
                    for (a in 0 until anCount) {
                        var len = dis.readByte().toInt() and 0xFF
                        while (true) {
                            if (len == 0) break
                            if (len >= 192) {
                                dis.readByte()
                                break
                            }
                            dis.skipBytes(len)
                            len = dis.readByte().toInt() and 0xFF
                        }
                        val type = dis.readShort().toInt() and 0xFFFF
                        dis.readShort() // Class
                        dis.readInt() // TTL
                        val dataLen = dis.readShort().toInt() and 0xFFFF
                        if (type == 1 && dataLen == 4) {
                            val ipBytes = ByteArray(4)
                            dis.readFully(ipBytes)
                            try {
                                addresses.add(InetAddress.getByAddress(hostname, ipBytes))
                            } catch (e: Exception) {}
                        } else {
                            dis.skipBytes(dataLen)
                        }
                    }
                    return addresses
                } finally {
                    socket.close()
                }
            }
        }

        /** Singleton OkHttpClient — reuses thread pool and connection pool across all requests */
        private val client: OkHttpClient = OkHttpClient.Builder()
            .dns(ResilientDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val requestWithUserAgent = original.newBuilder()
                    .header("User-Agent", vibes.launcher.angel.BuildConfig.NWS_USER_AGENT)
                    .header("Accept", "application/geo+json, application/json")
                    .build()
                chain.proceed(requestWithUserAgent)
            }
            .build()

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
        try {
            // Step 1: Format coordinates to max 4 decimal places
            val roundedLat = String.format(Locale.US, "%.4f", lat).toDouble()
            val roundedLon = String.format(Locale.US, "%.4f", lon).toDouble()

            // Guard: reject unset (0,0) coordinates
            if (roundedLat == 0.0 && roundedLon == 0.0) {
                return@withContext Result.failure(Exception("Coordinates not set"))
            }
            // Guard: NWS API only covers US territory (rough bounding box)
            val inUSBounds = roundedLat in 15.0..72.0 && roundedLon in -180.0..-60.0
            if (!inUSBounds) {
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
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun executeWithRetry(request: Request, maxRetries: Int = 3): okhttp3.Response {
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                return client.newCall(request).execute()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(attempt * 400L)
                    } catch (ie: InterruptedException) {
                        break
                    }
                }
            }
        }
        throw (lastException ?: Exception("Network request failed"))
    }

    suspend fun fetchGridpoint(lat: Double, lon: Double): Result<Triple<String, String, String>> = withContext(Dispatchers.IO) {
        val latStr = String.format(Locale.US, "%.4f", lat)
        val lonStr = String.format(Locale.US, "%.4f", lon)
        val url = "https://api.weather.gov/points/$latStr,$lonStr"

        val request = Request.Builder().url(url).build()

        try {
            val response = executeWithRetry(request)
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                if (code == 429) {
                    return@withContext Result.failure(Exception("NWS Rate Limit Reached (429)"))
                }
                return@withContext Result.failure(Exception("NWS API Error HTTP $code"))
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
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchForecast(forecastUrl: String): Result<List<NwsPeriod>> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(forecastUrl).build()

        try {
            val response = executeWithRetry(request)
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                if (code == 429) {
                    return@withContext Result.failure(Exception("NWS Rate Limit Reached (429)"))
                }
                return@withContext Result.failure(Exception("NWS Forecast Error HTTP $code"))
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
