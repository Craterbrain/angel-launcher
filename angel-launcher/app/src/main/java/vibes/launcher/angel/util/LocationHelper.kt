package vibes.launcher.angel.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

data class LocationSearchResult(
    val displayName: String,
    val city: String,
    val state: String,
    val latitude: Double,
    val longitude: Double
)

object LocationHelper {

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun getLastKnownLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        var bestLocation: Location? = null

        // Query candidate providers including FUSED_PROVIDER, NETWORK, GPS, PASSIVE
        val candidateProviders = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            candidateProviders.add(LocationManager.FUSED_PROVIDER)
        }
        candidateProviders.add(LocationManager.NETWORK_PROVIDER)
        candidateProviders.add(LocationManager.GPS_PROVIDER)
        candidateProviders.add(LocationManager.PASSIVE_PROVIDER)
        try {
            for (p in locationManager.allProviders) {
                if (!candidateProviders.contains(p)) candidateProviders.add(p)
            }
        } catch (e: Exception) {}

        for (provider in candidateProviders) {
            try {
                val l = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null) {
                    bestLocation = l
                } else {
                    val lTime = l.time
                    val bestTime = bestLocation.time
                    // Prefer significantly newer locations, or higher accuracy if similar timestamp
                    if (lTime > bestTime + 60_000L || (Math.abs(lTime - bestTime) <= 60_000L && l.accuracy < bestLocation.accuracy)) {
                        bestLocation = l
                    }
                }
            } catch (e: SecurityException) {
                // Ignore
            } catch (e: Exception) {
                // Ignore
            }
        }
        return bestLocation
    }

    suspend fun getDeviceLocation(context: Context): Location? = withContext(Dispatchers.IO) {
        val lastKnown = getLastKnownLocation(context)
        val isFresh = lastKnown != null && (System.currentTimeMillis() - lastKnown.time < 15 * 60 * 1000L)
        if (isFresh) return@withContext lastKnown

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasLocationPermission(context)) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager != null) {
                try {
                    val freshLocation = suspendCancellableCoroutine<Location?> { continuation ->
                        val signal = android.os.CancellationSignal()
                        continuation.invokeOnCancellation { signal.cancel() }

                        val provider = when {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                            else -> LocationManager.PASSIVE_PROVIDER
                        }

                        locationManager.getCurrentLocation(
                            provider,
                            signal,
                            context.mainExecutor
                        ) { location ->
                            if (continuation.isActive) {
                                continuation.resume(location)
                            }
                        }
                    }
                    if (freshLocation != null) return@withContext freshLocation
                } catch (e: Exception) {
                    // Fallback to lastKnown
                }
            }
        }
        lastKnown
    }

    suspend fun searchLocation(context: Context, query: String): List<LocationSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        val results = mutableListOf<LocationSearchResult>()

        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.US)
                val addresses: List<Address>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // suspendCancellableCoroutine is coroutine-friendly and doesn't block the IO thread pool
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocationName(trimmed, 5) { list ->
                            if (cont.isActive) cont.resume(list)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(trimmed, 5)
                }

                if (!addresses.isNullOrEmpty()) {
                    for (addr in addresses) {
                        val city = addr.locality ?: addr.subAdminArea ?: addr.featureName ?: trimmed
                        val state = addr.adminArea ?: ""
                        val country = addr.countryCode ?: ""

                        // Format a clean display name
                        val displayParts = mutableListOf<String>()
                        if (city.isNotBlank()) displayParts.add(city)
                        if (state.isNotBlank()) displayParts.add(state)
                        if (country.isNotBlank() && country != "US") displayParts.add(country)
                        val displayName = if (displayParts.isNotEmpty()) displayParts.joinToString(", ") else trimmed

                        results.add(
                            LocationSearchResult(
                                displayName = displayName,
                                city = city,
                                state = state,
                                latitude = addr.latitude,
                                longitude = addr.longitude
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Geocoder exception fallback
        }

        results
    }
}
