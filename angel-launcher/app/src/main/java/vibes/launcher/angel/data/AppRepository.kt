package vibes.launcher.angel.data

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(context: Context, private val appDao: AppDao) {
    private val context: Context = context.applicationContext

    /**
     * Lists launchable apps merged with their stored settings.
     * With [prune], stored settings of packages that are no longer installed at all are deleted first
     * (a package that is merely disabled or not launchable keeps its settings).
     */
    suspend fun getInstalledApps(prune: Boolean = false): List<AppModel> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        // One bulk call serves both pruning and install times, instead of a getPackageInfo IPC per app
        val packageInfos = try {
            pm.getInstalledPackages(0).associateBy { it.packageName }
        } catch (e: Exception) { null }

        if (prune && !packageInfos.isNullOrEmpty()) {
            for (entity in appDao.getAll()) {
                if (entity.packageName != SETTINGS_PSEUDO_PACKAGE && entity.packageName !in packageInfos) {
                    appDao.deleteApp(entity.packageName)
                }
            }
        }

        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        
        // Use queryIntentActivities (flags = 0)
        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        val dbApps = appDao.getAll().associateBy { it.packageName }
        val locale = context.resources.configuration.locales[0]?.toLanguageTag() ?: ""

        val appModels = resolveInfos.distinctBy { it.activityInfo.packageName }.map { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName
            val packageInfo = packageInfos?.get(packageName)
            // loadLabel reads each app's resources, the most expensive part of a reload; the label only
            // changes with an update or a locale change, so cache it keyed on both.
            val labelKey = "$packageName/${resolveInfo.activityInfo.name}/${packageInfo?.lastUpdateTime}/$locale"
            val label = labelCache.getOrPut(labelKey) { resolveInfo.loadLabel(pm).toString() }

            val entity = dbApps[packageName]
            val isHidden = entity?.isHidden ?: false
            val isPinned = entity?.isPinned ?: false
            val useDefaultIcon = entity?.useDefaultIcon ?: false
            val customIconPack = entity?.customIconPack
            val customIconDrawable = entity?.customIconDrawable
            val customLabel = entity?.customLabel

            AppModel(
                label = label,
                packageName = packageName,
                isHidden = isHidden,
                isPinned = isPinned,
                activityName = resolveInfo.activityInfo.name,
                useDefaultIcon = useDefaultIcon,
                customIconPack = customIconPack,
                customIconDrawable = customIconDrawable,
                customLabel = customLabel,
                firstInstallTime = packageInfo?.firstInstallTime ?: 0L,
                lastUpdateTime = packageInfo?.lastUpdateTime ?: 0L
            )
        }
        
        appModels.sortedBy { it.displayLabel.lowercase() }
    }

    suspend fun getAllEntities(): List<AppEntity> = withContext(Dispatchers.IO) {
        appDao.getAll()
    }

    /** Inserts all entities atomically. */
    suspend fun insertAll(entities: List<AppEntity>) = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(context).runInTransaction {
            for (entity in entities) appDao.insertApp(entity)
        }
    }

    suspend fun deleteApp(packageName: String) = withContext(Dispatchers.IO) {
        appDao.deleteApp(packageName)
    }

    suspend fun updateApp(appModel: AppModel) = withContext(Dispatchers.IO) {
        appDao.insertApp(vibes.launcher.angel.data.AppEntity(
            appModel.packageName,
            appModel.isHidden,
            appModel.isPinned,
            appModel.useDefaultIcon,
            appModel.customIconPack,
            appModel.customIconDrawable,
            appModel.customLabel
        ))
    }

    suspend fun getHiddenApps(): List<AppModel> = withContext(Dispatchers.IO) {
        getInstalledApps().filter { it.isHidden }
    }

    suspend fun unhideApp(packageName: String) = withContext(Dispatchers.IO) {
        val entity = appDao.getAll().firstOrNull { it.packageName == packageName }
        if (entity != null) {
            appDao.insertApp(vibes.launcher.angel.data.AppEntity(
                entity.packageName,
                false,
                entity.isPinned,
                entity.useDefaultIcon,
                entity.customIconPack,
                entity.customIconDrawable,
                entity.customLabel
            ))
        }
    }

    suspend fun unhideAllApps() = withContext(Dispatchers.IO) {
        val all = appDao.getAll()
        for (entity in all) {
            if (entity.isHidden) {
                appDao.insertApp(vibes.launcher.angel.data.AppEntity(
                    entity.packageName,
                    false,
                    entity.isPinned,
                    entity.useDefaultIcon,
                    entity.customIconPack,
                    entity.customIconDrawable,
                    entity.customLabel
                ))
            }
        }
    }

    private companion object {
        const val SETTINGS_PSEUDO_PACKAGE = "vibes.launcher.angel.settings"
        // Shared across repository instances (activity re-creation); keys go stale only on update/locale change
        val labelCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}
