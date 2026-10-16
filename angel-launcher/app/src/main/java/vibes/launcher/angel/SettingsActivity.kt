package vibes.launcher.angel

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import vibes.launcher.angel.data.AppDatabase
import vibes.launcher.angel.data.AppModel
import vibes.launcher.angel.data.AppRepository
import vibes.launcher.angel.data.NwsWeatherRepository
import vibes.launcher.angel.data.SettingsManager
import vibes.launcher.angel.theme.AngelLauncherTheme
import vibes.launcher.angel.util.WeatherIconHelper
import vibes.launcher.angel.ui.IconExtractor
import vibes.launcher.angel.ui.IconPackParser
import vibes.launcher.angel.util.LocationHelper
import vibes.launcher.angel.util.LocationSearchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var iconPackParser: IconPackParser

    override fun onDestroy() {
        settingsManager.destroy()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        vibes.launcher.angel.util.DeviceUtils.applyOrientationLock(this)
        settingsManager = SettingsManager(this)
        iconPackParser = IconPackParser()
        val appRepository = AppRepository(applicationContext, AppDatabase.getDatabase(this).appDao())
        val iconExtractor = IconExtractor()

        setContent {
            var currentThemeMode by remember { mutableIntStateOf(settingsManager.themeMode) }
            val systemDark = isSystemInDarkTheme()
            val isDark = when (currentThemeMode) {
                1 -> false
                2 -> true
                else -> systemDark
            }

            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.isAppearanceLightStatusBars = !isDark
                    insetsController.isAppearanceLightNavigationBars = !isDark
                }
            }

            AngelLauncherTheme(darkTheme = isDark, dynamicColor = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsNavHost(
                        settingsManager = settingsManager,
                        iconPackParser = iconPackParser,
                        appRepository = appRepository,
                        iconExtractor = iconExtractor,
                        onThemeChanged = { newMode ->
                            currentThemeMode = newMode
                            applyTheme(newMode)
                        },
                        onBackPressed = {
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun applyTheme(mode: Int) {
        val mappedMode = when (mode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mappedMode)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsNavHost(
    settingsManager: SettingsManager,
    iconPackParser: IconPackParser,
    appRepository: AppRepository,
    iconExtractor: IconExtractor,
    onThemeChanged: (Int) -> Unit,
    onBackPressed: () -> Unit
) {
    val initialScreen = (LocalContext.current as? Activity)?.intent?.getStringExtra("screen_id") ?: "main"
    var currentScreen by remember { mutableStateOf(initialScreen) }

    BackHandler {
        when (currentScreen) {
            "colors" -> currentScreen = "theme"
            "main" -> onBackPressed()
            else -> currentScreen = "main"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (currentScreen) {
                            "main" -> "Angel Launcher Settings"
                            "ui" -> "UI & Layout"
                            "theme" -> "Theme & Appearance"
                            "colors" -> "Colors & Opacity"
                            "widget" -> "Launcher Widget"
                            "icon_pack" -> "Icon Pack"
                            "hidden_apps" -> "Hidden Apps"
                            "backup" -> "Backup & Restore"
                            else -> "Settings"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (currentScreen != "main") {
                        IconButton(onClick = {
                            currentScreen = if (currentScreen == "colors") "theme" else "main"
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (currentScreen) {
                "main" -> MainScreen(
                    onNavigateToUi = { currentScreen = "ui" },
                    onNavigateToTheme = { currentScreen = "theme" },
                    onNavigateToWidget = { currentScreen = "widget" },
                    onNavigateToIconPack = { currentScreen = "icon_pack" },
                    onNavigateToHiddenApps = { currentScreen = "hidden_apps" },
                    onNavigateToBackup = { currentScreen = "backup" }
                )
                "ui" -> UiScreen(settingsManager)
                "theme" -> ThemeScreen(
                    settingsManager = settingsManager,
                    onThemeChanged = onThemeChanged,
                    onNavigateToColors = { currentScreen = "colors" }
                )
                "colors" -> ColorsScreen(settingsManager)
                "widget" -> WidgetScreen(settingsManager)
                "icon_pack" -> IconPackScreen(settingsManager, iconPackParser)
                "hidden_apps" -> HiddenAppsScreen(appRepository, iconExtractor)
                "backup" -> BackupRestoreScreen(settingsManager)
            }
        }
    }
}

@Composable
fun MainScreen(
    onNavigateToUi: () -> Unit,
    onNavigateToTheme: () -> Unit,
    onNavigateToWidget: () -> Unit,
    onNavigateToIconPack: () -> Unit,
    onNavigateToHiddenApps: () -> Unit,
    onNavigateToBackup: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    ModernSettingsItem(
                        icon = Icons.Rounded.Tune,
                        title = "UI & Layout",
                        subtitle = "Global scale, icon size, and text scaling",
                        onClick = onNavigateToUi
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                    ModernSettingsItem(
                        icon = Icons.Rounded.Palette,
                        title = "Theme & Appearance",
                        subtitle = "Light, Dark, Monet Dynamic tint, and custom colors",
                        onClick = onNavigateToTheme
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                    ModernSettingsItem(
                        icon = Icons.Rounded.Widgets,
                        title = "Launcher Widget",
                        subtitle = "Live clock, date formats, and NWS weather forecast",
                        onClick = onNavigateToWidget
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                    ModernSettingsItem(
                        icon = Icons.Rounded.Category,
                        title = "Icon Pack",
                        subtitle = "Apply third-party icon packs and manage custom icons",
                        onClick = onNavigateToIconPack
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                    ModernSettingsItem(
                        icon = Icons.Rounded.VisibilityOff,
                        title = "Hidden Apps",
                        subtitle = "Manage and unhide apps hidden from launcher",
                        onClick = onNavigateToHiddenApps
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                    ModernSettingsItem(
                        icon = Icons.Rounded.Backup,
                        title = "Backup & Restore",
                        subtitle = "Export or import launcher settings, colors, and layout",
                        onClick = onNavigateToBackup
                    )
                }
            }
        }
    }
}

@Composable
fun ModernSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun UiScreen(settingsManager: SettingsManager) {
    var globalScale by remember { mutableFloatStateOf(settingsManager.globalScale) }
    var iconScale by remember { mutableFloatStateOf(settingsManager.iconScale) }
    var textScale by remember { mutableFloatStateOf(settingsManager.textScale) }
    var selectedShape by remember { mutableIntStateOf(settingsManager.iconShape) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Real-Time Live Scale Preview Card ---
        item {
            LiveScalePreviewCard(
                globalScale = globalScale,
                iconScale = iconScale,
                textScale = textScale,
                iconShape = selectedShape
            )
        }

        // --- Global Icon Shape Card ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Category,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Global Icon Shape",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Choose a unified shape mask for all app icons",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val shapes = listOf(
                        Triple(SettingsManager.SHAPE_SYSTEM, "Default", "System original"),
                        Triple(SettingsManager.SHAPE_CIRCLE, "Circle", "Round / Pixel"),
                        Triple(SettingsManager.SHAPE_SQUIRCLE, "Squircle", "Smooth curve"),
                        Triple(SettingsManager.SHAPE_ROUNDED_SQUARE, "Rounded", "Soft square"),
                        Triple(SettingsManager.SHAPE_TEARDROP, "Teardrop", "Pebble shape"),
                        Triple(SettingsManager.SHAPE_HEXAGON, "Hexagon", "6-sided polygon")
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        shapes.chunked(2).forEach { rowShapes ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowShapes.forEach { (shapeId, title, subtitle) ->
                                    val isSelected = selectedShape == shapeId
                                    ShapeSelectionCard(
                                        modifier = Modifier.weight(1f),
                                        shapeId = shapeId,
                                        title = title,
                                        subtitle = subtitle,
                                        isSelected = isSelected,
                                        onClick = {
                                            selectedShape = shapeId
                                            settingsManager.iconShape = shapeId
                                        }
                                    )
                                }
                                if (rowShapes.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Scale & Sizing Card ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Scale & Sizing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Global Scale
                    ScaleSliderItem(
                        icon = Icons.Rounded.AspectRatio,
                        title = "Global Scale",
                        value = globalScale,
                        onValueChange = {
                            globalScale = it
                            settingsManager.globalScale = it
                        },
                        onValueChangeFinished = { settingsManager.globalScale = globalScale }
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(20.dp))

                    // Icon Scale
                    ScaleSliderItem(
                        icon = Icons.Rounded.GridOn,
                        title = "Icon Scale",
                        value = iconScale,
                        onValueChange = {
                            iconScale = it
                            settingsManager.iconScale = it
                        },
                        onValueChangeFinished = { settingsManager.iconScale = iconScale }
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(20.dp))

                    // Text Scale
                    ScaleSliderItem(
                        icon = Icons.Rounded.TextFields,
                        title = "Text Scale",
                        value = textScale,
                        onValueChange = {
                            textScale = it
                            settingsManager.textScale = it
                        },
                        onValueChangeFinished = { settingsManager.textScale = textScale }
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveScalePreviewCard(
    globalScale: Float,
    iconScale: Float,
    textScale: Float,
    iconShape: Int
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    
    val baseUDp = screenWidthDp * 0.12f * globalScale
    val iconSizeDp = baseUDp * iconScale
    val textSizeSp = (baseUDp.value * 0.35f * textScale).sp

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Preview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Sample App Row Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(
                        horizontal = (baseUDp.value * 0.5f).coerceIn(8f, 32f).dp,
                        vertical = (baseUDp.value * 0.25f).coerceIn(4f, 20f).dp
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Sample Icon with shape clip
                    Box(
                        modifier = Modifier
                            .size(iconSizeDp.coerceIn(16.dp, 120.dp))
                            .clip(getPreviewShape(iconShape))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Android,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size((iconSizeDp.value * 0.6f).coerceIn(10f, 72f).dp)
                        )
                    }

                    Spacer(modifier = Modifier.width((baseUDp.value * 0.5f).coerceIn(8f, 32f).dp))

                    Text(
                        text = "Sample App",
                        fontSize = (baseUDp.value * 0.35f * textScale).coerceIn(8f, 48f).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private val HexagonShape = androidx.compose.foundation.shape.GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val cx = w / 2f
    moveTo(cx, 0f)
    lineTo(w, h * 0.25f)
    lineTo(w, h * 0.75f)
    lineTo(cx, h)
    lineTo(0f, h * 0.75f)
    lineTo(0f, h * 0.25f)
    close()
}

private fun getShapeForSelector(shapeId: Int): androidx.compose.ui.graphics.Shape {
    return when (shapeId) {
        SettingsManager.SHAPE_CIRCLE -> CircleShape
        SettingsManager.SHAPE_SQUIRCLE -> RoundedCornerShape(percent = 35)
        SettingsManager.SHAPE_ROUNDED_SQUARE -> RoundedCornerShape(percent = 18)
        SettingsManager.SHAPE_TEARDROP -> RoundedCornerShape(topStartPercent = 48, topEndPercent = 48, bottomStartPercent = 48, bottomEndPercent = 8)
        SettingsManager.SHAPE_HEXAGON -> HexagonShape
        else -> RoundedCornerShape(percent = 8)
    }
}

private fun getPreviewShape(shapeId: Int): androidx.compose.ui.graphics.Shape {
    return getShapeForSelector(shapeId)
}

@Composable
private fun ShapeSelectionCard(
    modifier: Modifier = Modifier,
    shapeId: Int,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val shape = getShapeForSelector(shapeId)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shape visual preview box matching theme colors and shape geometry
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = shape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            shape = shape
                        )
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ScaleSliderItem(
    icon: ImageVector,
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = String.format(java.util.Locale.US, "%.2fx", value),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0.5f..2.0f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
fun ThemeScreen(
    settingsManager: SettingsManager,
    onThemeChanged: (Int) -> Unit,
    onNavigateToColors: () -> Unit
) {
    var selectedTheme by remember { mutableIntStateOf(settingsManager.themeMode) }

    val themes = listOf(
        Triple(0, "System Default", Icons.Rounded.BrightnessAuto),
        Triple(1, "Light Theme", Icons.Rounded.LightMode),
        Triple(2, "Dark Theme", Icons.Rounded.DarkMode)
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Base Theme Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    themes.forEachIndexed { index, (mode, title, icon) ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedTheme = mode
                                    settingsManager.themeMode = mode
                                    onThemeChanged(mode)
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (selectedTheme == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selectedTheme == mode) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            RadioButton(
                                selected = selectedTheme == mode,
                                onClick = {
                                    selectedTheme = mode
                                    settingsManager.themeMode = mode
                                    onThemeChanged(mode)
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }
        }

        item {
            var selectedNavBarMode by remember { mutableIntStateOf(settingsManager.navBarThemeMode) }
            val navBarModes = listOf(
                Pair(0, "Transparent"),
                Pair(1, "Monet Secondary Color")
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "System Navigation Bar Style",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    navBarModes.forEachIndexed { index, (mode, title) ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedNavBarMode = mode
                                    settingsManager.navBarThemeMode = mode
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selectedNavBarMode == mode) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            RadioButton(
                                selected = selectedNavBarMode == mode,
                                onClick = {
                                    selectedNavBarMode = mode
                                    settingsManager.navBarThemeMode = mode
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    ModernSettingsItem(
                        icon = Icons.Rounded.ColorLens,
                        title = "Colors & Opacity",
                        subtitle = "Monet dynamic tint, text, search, and scrubber colors",
                        onClick = onNavigateToColors
                    )
                }
            }
        }
    }
}

/** One row of the Colors screen: a color + opacity pair on [SettingsManager] and how to reset it. */
private class ColorSpec(
    val title: String,
    val description: String,
    val getColor: (SettingsManager) -> Int,
    val setColor: (SettingsManager, Int) -> Unit,
    val getOpacity: (SettingsManager) -> Float,
    val setOpacity: (SettingsManager, Float) -> Unit,
    val reset: (SettingsManager) -> Unit,
    val hasMonet: Boolean = false
)

private val colorSpecs = listOf(
    ColorSpec(
        "App List & Scrubber Tint",
        "Background tint applied during State 1 (App List View)",
        getColor = { it.appListCustomColor }, setColor = { sm, v -> sm.appListCustomColor = v },
        getOpacity = { it.appListTintOpacity }, setOpacity = { sm, v -> sm.appListTintOpacity = v },
        reset = { it.resetAppListTint() },
        hasMonet = true
    ),
    ColorSpec(
        "App Names & Section Headers",
        "Text color and opacity for installed apps and alphabet headers",
        getColor = { it.appTextColor }, setColor = { sm, v -> sm.appTextColor = v },
        getOpacity = { it.appTextOpacity }, setOpacity = { sm, v -> sm.appTextOpacity = v },
        reset = { it.resetAppTextColor() }
    ),
    ColorSpec(
        "Scrubber Alphabet Letters",
        "Color and opacity for right-edge alphabet index and wave preview",
        getColor = { it.scrubberTextColor }, setColor = { sm, v -> sm.scrubberTextColor = v },
        getOpacity = { it.scrubberTextOpacity }, setOpacity = { sm, v -> sm.scrubberTextOpacity = v },
        reset = { it.resetScrubberTextColor() }
    ),
    ColorSpec(
        "Search Bar Pill Surface",
        "Background surface color and transparency of the search bar pill",
        getColor = { it.searchBarColor }, setColor = { sm, v -> sm.searchBarColor = v },
        getOpacity = { it.searchBarOpacity }, setOpacity = { sm, v -> sm.searchBarOpacity = v },
        reset = { it.resetSearchBarColor() }
    ),
    ColorSpec(
        "Search Overlay Dimming",
        "Dim background scrim color behind active search view",
        getColor = { it.searchDimColor }, setColor = { sm, v -> sm.searchDimColor = v },
        getOpacity = { it.searchDimOpacity }, setOpacity = { sm, v -> sm.searchDimOpacity = v },
        reset = { it.resetSearchDimColor() }
    ),
    ColorSpec(
        "Search Floating Button (FAB)",
        "Floating search button background color and opacity",
        getColor = { it.searchFabColor }, setColor = { sm, v -> sm.searchFabColor = v },
        getOpacity = { it.searchFabOpacity }, setOpacity = { sm, v -> sm.searchFabOpacity = v },
        reset = { it.resetSearchFabColor() }
    ),
    ColorSpec(
        "Header Widget Clock & Date Text",
        "Text color and opacity for clock digits, AM/PM, and date",
        getColor = { it.clockTextColor }, setColor = { sm, v -> sm.clockTextColor = v },
        getOpacity = { it.clockTextOpacity }, setOpacity = { sm, v -> sm.clockTextOpacity = v },
        reset = { it.resetClockTextColor() }
    ),
    ColorSpec(
        "Header Widget Weather & Forecast Text",
        "Text color and opacity for current weather location/temp and 5-day daily forecast",
        getColor = { it.weatherTextColor }, setColor = { sm, v -> sm.weatherTextColor = v },
        getOpacity = { it.weatherTextOpacity }, setOpacity = { sm, v -> sm.weatherTextOpacity = v },
        reset = { it.resetWeatherTextColor() }
    ),
    ColorSpec(
        "Edit Mode Top Banner",
        "Background color and opacity for top banner during Edit Home Screen and Edit Layout view",
        getColor = { it.editModeBannerColor }, setColor = { sm, v -> sm.editModeBannerColor = v },
        getOpacity = { it.editModeBannerOpacity }, setOpacity = { sm, v -> sm.editModeBannerOpacity = v },
        reset = { it.resetEditModeBannerColor() }
    )
)

@Composable
fun ColorsScreen(settingsManager: SettingsManager) {
    val context = LocalContext.current
    // SettingsManager is the source of truth; bumping this re-reads it after every write.
    var revision by remember { mutableIntStateOf(0) }
    fun edit(change: () -> Unit) {
        change()
        revision++
    }

    var hexDialogTarget by remember { mutableStateOf<ColorSpec?>(null) }
    var customHexInput by remember { mutableStateOf("") }

    hexDialogTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { hexDialogTarget = null },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Colorize,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "Custom Hex Color",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter a 6-digit hex color code (e.g. #006874 or #FFFFFF):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customHexInput,
                        onValueChange = { customHexInput = it },
                        label = { Text("Hex Color Code") },
                        placeholder = { Text("#FFFFFF") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = try {
                            android.graphics.Color.parseColor(
                                if (customHexInput.startsWith("#")) customHexInput else "#$customHexInput"
                            )
                        } catch (e: Exception) {
                            Toast.makeText(context, "Invalid hex color: \"$customHexInput\". Use format #RRGGBB.", Toast.LENGTH_LONG).show()
                            null
                        }
                        if (parsed != null) {
                            edit { target.setColor(settingsManager, parsed) }
                            hexDialogTarget = null
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { hexDialogTarget = null },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Launcher Color Scheme",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(
                    onClick = { edit { settingsManager.resetAllColors() } },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset All")
                }
            }
        }

        items(colorSpecs) { spec ->
            // Reading `revision` makes this item recompose after any edit.
            @Suppress("UNUSED_EXPRESSION") revision
            val color = spec.getColor(settingsManager)
            ModernColorSettingCard(
                title = spec.title,
                description = spec.description,
                selectedColor = color,
                selectedOpacity = spec.getOpacity(settingsManager),
                isMonetSupported = spec.hasMonet,
                isMonetEnabled = spec.hasMonet && settingsManager.appListMonetEnabled,
                onMonetToggle = if (spec.hasMonet) { enabled ->
                    edit { settingsManager.appListMonetEnabled = enabled }
                } else null,
                onColorSelected = { edit { spec.setColor(settingsManager, it) } },
                onOpacityChanged = { edit { spec.setOpacity(settingsManager, it) } },
                onCustomHexClick = {
                    customHexInput = String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and color)
                    hexDialogTarget = spec
                },
                onReset = { edit { spec.reset(settingsManager) } }
            )
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun ModernColorSettingCard(
    title: String,
    description: String,
    selectedColor: Int,
    selectedOpacity: Float,
    isMonetSupported: Boolean = false,
    isMonetEnabled: Boolean = false,
    onMonetToggle: ((Boolean) -> Unit)? = null,
    onColorSelected: (Int) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onCustomHexClick: () -> Unit,
    onReset: () -> Unit
) {
    val presets = listOf(
        0xFFFFFFFF.toInt(),
        0xFF000000.toInt(),
        0xFF2C2C2C.toInt(),
        0xFF1E88E5.toInt(),
        0xFF00897B.toInt(),
        0xFF43A047.toInt(),
        0xFFFFB300.toInt(),
        0xFFF4511E.toInt(),
        0xFFE53935.toInt(),
        0xFF8E24AA.toInt()
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Color Preview Swatch
                val previewColor = Color(
                    red = android.graphics.Color.red(selectedColor) / 255f,
                    green = android.graphics.Color.green(selectedColor) / 255f,
                    blue = android.graphics.Color.blue(selectedColor) / 255f,
                    alpha = selectedOpacity.coerceIn(0f, 1f)
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(previewColor)
                            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    )
                }
            }

            if (isMonetSupported && onMonetToggle != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMonetToggle(!isMonetEnabled) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Material You (Monet)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Extract dynamic palette from system wallpaper",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isMonetEnabled,
                            onCheckedChange = onMonetToggle
                        )
                    }
                }
            }

            if (!isMonetSupported || !isMonetEnabled) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Color Palette",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(presets) { preset ->
                        val isSelected = preset == selectedColor
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(preset).copy(alpha = 1.0f))
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                                .clickable { onColorSelected(preset) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = if (preset == 0xFFFFFFFF.toInt()) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    item {
                        OutlinedButton(
                            onClick = onCustomHexClick,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("HEX", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Opacity Slider
            val opacityPercent = (selectedOpacity * 100).toInt()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Opacity",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "$opacityPercent%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Slider(
                value = selectedOpacity,
                onValueChange = onOpacityChanged,
                valueRange = 0f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            // Reset Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onReset,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset")
                }
            }
        }
    }
}

@Composable
fun IconPackScreen(settingsManager: SettingsManager, iconPackParser: IconPackParser) {
    var selectedPack by remember { mutableStateOf(settingsManager.iconPackPackage) }
    val context = LocalContext.current
    var packs by remember { mutableStateOf(iconPackParser.getAvailableIconPacks(context)) }
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Installed Icon Packs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // System Default Pack
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                selectedPack = null
                                settingsManager.iconPackPackage = null
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Android,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "System Default",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selectedPack == null) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Original app icons provided by operating system",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RadioButton(
                            selected = selectedPack == null,
                            onClick = {
                                selectedPack = null
                                settingsManager.iconPackPackage = null
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    packs.forEach { pack ->
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedPack = pack.packageName
                                    settingsManager.iconPackPackage = pack.packageName
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Apps,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pack.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selectedPack == pack.packageName) FontWeight.SemiBold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = pack.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            RadioButton(
                                selected = selectedPack == pack.packageName,
                                onClick = {
                                    selectedPack = pack.packageName
                                    settingsManager.iconPackPackage = pack.packageName
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    isRefreshing = true
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val freshPacks = iconPackParser.getAvailableIconPacks(context)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            packs = freshPacks
                            isRefreshing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                enabled = !isRefreshing
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scanning Icon Packs…")
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Refresh Icon Packs List")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun WidgetScreen(settingsManager: SettingsManager) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showWidget by remember { mutableStateOf(settingsManager.showWidget) }
    var clock24Hour by remember { mutableIntStateOf(settingsManager.clock24Hour) }
    var showAmPm by remember { mutableStateOf(settingsManager.showAmPm) }
    var showDate by remember { mutableStateOf(settingsManager.showDate) }
    var dateFormat by remember { mutableStateOf(settingsManager.dateFormat) }
    var showWeather by remember { mutableStateOf(settingsManager.showWeather) }
    var show5DayForecast by remember { mutableStateOf(settingsManager.show5DayForecast) }

    var useDeviceLocation by remember { mutableStateOf(settingsManager.weatherUseDeviceLocation) }
    var searchQuery by remember { mutableStateOf(settingsManager.weatherCustomLocationQuery) }
    var customCity by remember { mutableStateOf(settingsManager.weatherCustomCity) }
    var customState by remember { mutableStateOf(settingsManager.weatherCustomState) }
    var customLat by remember { mutableDoubleStateOf(settingsManager.weatherCustomLat) }
    var customLon by remember { mutableDoubleStateOf(settingsManager.weatherCustomLon) }

    var searchResults by remember { mutableStateOf<List<LocationSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var isRefreshingWeather by remember { mutableStateOf(false) }
    var cachedWeather by remember { mutableStateOf(settingsManager.getCachedWeatherData()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            useDeviceLocation = true
            settingsManager.weatherUseDeviceLocation = true
            Toast.makeText(context, "Location permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Auto-search user location when input is typed in search box
    LaunchedEffect(searchQuery, useDeviceLocation) {
        if (!useDeviceLocation && searchQuery.trim().length >= 2) {
            delay(400) // Debounce
            isSearching = true
            val results = LocationHelper.searchLocation(context, searchQuery)
            searchResults = results
            isSearching = false
        } else {
            searchResults = emptyList()
            isSearching = false
        }
    }

    val clockModes = listOf(
        Triple(0, "System Default", Icons.Rounded.Schedule),
        Triple(1, "12-Hour format (e.g. 5:30 PM)", Icons.Rounded.AccessTime),
        Triple(2, "24-Hour format (e.g. 17:30)", Icons.Rounded.Timelapse)
    )

    val dateFormats = listOf(
        "EEE, MMM d" to "Short (Wed, Jul 29)",
        "EEEE, MMMM d" to "Full (Wednesday, July 29)",
        "M/d/yyyy" to "Numeric (7/29/2026)",
        "d/M/yyyy" to "Day-first (29/7/2026)",
        "yyyy-MM-dd" to "ISO (2026-07-29)"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Widget Visibility Master Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val newValue = !showWidget
                                showWidget = newValue
                                settingsManager.showWidget = newValue
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Widgets,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show Launcher Widget",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Display live clock, date, and weather header",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showWidget,
                            onCheckedChange = {
                                showWidget = it
                                settingsManager.showWidget = it
                            }
                        )
                    }
                }
            }
        }

        // 2. Clock & Date Settings Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Clock & Time Format",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    clockModes.forEachIndexed { index, (mode, title, icon) ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    clock24Hour = mode
                                    settingsManager.clock24Hour = mode
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (clock24Hour == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (clock24Hour == mode) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            RadioButton(
                                selected = clock24Hour == mode,
                                onClick = {
                                    clock24Hour = mode
                                    settingsManager.clock24Hour = mode
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val newValue = !showAmPm
                                showAmPm = newValue
                                settingsManager.showAmPm = newValue
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show AM / PM Indicator",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Applies to 12-hour clock format",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showAmPm,
                            onCheckedChange = {
                                showAmPm = it
                                settingsManager.showAmPm = it
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Calendar Date Display",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val newValue = !showDate
                                showDate = newValue
                                settingsManager.showDate = newValue
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show Date",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Display calendar date beneath the clock",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showDate,
                            onCheckedChange = {
                                showDate = it
                                settingsManager.showDate = it
                            }
                        )
                    }

                    AnimatedVisibility(
                        visible = showDate,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Date Format",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            dateFormats.forEach { (pattern, title) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            dateFormat = pattern
                                            settingsManager.dateFormat = pattern
                                        }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = dateFormat == pattern,
                                        onClick = {
                                            dateFormat = pattern
                                            settingsManager.dateFormat = pattern
                                        },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Weather Settings Card (National Weather Service API)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Weather & Forecast (NWS API)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val newValue = !showWeather
                                showWeather = newValue
                                settingsManager.showWeather = newValue
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show Weather & Forecast",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Live updates and 5-day daily forecast",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showWeather,
                            onCheckedChange = {
                                showWeather = it
                                settingsManager.showWeather = it
                            }
                        )
                    }

                    AnimatedVisibility(
                        visible = showWeather,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Column {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            // 5-Day Forecast toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        val newValue = !show5DayForecast
                                        show5DayForecast = newValue
                                        settingsManager.show5DayForecast = newValue
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "5-Day Forecast",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Show extended 5-day weather forecast card",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = show5DayForecast,
                                    onCheckedChange = {
                                        show5DayForecast = it
                                        settingsManager.show5DayForecast = it
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            var contactEmail by remember { mutableStateOf(settingsManager.nwsContactEmail) }
                            OutlinedTextField(
                                value = contactEmail,
                                onValueChange = {
                                    contactEmail = it
                                    settingsManager.nwsContactEmail = it
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Contact email (required for weather)") },
                                supportingText = {
                                    Text("The National Weather Service requires API clients to identify themselves with a contact address. Weather is disabled until you enter one. It is sent only in the weather request's User-Agent header.")
                                },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Email
                                )
                            )

                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Location Source",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Device Location
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (!LocationHelper.hasLocationPermission(context)) {
                                            permissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                                )
                                            )
                                        } else {
                                            useDeviceLocation = true
                                            settingsManager.weatherUseDeviceLocation = true
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MyLocation,
                                    contentDescription = null,
                                    tint = if (useDeviceLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Device Location (GPS / Network)",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (useDeviceLocation) FontWeight.SemiBold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val hasPerm = LocationHelper.hasLocationPermission(context)
                                    Text(
                                        text = if (hasPerm) "Permission active" else "Tap to grant location permission",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (hasPerm) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }
                                RadioButton(
                                    selected = useDeviceLocation,
                                    onClick = {
                                        if (!LocationHelper.hasLocationPermission(context)) {
                                            permissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                                )
                                            )
                                        } else {
                                            useDeviceLocation = true
                                            settingsManager.weatherUseDeviceLocation = true
                                        }
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }

                            // Custom Location
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        useDeviceLocation = false
                                        settingsManager.weatherUseDeviceLocation = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LocationSearching,
                                    contentDescription = null,
                                    tint = if (!useDeviceLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Custom Location",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (!useDeviceLocation) FontWeight.SemiBold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Specify city name, zip code, or address",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                RadioButton(
                                    selected = !useDeviceLocation,
                                    onClick = {
                                        useDeviceLocation = false
                                        settingsManager.weatherUseDeviceLocation = false
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }

                            if (!useDeviceLocation) {
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = {
                                        searchQuery = it
                                        settingsManager.weatherCustomLocationQuery = it
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    label = { Text("Search City, State, or Zip Code") },
                                    placeholder = { Text("e.g. Crossville, TN or 38555") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    trailingIcon = {
                                        if (isSearching) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                )

                                if (searchResults.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(
                                                text = "Select Location:",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            searchResults.forEach { result ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            customCity = result.city
                                                            customState = result.state
                                                            customLat = result.latitude
                                                            customLon = result.longitude
                                                            searchQuery = result.displayName

                                                            settingsManager.weatherCustomCity = result.city
                                                            settingsManager.weatherCustomState = result.state
                                                            settingsManager.weatherCustomLat = result.latitude
                                                            settingsManager.weatherCustomLon = result.longitude
                                                            settingsManager.weatherCustomLocationQuery = result.displayName

                                                            searchResults = emptyList()
                                                            Toast.makeText(context, "Location set to ${result.displayName}", Toast.LENGTH_SHORT).show()

                                                            // Refresh forecast
                                                            coroutineScope.launch {
                                                                isRefreshingWeather = true
                                                                try {
                                                                    val repo = NwsWeatherRepository(context)
                                                                    val res = repo.getWeatherData(result.latitude, result.longitude)
                                                                    if (res.isSuccess) {
                                                                        val wData = res.getOrThrow()
                                                                        settingsManager.saveCachedWeatherData(wData)
                                                                        cachedWeather = wData
                                                                        Toast.makeText(context, "Weather updated: ${wData.currentTemp}°", Toast.LENGTH_SHORT).show()
                                                                    } else {
                                                                        val err = res.exceptionOrNull()?.message ?: "Unknown error"
                                                                        Toast.makeText(context, "Weather fetch failed: $err", Toast.LENGTH_LONG).show()
                                                                    }
                                                                } finally {
                                                                    isRefreshingWeather = false
                                                                }
                                                            }
                                                        }
                                                        .padding(vertical = 8.dp, horizontal = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.LocationOn,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = result.displayName,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Selected: $customCity, $customState (Lat: ${String.format(java.util.Locale.US, "%.4f", customLat)}, Lon: ${String.format(java.util.Locale.US, "%.4f", customLon)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Refresh Weather Now Button
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        isRefreshingWeather = true
                                        try {
                                            var lat = customLat
                                            var lon = customLon
                                            if (useDeviceLocation) {
                                                val devLoc = LocationHelper.getLastKnownLocation(context)
                                                if (devLoc != null) {
                                                    lat = devLoc.latitude
                                                    lon = devLoc.longitude
                                                }
                                            }
                                            val repo = NwsWeatherRepository(context)
                                            val res = repo.getWeatherData(lat, lon)
                                            if (res.isSuccess) {
                                                val wData = res.getOrThrow()
                                                settingsManager.saveCachedWeatherData(wData)
                                                cachedWeather = wData
                                                Toast.makeText(context, "Weather updated for ${wData.cityName}: ${wData.currentTemp}°", Toast.LENGTH_SHORT).show()
                                            } else {
                                                val err = res.exceptionOrNull()?.message ?: "Unknown error"
                                                Toast.makeText(context, "Failed to fetch weather: $err", Toast.LENGTH_LONG).show()
                                            }
                                        } finally {
                                            isRefreshingWeather = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                enabled = !isRefreshingWeather
                            ) {
                                if (isRefreshingWeather) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Fetching NWS Forecast...")
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Refresh Weather Now")
                                }
                            }

                            // Cached Status Card
                            if (cachedWeather != null) {
                                val weather = cachedWeather!!
                                Spacer(modifier = Modifier.height(12.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Rounded.WbSunny,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Current Forecast Data",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "${weather.currentConditionEmoji} ${weather.cityName}, ${weather.state}: ${weather.currentTemp}° (${weather.currentShortForecast})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "5-day: " + weather.dailyForecasts.joinToString(" | ") { "${it.dayName}: ${it.conditionEmoji} ${it.highTemp}°/${it.lowTemp}°" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Weather Icon Pack Card
        item {
            val weatherPacks = remember { WeatherIconHelper.getAvailableWeatherIconPacks(context) }
            var selectedWeatherPack by remember { mutableStateOf(settingsManager.weatherIconPackPackage) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Weather Icon Pack",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Supports Breezy Weather, Geometric Weather, & Chronus icon packs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Default Built-in Option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                selectedWeatherPack = null
                                settingsManager.weatherIconPackPackage = null
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Built-in Icons (Default)",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selectedWeatherPack == null) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        RadioButton(
                            selected = selectedWeatherPack == null,
                            onClick = {
                                selectedWeatherPack = null
                                settingsManager.weatherIconPackPackage = null
                            }
                        )
                    }

                    if (weatherPacks.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No custom weather icon packs detected on device. Install any Breezy Weather or Geometric Weather icon pack APK to use custom icons.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    } else {
                        weatherPacks.forEach { pack ->
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        selectedWeatherPack = pack.packageName
                                        settingsManager.weatherIconPackPackage = pack.packageName
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = pack.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (selectedWeatherPack == pack.packageName) FontWeight.Bold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = pack.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                RadioButton(
                                    selected = selectedWeatherPack == pack.packageName,
                                    onClick = {
                                        selectedWeatherPack = pack.packageName
                                        settingsManager.weatherIconPackPackage = pack.packageName
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Reset Button Card
        item {
            OutlinedButton(
                onClick = {
                    settingsManager.resetWidgetSettings()
                    settingsManager.weatherCachedJson = null
                    showWidget = settingsManager.showWidget
                    clock24Hour = settingsManager.clock24Hour
                    showAmPm = settingsManager.showAmPm
                    showDate = settingsManager.showDate
                    dateFormat = settingsManager.dateFormat
                    showWeather = settingsManager.showWeather
                    show5DayForecast = settingsManager.show5DayForecast
                    useDeviceLocation = settingsManager.weatherUseDeviceLocation
                    searchQuery = settingsManager.weatherCustomLocationQuery
                    customCity = settingsManager.weatherCustomCity
                    customState = settingsManager.weatherCustomState
                    customLat = settingsManager.weatherCustomLat
                    customLon = settingsManager.weatherCustomLon
                    cachedWeather = null
                    Toast.makeText(context, "Widget settings reset to defaults", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset Widget Settings")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun HiddenAppsScreen(
    appRepository: AppRepository,
    iconExtractor: IconExtractor
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var hiddenApps by remember { mutableStateOf<List<AppModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    val reloadHiddenApps: () -> Unit = {
        coroutineScope.launch {
            isLoading = true
            hiddenApps = appRepository.getHiddenApps()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        reloadHiddenApps()
    }

    val filteredApps = remember(hiddenApps, searchQuery) {
        if (searchQuery.isBlank()) hiddenApps
        else hiddenApps.filter {
            it.displayLabel.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status & Unhide All Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Hidden Apps",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (hiddenApps.isEmpty()) "No apps currently hidden" else "${hiddenApps.size} hidden ${if (hiddenApps.size == 1) "app" else "apps"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (hiddenApps.isNotEmpty()) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        appRepository.unhideAllApps()
                                        Toast.makeText(context, "All apps unhidden", Toast.LENGTH_SHORT).show()
                                        reloadHiddenApps()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Unhide All", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    if (hiddenApps.size > 2) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter hidden apps…") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Clear",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (hiddenApps.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Hidden Apps",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Long-press any app in the launcher and choose 'Hide from App List' to hide it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(filteredApps, key = { it.packageName }) { app ->
                HiddenAppListItem(
                    app = app,
                    iconExtractor = iconExtractor,
                    onUnhide = {
                        coroutineScope.launch {
                            appRepository.unhideApp(app.packageName)
                            Toast.makeText(context, "Restored ${app.displayLabel}", Toast.LENGTH_SHORT).show()
                            reloadHiddenApps()
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun HiddenAppListItem(
    app: AppModel,
    iconExtractor: IconExtractor,
    onUnhide: () -> Unit
) {
    val context = LocalContext.current
    var iconBitmap by remember(app.packageName) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(app.packageName) {
        iconBitmap = iconExtractor.loadSystemIcon(context, app.packageName, 128)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                if (iconBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = iconBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Category,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.displayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onUnhide,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Unhide", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun BackupRestoreScreen(settingsManager: SettingsManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            isWorking = true
            scope.launch {
                val backupManager = vibes.launcher.angel.util.BackupManager(context)
                val result = backupManager.exportBackup(uri)
                isWorking = false
                if (result.isSuccess) {
                    Toast.makeText(context, "Backup exported successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Export failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            isWorking = true
            scope.launch {
                val backupManager = vibes.launcher.angel.util.BackupManager(context)
                val result = backupManager.importRestore(uri)
                isWorking = false
                if (result.isSuccess) {
                    Toast.makeText(context, "Settings & Layout restored successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Restore failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Reset All Settings?") },
            text = { Text("This will restore all launcher settings, colors, widget padding, and layout customizations to factory defaults. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        settingsManager.resetAllColors()
                        Toast.makeText(context, "All settings reset to default", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Reset All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    // Export Backup
                    ModernSettingsItem(
                        icon = Icons.Rounded.FileUpload,
                        title = "Export Backup",
                        subtitle = "Save launcher settings, colors, and layout to a JSON file",
                        onClick = {
                            if (!isWorking) {
                                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                                exportLauncher.launch("angel_launcher_backup_$timestamp.json")
                            }
                        }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    // Import Restore
                    ModernSettingsItem(
                        icon = Icons.Rounded.FileDownload,
                        title = "Restore Backup",
                        subtitle = "Load launcher settings and app configuration from a JSON file",
                        onClick = {
                            if (!isWorking) {
                                importLauncher.launch(arrayOf("application/json", "*/*"))
                            }
                        }
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                )
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    ModernSettingsItem(
                        icon = Icons.Rounded.RestartAlt,
                        title = "Reset All Settings",
                        subtitle = "Restore all colors, opacities, and layout options to defaults",
                        onClick = { showResetDialog = true }
                    )
                }
            }
        }
    }
}

