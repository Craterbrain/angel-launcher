package vibes.launcher.angel.ui

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vibes.launcher.angel.data.AppModel
import vibes.launcher.angel.data.SettingsManager
import vibes.launcher.angel.theme.AngelLauncherTheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppOptionsBottomSheet(
    private val app: AppModel,
    private val iconPackPackage: String?,
    private val iconPackResources: IconPackResources?,
    private val iconCacheManager: IconCacheManager? = null,
    private val onToggleFavorite: () -> Unit,
    private val onRenameApp: (String?) -> Unit,
    private val onOpenIconPicker: () -> Unit,
    private val onToggleHide: () -> Unit,
    private val onAppInfo: () -> Unit,
    private val onUninstall: () -> Unit
) : BottomSheetDialogFragment() {

    private val iconExtractor = IconExtractor.INSTANCE

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                sheet.setBackgroundColor(Color.TRANSPARENT)
                sheet.background = ColorDrawable(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isHideable = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val settingsManager = remember { SettingsManager(context) }
                val systemDark = isSystemInDarkTheme()
                val isDark = when (settingsManager.themeMode) {
                    1 -> false
                    2 -> true
                    else -> systemDark
                }

                AngelLauncherTheme(darkTheme = isDark, dynamicColor = true) {
                    AppOptionsSheetContent(
                        app = app,
                        iconExtractor = iconExtractor,
                        iconPackPackage = iconPackPackage,
                        iconPackResources = iconPackResources,
                        iconCacheManager = iconCacheManager,
                        onDismiss = { dismiss() },
                        onToggleFavorite = {
                            onToggleFavorite()
                            dismiss()
                        },
                        onRenameApp = { newName ->
                            onRenameApp(newName)
                            dismiss()
                        },
                        onOpenIconPicker = {
                            dismiss()
                            onOpenIconPicker()
                        },
                        onToggleHide = {
                            onToggleHide()
                            dismiss()
                        },
                        onAppInfo = {
                            dismiss()
                            onAppInfo()
                        },
                        onUninstall = {
                            dismiss()
                            onUninstall()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppOptionsSheetContent(
    app: AppModel,
    iconExtractor: IconExtractor,
    iconPackPackage: String?,
    iconPackResources: IconPackResources?,
    iconCacheManager: IconCacheManager?,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRenameApp: (String?) -> Unit,
    onOpenIconPicker: () -> Unit,
    onToggleHide: () -> Unit,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit
) {
    val context = LocalContext.current
    var appIconBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf(app.displayLabel) }

    LaunchedEffect(app) {
        val shape = SettingsManager(context).iconShape
        val customKeyPart = if (app.customIconPack != null && app.customIconDrawable != null) {
            "custom_${app.customIconPack}_${app.customIconDrawable}"
        } else if (app.useDefaultIcon) {
            "default"
        } else {
            iconPackPackage ?: "default"
        }
        val cacheKey = "${app.packageName}_${customKeyPart}_shape${shape}_size144"

        val memCached = iconCacheManager?.getFromMemory(cacheKey)
        if (memCached != null) {
            appIconBitmap = memCached
        } else {
            val bitmap = iconCacheManager?.getOrExtract(cacheKey) {
                iconExtractor.extractAndRasterizeIcon(
                    context,
                    app.packageName,
                    iconPackPackage,
                    iconPackResources,
                    144,
                    app.useDefaultIcon,
                    app.customIconPack,
                    app.customIconDrawable,
                    shape
                )
            } ?: withContext(Dispatchers.IO) {
                iconExtractor.extractAndRasterizeIcon(
                    context,
                    app.packageName,
                    iconPackPackage,
                    iconPackResources,
                    144,
                    app.useDefaultIcon,
                    app.customIconPack,
                    app.customIconDrawable,
                    shape
                )
            }
            appIconBitmap = bitmap
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "Rename App",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter a custom name for ${app.label}:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        label = { Text("Display Name") },
                        placeholder = { Text(app.label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        trailingIcon = {
                            if (renameInput.isNotEmpty()) {
                                IconButton(onClick = { renameInput = "" }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Clear,
                                        contentDescription = "Clear"
                                    )
                                }
                            }
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameInput.trim()
                        val custom = if (trimmed.isEmpty() || trimmed == app.label) null else trimmed
                        showRenameDialog = false
                        onRenameApp(custom)
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (app.customLabel != null) {
                        OutlinedButton(
                            onClick = {
                                showRenameDialog = false
                                onRenameApp(null)
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Reset")
                        }
                    }
                    TextButton(
                        onClick = { showRenameDialog = false },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Drag Handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // App Header Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        if (appIconBitmap != null) {
                            Image(
                                bitmap = appIconBitmap!!.asImageBitmap(),
                                contentDescription = app.displayLabel,
                                modifier = Modifier.size(46.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.displayLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (app.isPinned || app.isHidden || app.customLabel != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (app.isPinned) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = "★ Favorited",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (app.isHidden) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.errorContainer
                                    ) {
                                        Text(
                                            text = "Hidden",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (app.customLabel != null) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = "Renamed",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Options List Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    // 1. Favorite / Unfavorite
                    ModernSheetOptionItem(
                        icon = if (app.isPinned) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        iconTint = if (app.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        iconContainerColor = if (app.isPinned) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        title = if (app.isPinned) "Remove from Favorites" else "Add to Favorites",
                        subtitle = if (app.isPinned) "Unpin from top favorites section" else "Pin app to the top of your list",
                        onClick = onToggleFavorite
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    // 2. Rename App
                    ModernSheetOptionItem(
                        icon = Icons.Rounded.Edit,
                        iconTint = MaterialTheme.colorScheme.primary,
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        title = "Rename App",
                        subtitle = if (app.customLabel != null) "Current: \"${app.customLabel}\"" else "Set a custom display name",
                        onClick = { showRenameDialog = true }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    // 3. Edit Icon
                    ModernSheetOptionItem(
                        icon = Icons.Rounded.Palette,
                        iconTint = MaterialTheme.colorScheme.primary,
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        title = "Edit Icon",
                        subtitle = if (app.customIconDrawable != null) "Custom icon active" else if (app.useDefaultIcon) "System default icon" else "Pack icon active",
                        onClick = onOpenIconPicker
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    // 4. Hide / Show App
                    ModernSheetOptionItem(
                        icon = if (app.isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        title = if (app.isHidden) "Show in App List" else "Hide from App List",
                        subtitle = if (app.isHidden) "Restore app visibility in list" else "Hide app from the main app list",
                        onClick = onToggleHide
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    // 5. App Info
                    ModernSheetOptionItem(
                        icon = Icons.Rounded.Info,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        title = "App Info",
                        subtitle = "Open system application details & permissions",
                        onClick = onAppInfo
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    // 6. Uninstall
                    ModernSheetOptionItem(
                        icon = Icons.Rounded.DeleteOutline,
                        iconTint = MaterialTheme.colorScheme.error,
                        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                        title = "Uninstall",
                        subtitle = "Remove this application from device",
                        onClick = onUninstall
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ModernSheetOptionItem(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    iconContainerColor: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconContainerColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = iconTint
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp)
        )
    }
}
