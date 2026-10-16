package vibes.launcher.angel.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class IconCacheManager(context: Context) {
    
    private val appContext = context.applicationContext
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.allocationByteCount / 1024
        }
    }
    
    private val diskCacheDir = File(appContext.cacheDir, "icon_cache").apply {
        if (!exists()) mkdirs()
    }

    private val inFlightRequests = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()
    private val sha256Digest = java.security.MessageDigest.getInstance("SHA-256")

    private fun getDiskFile(key: String): File {
        val digest = synchronized(sha256Digest) {
            sha256Digest.digest(key.toByteArray(Charsets.UTF_8))
        }
        val hexString = digest.joinToString("") { "%02x".format(it) }
        return File(diskCacheDir, "$hexString.bin")
    }

    fun getFromMemory(key: String): Bitmap? {
        return memoryCache.get(key)
    }
    
    suspend fun getFromDisk(key: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = getDiskFile(key)
        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                memoryCache.put(key, bitmap)
                return@withContext bitmap
            }
        }
        null
    }

    suspend fun getOrExtract(
        key: String,
        extractor: suspend () -> Bitmap?
    ): Bitmap? = withContext(Dispatchers.IO) {
        // Tier 1: Fast memory cache check
        getFromMemory(key)?.let { return@withContext it }

        // Tier 2: Fast disk cache check
        getFromDisk(key)?.let { return@withContext it }

        // Tier 3: Singleflight deduplication to avoid cache stampede on cold start
        val deferred = CompletableDeferred<Bitmap?>()
        val existing = inFlightRequests.putIfAbsent(key, deferred)
        if (existing != null) {
            return@withContext existing.await()
        }

        try {
            val bitmap = extractor()
            if (bitmap != null) {
                put(key, bitmap)
            }
            deferred.complete(bitmap)
            bitmap
        } catch (e: Throwable) {
            deferred.complete(null)
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } finally {
            inFlightRequests.remove(key)
        }
    }
    
    suspend fun put(key: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        memoryCache.put(key, bitmap)
        
        try {
            val file = getDiskFile(key)
            FileOutputStream(file).use { out ->
                // WEBP compression for speed and small disk footprint
                // Use WEBP if available, otherwise PNG
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("IconCacheManager", "Disk cache operation failed", e)
        }
    }

    fun clearCache() {
        memoryCache.evictAll()
        inFlightRequests.values.forEach { it.complete(null) }
        inFlightRequests.clear()
        try {
            diskCacheDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            android.util.Log.w("IconCacheManager", "Disk cache operation failed", e)
        }
    }
}
