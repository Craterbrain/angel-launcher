package vibes.launcher.angel.data

import android.content.Intent

data class AppModel(
    val label: String,
    val packageName: String,
    val isHidden: Boolean,
    val isPinned: Boolean,
    val launchIntent: Intent?,
    val isFavoriteSection: Boolean = false,
    val useDefaultIcon: Boolean = false,
    val customIconPack: String? = null,
    val customIconDrawable: String? = null,
    val customLabel: String? = null
) {
    val displayLabel: String
        get() = if (!customLabel.isNullOrBlank()) customLabel else label
}
