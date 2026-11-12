package vibes.launcher.angel.data

import android.content.ComponentName
import android.content.Intent

data class AppModel(
    val label: String,
    val packageName: String,
    val isHidden: Boolean,
    val isPinned: Boolean,
    // Launcher activity class. Stored as a String rather than an Intent because Intent has
    // identity equality, which made every reload look like a change to DiffUtil.
    val activityName: String?,
    val isFavoriteSection: Boolean = false,
    val useDefaultIcon: Boolean = false,
    val customIconPack: String? = null,
    val customIconDrawable: String? = null,
    val customLabel: String? = null,
    val firstInstallTime: Long = 0L,
    val lastUpdateTime: Long = 0L
) {
    val displayLabel: String
        get() = if (!customLabel.isNullOrBlank()) customLabel else label

    val launchIntent: Intent?
        get() = activityName?.let { activity ->
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(packageName, activity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        }
}
