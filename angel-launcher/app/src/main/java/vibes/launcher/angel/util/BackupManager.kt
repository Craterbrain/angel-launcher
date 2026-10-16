package vibes.launcher.angel.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import vibes.launcher.angel.data.AppDatabase
import vibes.launcher.angel.data.AppEntity
import vibes.launcher.angel.data.AppRepository

class BackupManager(private val context: Context) {

    private fun repository() = AppRepository(context, AppDatabase.getDatabase(context).appDao())

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
            val appEntities = repository().getAllEntities()
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
            if (!jsonObject.has("preferences") && !jsonObject.has("app_entities")) {
                throw IllegalArgumentException("Not an Angel Launcher backup file")
            }

            // Phase 1: parse and validate everything. Nothing is modified until this succeeds.
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.all
            val parsedPrefs = LinkedHashMap<String, Any>()
            if (jsonObject.has("preferences")) {
                val prefsJson = jsonObject.getJSONObject("preferences")
                val keys = prefsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (prefsJson.isNull(key)) continue
                    val value = prefsJson.get(key)
                    // Prefer the type the preference currently has, since JSON can't tell Int/Long/Float apart
                    val converted: Any? = when (value) {
                        is Boolean -> value
                        is String -> value
                        is Number -> when (existing[key]) {
                            is Float -> value.toFloat()
                            is Long -> value.toLong()
                            is Int -> value.toInt()
                            else -> when (value) {
                                is Int -> value
                                is Long -> value
                                else -> value.toFloat()
                            }
                        }
                        is JSONArray -> {
                            val set = HashSet<String>()
                            for (i in 0 until value.length()) set.add(value.getString(i))
                            set
                        }
                        else -> null
                    }
                    if (converted != null) parsedPrefs[key] = converted
                }
            }

            val parsedEntities = mutableListOf<AppEntity>()
            if (jsonObject.has("app_entities")) {
                val entitiesArray = jsonObject.getJSONArray("app_entities")
                for (i in 0 until entitiesArray.length()) {
                    val entityObj = entitiesArray.getJSONObject(i)
                    parsedEntities.add(
                        AppEntity(
                            entityObj.getString("packageName"),
                            entityObj.optBoolean("isHidden", false),
                            entityObj.optBoolean("isPinned", false),
                            entityObj.optBoolean("useDefaultIcon", false),
                            if (entityObj.isNull("customIconPack")) null else entityObj.optString("customIconPack"),
                            if (entityObj.isNull("customIconDrawable")) null else entityObj.optString("customIconDrawable"),
                            if (entityObj.isNull("customLabel")) null else entityObj.optString("customLabel")
                        )
                    )
                }
            }

            // Phase 2: apply. The database goes in one transaction, then preferences are written synchronously.
            if (jsonObject.has("app_entities")) {
                repository().insertAll(parsedEntities)
            }

            if (jsonObject.has("preferences")) {
                val editor = prefs.edit()
                editor.clear()
                for ((key, value) in parsedPrefs) {
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                if (!editor.commit()) throw IllegalStateException("Failed to write restored preferences")
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "angel_launcher_prefs"
    }
}
