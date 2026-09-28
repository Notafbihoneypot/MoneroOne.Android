package one.monero.moneroone.ui.screens.settings

import one.monero.moneroone.core.locale.tr
import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.monero.moneroone.ui.components.GlassCard
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.MoneroTheme
import one.monero.moneroone.ui.theme.setSystemNightMode

enum class ThemeOption(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val nightMode: Int,
    // Value on the Settings > Appearance row, worded as the iOS row.
    val rowValue: String
) {
    SYSTEM(
        "System",
        "Match device settings",
        Icons.Default.PhoneAndroid,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        "System"
    ),
    LIGHT(
        "Light",
        "Always use light theme",
        Icons.Default.LightMode,
        AppCompatDelegate.MODE_NIGHT_NO,
        "Light"
    ),
    DARK(
        "Dark",
        "Always use dark theme",
        Icons.Default.DarkMode,
        AppCompatDelegate.MODE_NIGHT_YES,
        "Dark"
    );

    companion object {
        // The option for a saved "theme_mode" value. An unknown value follows
        // the system, as MainActivity does.
        fun fromNightMode(nightMode: Int): ThemeOption =
            entries.find { it.nightMode == nightMode } ?: SYSTEM
    }
}

@Composable
fun ThemeScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("monero_wallet", Context.MODE_PRIVATE) }

    var selectedTheme by remember {
        val savedMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        mutableStateOf(ThemeOption.fromNightMode(savedMode))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MoneroTheme.colors.bgGrouped)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back")
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = tr("Appearance"),
                style = MaterialTheme.typography.headlineSmall
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = tr("Choose your preferred app appearance."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // One grouped card, as an iOS inset grouped list
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, shadow = false) {
        Column {
            ThemeOption.entries.forEachIndexed { index, theme ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                ThemeItem(
                    theme = theme,
                    isSelected = theme == selectedTheme,
                    onClick = {
                        selectedTheme = theme
                        prefs.edit().putInt("theme_mode", theme.nightMode).apply()
                        AppCompatDelegate.setDefaultNightMode(theme.nightMode)
                        // Recreate activity to apply theme immediately
                        (context as? Activity)?.recreate()
                        // AppCompat never tells the system, so the splash followed
                        // the phone. Called after recreate(): the system's own
                        // relaunch for this change then joins the pending one.
                        setSystemNightMode(context, theme.nightMode)
                    }
                )
            }
        }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ThemeItem(
    theme: ThemeOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = theme.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tr(theme.title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = tr(theme.subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // The check marks the selection; the row text stays in the label color.
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = tr("Selected"),
                tint = MoneroOrange,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
