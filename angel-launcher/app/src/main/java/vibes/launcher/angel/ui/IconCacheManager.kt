package vibes.launcher.angel.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vibes.launcher.angel.data.AppModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Two-tier (memory + disk) icon cache.
 *
 * Keys start with "<packageName>|" (see [appIconKey]) so one package's icons can be dropped on
 * their own, and disk files live in a per-package directory for the same reason.
 */
class IconCacheManager(context: Context) {

    companion object {
        private const val MAX_LOAD_ATTEMPTS = 3
        private const val SIGNATURE_FILE = "signature"

        /** Cache key for an app's icon. Includes the app's last update time so updated apps never show a stale icon. */
        fun appIconKey(app: AppModel, iconPackPackage: String?, iconShape: Int, sizePx: Int): String {
            val source = when {
                app.customIconDrawable != null -> "custom_${app.customIconPack}_${app.customIconDrawable}"
                app.useDefaultIcon -> "default"
                else -> iconPackPackage ?: "default"
            }
            return "${app.packageName}|v${app.lastUpdateTime}|$source|shape$iconShape|size$sizePx"
        }

        fun builtinIconKey(name: String, iconShape: Int, sizePx: Int): String =
            "$name|shape$iconShape|size$sizePx"
    }

    private val appContext = context.applicationContext
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.allocationByteCount / 1024
        }
    }

    private val diskCacheDir = File(appContext.cacheDir, "icon_cache")

    // Extractions run here, not in the caller's coroutine: a row that gets rebound cancels its own
    // wait, but never an extraction that another row is waiting on.
    private val extractionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    // Bumped by every invalidation. An extraction that started under an older generation may have
    // read a stale icon, so its result is not cached and its waiters load again.
    private val generation = AtomicLong()

    // Serializes disk writes against invalidation so a stale write cannot land after a clear.
    private val diskLock = Any()

    private val sha256Digest = java.security.MessageDigest.getInstance("SHA-256")

    init {
        extractionScope.launch {
            // Caches from before per-package directories kept flat files at the root; nothing reads them now.
            diskCacheDir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }?.forEach { it.delete() }
        }
    }

    private fun packageDir(packageName: String) = File(diskCacheDir, packageName)

    private fun getDiskFile(key: String): File {
        val digest = synchronized(sha256Digest) {
            sha256Digest.digest(key.toByteArray(Charsets.UTF_8))
        }
        val hexString = digest.joinToString("") { "%02x".format(it) }
        return File(packageDir(key.substringBefore('|')), "$hexString.bin")
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
    ): Bitmap? {
        // Tier 1: Fast memory cache check
        getFromMemory(key)?.let { return it }

        var bitmap: Bitmap? = null
        repeat(MAX_LOAD_ATTEMPTS) {
            val startGeneration = generation.get()
            // Tier 2: disk cache, then Tier 3: one shared extraction per key
            bitmap = getFromDisk(key) ?: run {
                val deferred = inFlightRequests.computeIfAbsent(key) { newExtraction(key, extractor) }
                deferred.await()
            }
            // Invalidated while loading: the result may be stale, so load again.
            if (generation.get() == startGeneration) return bitmap
        }
        return bitmap
    }

    private fun newExtraction(key: String, extractor: suspend () -> Bitmap?): Deferred<Bitmap?> {
        val startGeneration = generation.get()
        // LAZY so it cannot finish (and remove itself) before computeIfAbsent has stored it.
        val deferred = extractionScope.async(start = CoroutineStart.LAZY) {
            val bitmap = try {
                extractor()
            } catch (e: Exception) {
                android.util.Log.w("IconCacheManager", "Icon extraction failed for $key", e)
                null
            }
            if (bitmap != null) putIfCurrent(key, bitmap, startGeneration)
            bitmap
        }
        deferred.invokeOnCompletion { inFlightRequests.remove(key, deferred) }
        return deferred
    }

    private fun putIfCurrent(key: String, bitmap: Bitmap, startGeneration: Long) {
        // WEBP compression for speed and small disk footprint; done outside the lock.
        val bytes = ByteArrayOutputStream().use { out ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
            } else {
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
            }
            out.toByteArray()
        }
        synchronized(diskLock) {
            if (generation.get() != startGeneration) return
            memoryCache.put(key, bitmap)
            try {
                val file = getDiskFile(key)
                file.parentFile?.mkdirs()
                // Write then rename, so a concurrent reader never decodes a half-written file.
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) tmp.delete()
            } catch (e: Exception) {
                android.util.Log.w("IconCacheManager", "Disk cache operation failed", e)
            }
        }
    }

    /** Drops every cached icon for one package (all versions, shapes and sizes). */
    suspend fun invalidatePackage(packageName: String) = withContext(Dispatchers.IO) {
        val prefix = "$packageName|"
        synchronized(diskLock) {
            generation.incrementAndGet()
            memoryCache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { memoryCache.remove(it) }
            inFlightRequests.keys.filter { it.startsWith(prefix) }.forEach { inFlightRequests.remove(it) }
            try {
                packageDir(packageName).deleteRecursively()
            } catch (e: Exception) {
                android.util.Log.w("IconCacheManager", "Disk cache operation failed", e)
            }
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        synchronized(diskLock) {
            generation.incrementAndGet()
            memoryCache.evictAll()
            inFlightRequests.clear()
            try {
                diskCacheDir.listFiles { f -> f.isDirectory || f.name.endsWith(".bin") }
                    ?.forEach { it.deleteRecursively() }
            } catch (e: Exception) {
                android.util.Log.w("IconCacheManager", "Disk cache operation failed", e)
            }
        }
    }

    /**
     * Clears the cache when [signature] (icon pack + shape) differs from the one it was built with.
     * Keys already include both, so this only reclaims space; it never runs on an unchanged cold start.
     */
    suspend fun clearIfSignatureChanged(signature: String) = withContext(Dispatchers.IO) {
        val file = File(diskCacheDir, SIGNATURE_FILE)
        val previous = try { file.readText() } catch (e: Exception) { null }
        if (previous == signature) return@withContext
        if (previous != null) clearCache()
        try {
            diskCacheDir.mkdirs()
            file.writeText(signature)
        } catch (e: Exception) {
            android.util.Log.w("IconCacheManager", "Disk cache operation failed", e)
        }
    }
}
