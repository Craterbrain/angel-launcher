package vibes.launcher.angel.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import vibes.launcher.angel.data.AppDatabase
import vibes.launcher.angel.data.AppEntity

class BackupManager(private val context: Context) {

    suspend fun exportBackup(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonObject = JSONObject()
            jsonObject.put("version", 1)
            jsonObject.put("timestamp", System.currentTimeMillis())

            // 1. Export SharedPreferences
            val prefs = context.getSharedPreferences("angel_launcher_prefs", Context.MODE_PRIVATE)
            val prefsJson = JSONObject()
            for ((key, value) in prefs.all) {
                if (value != null) {
                    when (value) {
                        is Set<*> -> {
                            val array = JSONArray()
                            for (item in value) {
                                array.put(item.toString())
                            }
                            prefsJson.put(key, array)
                        }
                        else -> prefsJson.put(key, value)
                    }
                }
            }
            jsonObject.put("preferences", prefsJson)

            // 2. Export Room Database App Entities
            val appDao = AppDatabase.getDatabase(context).appDao()
            val appEntities = appDao.all
            val entitiesArray = JSONArray()
            for (entity in appEntities) {
                val entityObj = JSONObject().apply {
                    put("packageName", entity.packageName)
                    put("isHidden", entity.isHidden)
                    put("isPinned", entity.isPinned)
                    put("useDefaultIcon", entity.useDefaultIcon)
                    put("customIconPack", entity.customIconPack ?: JSONObject.NULL)
                    put("customIconDrawable", entity.customIconDrawable ?: JSONObject.NULL)
                    put("customLabel", entity.customLabel ?: JSONObject.NULL)
                }
                entitiesArray.put(entityObj)
            }
            jsonObject.put("app_entities", entitiesArray)

            // Write JSON bytes to OutputStream
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(jsonObject.toString(2).toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Unable to open output file stream")
        }
    }

    suspend fun importRestore(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            } ?: throw IllegalStateException("Unable to open input file stream")

            val jsonObject = JSONObject(jsonString)

            // 1. Restore SharedPreferences
            if (jsonObject.has("preferences")) {
                val prefsJson = jsonObject.getJSONObject("preferences")
                val prefs = context.getSharedPreferences("angel_launcher_prefs", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.clear()

                val keys = prefsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = prefsJson.get(key)
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value.toFloat())
                        is Double -> editor.putFloat(key, value.toFloat())
                        is String -> editor.putString(key, value)
                        is JSONArray -> {
                            val stringSet = mutableSetOf<String>()
                            for (i in 0 until value.length()) {
                                stringSet.add(value.getString(i))
                            }
                            editor.putStringSet(key, stringSet)
                        }
                    }
                }
                editor.apply()
            }

            // 2. Restore Room App Entities
            if (jsonObject.has("app_entities")) {
                val entitiesArray = jsonObject.getJSONArray("app_entities")
                val appDao = AppDatabase.getDatabase(context).appDao()
                for (i in 0 until entitiesArray.length()) {
                    val entityObj = entitiesArray.getJSONObject(i)
                    val pkgName = entityObj.getString("packageName")
                    val isHidden = entityObj.optBoolean("isHidden", false)
                    val isPinned = entityObj.optBoolean("isPinned", false)
                    val useDefaultIcon = entityObj.optBoolean("useDefaultIcon", false)
                    val customIconPack = if (entityObj.isNull("customIconPack")) null else entityObj.optString("customIconPack")
                    val customIconDrawable = if (entityObj.isNull("customIconDrawable")) null else entityObj.optString("customIconDrawable")
                    val customLabel = if (entityObj.isNull("customLabel")) null else entityObj.optString("customLabel")

                    appDao.insertApp(
                        AppEntity(
                            pkgName,
                            isHidden,
                            isPinned,
                            useDefaultIcon,
                            customIconPack,
                            customIconDrawable,
                            customLabel
                        )
                    )
                }
            }
        }
    }
}
