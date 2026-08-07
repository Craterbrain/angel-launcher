package vibes.launcher.angel.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(context: Context, private val appDao: AppDao) {
    private val context: Context = context.applicationContext

    suspend fun getInstalledApps(): List<AppModel> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        
        // Use queryIntentActivities (flags = 0)
        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        val installedPackageNames = resolveInfos.map { it.activityInfo.packageName }.toSet()

        val dbAppsList = appDao.getAll()
        for (entity in dbAppsList) {
            if (!installedPackageNames.contains(entity.packageName) && entity.packageName != "vibes.launcher.angel.settings") {
                appDao.deleteApp(entity.packageName)
            }
        }
        val dbApps = appDao.getAll().associateBy { it.packageName }
        
        val appModels = resolveInfos.mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName
            val label = resolveInfo.loadLabel(pm).toString()
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            
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
                launchIntent = launchIntent,
                useDefaultIcon = useDefaultIcon,
                customIconPack = customIconPack,
                customIconDrawable = customIconDrawable,
                customLabel = customLabel
            )
        }
        
        appModels.sortedBy { it.displayLabel.lowercase() }
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
}
