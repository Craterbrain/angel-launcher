package vibes.launcher.angel.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import vibes.launcher.angel.R
import vibes.launcher.angel.data.AppModel
import vibes.launcher.angel.data.SettingsManager
import vibes.launcher.angel.theme.AngelLauncherTheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TopBarIconItem(
    val title: String,
    val subtitle: String,
    val iconPackPackage: String?,
    val drawableName: String?,
    val isSystem: Boolean = false,
    val isPackDefault: Boolean = false,
    val isSelected: Boolean = false
)

data class GridIconItem(
    val name: String,
    val iconPackPackage: String?,
    val drawableName: String,
    val isBuiltin: Boolean = false,
    val builtinResId: Int = 0
)

class IconPickerBottomSheet(
    private val app: AppModel,
    private val iconPackPackage: String?,
    private val iconPackResources: IconPackResources?,
    private val onIconSelected: (customPack: String?, customDrawable: String?) -> Unit
) : BottomSheetDialogFragment() {

    private val iconExtractor = IconExtractor.INSTANCE
    private val iconPackParser = IconPackParser.INSTANCE

    companion object {
        val CATEGORY_BUILTIN_MAP = mapOf(
            "Browser" to Pair("Generic Browser", R.drawable.ic_cat_browser),
            "Camera" to Pair("Generic Camera", R.drawable.ic_cat_camera),
            "Calendar" to Pair("Generic Calendar", R.drawable.ic_cat_calendar),
            "Clock" to Pair("Generic Clock", R.drawable.ic_cat_clock),
            "Communication" to Pair("Generic Phone", R.drawable.ic_cat_phone),
            "Files" to Pair("Generic Files", R.drawable.ic_cat_files),
            "Gallery" to Pair("Generic Gallery", R.drawable.ic_cat_gallery),
            "Games" to Pair("Generic Games", R.drawable.ic_cat_games),
            "Mail" to Pair("Generic Mail", R.drawable.ic_cat_mail),
            "Maps" to Pair("Generic Maps", R.drawable.ic_cat_maps),
            "Music" to Pair("Generic Music", R.drawable.ic_cat_music),
            "Settings" to Pair("Generic Settings", R.drawable.ic_cat_settings),
            "Social" to Pair("Generic Social", R.drawable.ic_cat_social),
            "Shopping" to Pair("Generic Shopping", R.drawable.ic_cat_shopping),
            "Weather" to Pair("Generic Weather", R.drawable.ic_cat_weather)
        )

        private val bitmapCache = LruCache<String, Bitmap>(200)

        fun getCachedBitmap(key: String): Bitmap? {
            return bitmapCache.get(key)
        }
        fun putCachedBitmap(key: String, bitmap: Bitmap) {
            bitmapCache.put(key, bitmap)
        }
    }

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
                val displayHeight = resources.displayMetrics.heightPixels
                val targetHeight = (displayHeight * 0.90).toInt()
                sheet.layoutParams.height = targetHeight
                behavior.peekHeight = targetHeight
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
                    IconPickerSheetContent(
                        app = app,
                        iconExtractor = iconExtractor,
                        iconPackParser = iconPackParser,
                        iconPackPackage = iconPackPackage,
                        iconPackResources = iconPackResources,
                        onDismiss = { dismiss() },
                        onSelectIcon = { pack, drawable ->
                            onIconSelected(pack, drawable)
                            dismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun IconPickerSheetContent(
    app: AppModel,
    iconExtractor: IconExtractor,
    iconPackParser: IconPackParser,
    iconPackPackage: String?,
    iconPackResources: IconPackResources?,
    onDismiss: () -> Unit,
    onSelectIcon: (String?, String?) -> Unit
) {
    val context = LocalContext.current
    var currentPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val categories = remember { IconPackParser.STANDARD_CATEGORIES }
    var selectedCategoryIndex by remember {
        mutableStateOf(
            if (iconPackPackage != null && categories.contains("Current App")) {
                categories.indexOf("Current App")
            } else 0
        )
    }

    // Top Bar Quick Items
    val topBarItems = remember(app, iconPackPackage, iconPackResources) {
        val items = mutableListOf<TopBarIconItem>()

        // 1. System Default
        val isSystemSelected = app.customIconPack == "system" || (app.customIconPack == null && app.useDefaultIcon)
        items.add(
            TopBarIconItem(
                title = "System",
                subtitle = "Default",
                iconPackPackage = null,
                drawableName = null,
                isSystem = true,
                isSelected = isSystemSelected
            )
        )

        // 2. Icon Pack Default
        if (iconPackPackage != null) {
            val isPackDefaultSelected = app.customIconPack == null && !app.useDefaultIcon
            items.add(
                TopBarIconItem(
                    title = "Pack Default",
                    subtitle = "Themed",
                    iconPackPackage = iconPackPackage,
                    drawableName = iconPackResources?.appFilterMap?.get(app.packageName),
                    isPackDefault = true,
                    isSelected = isPackDefaultSelected
                )
            )

            // 3. Pack Variants
            val variants = iconPackParser.findAppVariants(app.packageName, app.label, iconPackResources)
            for ((idx, variantDrawable) in variants.withIndex()) {
                val isVariantSelected = app.customIconPack == iconPackPackage && app.customIconDrawable == variantDrawable
                val displayTitle = if (variantDrawable.contains("_")) {
                    variantDrawable.substringAfterLast("_")
                } else {
                    "Var ${idx + 1}"
                }
                items.add(
                    TopBarIconItem(
                        title = displayTitle,
                        subtitle = variantDrawable,
                        iconPackPackage = iconPackPackage,
                        drawableName = variantDrawable,
                        isSelected = isVariantSelected
                    )
                )
            }
        }
        items
    }

    // Load active app preview bitmap
    LaunchedEffect(app) {
        val bmp = withContext(Dispatchers.IO) {
            iconExtractor.extractAndRasterizeIcon(
                context,
                app.packageName,
                iconPackPackage,
                iconPackResources,
                144,
                app.useDefaultIcon,
                app.customIconPack,
                app.customIconDrawable
            )
        }
        currentPreviewBitmap = bmp
    }

    // Grid items for active category
    var gridItems by remember { mutableStateOf<List<GridIconItem>>(emptyList()) }
    var isLoadingGrid by remember { mutableStateOf(false) }

    val currentCategory = categories.getOrElse(selectedCategoryIndex) { "All" }

    LaunchedEffect(currentCategory, iconPackPackage, iconPackResources) {
        isLoadingGrid = true
        try {
            val items = withContext(Dispatchers.IO) {
                val list = mutableListOf<GridIconItem>()

                // Add generic category icon
                IconPickerBottomSheet.CATEGORY_BUILTIN_MAP[currentCategory]?.let { (catTitle, resId) ->
                    val resEntryName = context.resources.getResourceEntryName(resId)
                    list.add(
                        GridIconItem(
                            name = catTitle,
                            iconPackPackage = null,
                            drawableName = resEntryName,
                            isBuiltin = true,
                            builtinResId = resId
                        )
                    )
                }

                if (currentCategory == "Current App") {
                    IconPickerBottomSheet.CATEGORY_BUILTIN_MAP.values.forEach { (catTitle, resId) ->
                        val resEntryName = context.resources.getResourceEntryName(resId)
                        list.add(
                            GridIconItem(
                                name = catTitle,
                                iconPackPackage = null,
                                drawableName = resEntryName,
                                isBuiltin = true,
                                builtinResId = resId
                            )
                        )
                    }
                }

                val packDrawables = iconPackParser.getDrawablesForCategory(
                    currentCategory,
                    app.packageName,
                    app.label,
                    iconPackResources
                )
                for (d in packDrawables) {
                    list.add(
                        GridIconItem(
                            name = d,
                            iconPackPackage = iconPackPackage,
                            drawableName = d
                        )
                    )
                }
                list
            }
            gridItems = items
        } finally {
            isLoadingGrid = false
        }
    }

    val filteredGridItems = remember(gridItems, searchQuery) {
        if (searchQuery.isBlank()) {
            gridItems
        } else {
            val q = searchQuery.trim().lowercase()
            gridItems.filter { it.name.lowercase().contains(q) || it.drawableName.lowercase().contains(q) }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(top = 12.dp)
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

            Spacer(modifier = Modifier.height(12.dp))

            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentPreviewBitmap != null) {
                        Image(
                            bitmap = currentPreviewBitmap!!.asImageBitmap(),
                            contentDescription = app.displayLabel,
                            modifier = Modifier.size(38.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Edit Icon",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = app.displayLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                placeholder = { Text("Search icons in category...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Options Row
            Text(
                text = "QUICK OPTIONS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(topBarItems) { item ->
                    TopBarQuickItemCard(
                        item = item,
                        app = app,
                        iconExtractor = iconExtractor,
                        iconPackResources = iconPackResources,
                        onClick = {
                            if (item.isSystem) {
                                onSelectIcon("system", "system")
                            } else if (item.isPackDefault) {
                                onSelectIcon(null, null)
                            } else {
                                onSelectIcon(item.iconPackPackage, item.drawableName)
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Categories Chips
            Text(
                text = "CATEGORIES",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(categories) { index, category ->
                    val isSelected = index == selectedCategoryIndex
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategoryIndex = index },
                        label = { Text(category, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = MaterialTheme.colorScheme.outlineVariant,
                            selectedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Icon Grid
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                if (isLoadingGrid) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (filteredGridItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No icons matching \"$searchQuery\"" else "No icons available in this category",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(filteredGridItems, key = { index, item -> 
                            if (item.isBuiltin) "builtin_${item.builtinResId}_$index" else "${item.iconPackPackage}_${item.drawableName}_$index" 
                        }) { _, item ->
                            GridIconCard(
                                item = item,
                                iconExtractor = iconExtractor,
                                onClick = {
                                    if (item.isBuiltin) {
                                        onSelectIcon("builtin", item.drawableName)
                                    } else {
                                        onSelectIcon(item.iconPackPackage, item.drawableName)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBarQuickItemCard(
    item: TopBarIconItem,
    app: AppModel,
    iconExtractor: IconExtractor,
    iconPackResources: IconPackResources?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var itemBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item) {
        val cacheKey = when {
            item.isSystem -> "system_${app.packageName}"
            item.iconPackPackage != null && item.drawableName != null -> "pack_${item.iconPackPackage}_${item.drawableName}"
            item.isPackDefault && item.iconPackPackage != null -> "default_${item.iconPackPackage}_${app.packageName}"
            else -> null
        }
        val cached = cacheKey?.let { IconPickerBottomSheet.getCachedBitmap(it) }
        if (cached != null) {
            itemBitmap = cached
        } else {
            val bmp = withContext(Dispatchers.IO) {
                if (item.isSystem) {
                    iconExtractor.loadSystemIcon(context, app.packageName, 96)
                } else if (item.iconPackPackage != null && item.drawableName != null) {
                    iconExtractor.loadIconPackDrawable(context, item.iconPackPackage, item.drawableName, 96)
                } else if (item.isPackDefault && item.iconPackPackage != null) {
                    iconExtractor.extractAndRasterizeIcon(
                        context,
                        app.packageName,
                        item.iconPackPackage,
                        iconPackResources,
                        96,
                        false
                    )
                } else null
            }
            if (bmp != null && cacheKey != null) {
                IconPickerBottomSheet.putCachedBitmap(cacheKey, bmp)
            }
            itemBitmap = bmp
        }
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (item.isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (item.isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(90.dp).height(82.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                if (itemBitmap != null) {
                    Image(
                        bitmap = itemBitmap!!.asImageBitmap(),
                        contentDescription = item.title,
                        modifier = Modifier.size(34.dp)
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (item.isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (item.isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun GridIconCard(
    item: GridIconItem,
    iconExtractor: IconExtractor,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var iconBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item) {
        if (!item.isBuiltin && item.iconPackPackage != null && item.drawableName != null) {
            val cacheKey = "${item.iconPackPackage}_${item.drawableName}"
            val cached = IconPickerBottomSheet.getCachedBitmap(cacheKey)
            if (cached != null) {
                iconBitmap = cached
            } else {
                val bmp = withContext(Dispatchers.IO) {
                    iconExtractor.loadIconPackDrawable(context, item.iconPackPackage, item.drawableName, 96)
                }
                if (bmp != null) {
                    IconPickerBottomSheet.putCachedBitmap(cacheKey, bmp)
                    iconBitmap = bmp
                }
            }
        }
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.9f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                if (item.isBuiltin) {
                    Icon(
                        painter = painterResource(id = item.builtinResId),
                        contentDescription = item.name,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap!!.asImageBitmap(),
                        contentDescription = item.name,
                        modifier = Modifier.size(36.dp)
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val displayName = if (item.name.contains("_")) {
                item.name.substringAfterLast("_")
            } else {
                item.name
            }

            Text(
                text = displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
