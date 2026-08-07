package vibes.launcher.angel.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vibes.launcher.angel.data.SettingsManager
import vibes.launcher.angel.theme.AngelLauncherTheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class EditLayoutSpacingBottomSheet(
    private val settingsManager: SettingsManager,
    private val hasActiveWidget: Boolean,
    private val onLayoutChanged: () -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0f)
        dialog.setOnShowListener { dialogInterface ->
            val d = dialogInterface as BottomSheetDialog
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                it.background = ColorDrawable(Color.TRANSPARENT)
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
                val systemDark = isSystemInDarkTheme()
                val isDark = when (settingsManager.themeMode) {
                    1 -> false
                    2 -> true
                    else -> systemDark
                }
                AngelLauncherTheme(darkTheme = isDark) {
                    EditLayoutSpacingContent(
                        settingsManager = settingsManager,
                        hasActiveWidget = hasActiveWidget,
                        onLayoutChanged = onLayoutChanged
                    )
                }
            }
        }
    }
}

@Composable
private fun EditLayoutSpacingContent(
    settingsManager: SettingsManager,
    hasActiveWidget: Boolean,
    onLayoutChanged: () -> Unit
) {
    var topPaddingDp by remember { mutableIntStateOf(settingsManager.widgetTopPaddingDp) }
    var bottomPaddingDp by remember { mutableIntStateOf(settingsManager.widgetBottomPaddingDp) }
    var widgetHeightDp by remember { mutableIntStateOf(settingsManager.userWidgetHeightDp) }

    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // Drag handle pill
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    .align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Header row with Reset action button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Edit Layout Spacing",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = {
                        topPaddingDp = 0
                        bottomPaddingDp = 0
                        settingsManager.widgetTopPaddingDp = 0
                        settingsManager.widgetBottomPaddingDp = 0
                        onLayoutChanged()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reset Spacing",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Single Compact Card for Sliders
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    // Top Spacing
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Top Spacing",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$topPaddingDp dp",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = topPaddingDp.toFloat(),
                        onValueChange = { newValue ->
                            val intVal = newValue.toInt()
                            topPaddingDp = intVal
                            settingsManager.widgetTopPaddingDp = intVal
                            onLayoutChanged()
                        },
                        valueRange = 0f..160f,
                        steps = 32,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Bottom Spacing
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Bottom Spacing",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$bottomPaddingDp dp",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = bottomPaddingDp.toFloat(),
                        onValueChange = { newValue ->
                            val intVal = newValue.toInt()
                            bottomPaddingDp = intVal
                            settingsManager.widgetBottomPaddingDp = intVal
                            onLayoutChanged()
                        },
                        valueRange = 0f..160f,
                        steps = 32,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                    )

                    if (hasActiveWidget) {
                        Spacer(modifier = Modifier.height(6.dp))

                        // User Widget Height
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Widget Height",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (widgetHeightDp == 0) "Auto" else "$widgetHeightDp dp",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = (if (widgetHeightDp == 0) 140 else widgetHeightDp).toFloat(),
                            enabled = widgetHeightDp != 0,
                            onValueChange = { newValue ->
                                val intVal = newValue.toInt()
                                widgetHeightDp = intVal
                                settingsManager.userWidgetHeightDp = intVal
                                onLayoutChanged()
                            },
                            valueRange = 60f..380f,
                            steps = 32,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
