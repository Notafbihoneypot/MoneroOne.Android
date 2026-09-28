package one.monero.moneroone.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

/**
 * Tells the system which Appearance the app uses, so that the Android 12+
 * splash screen agrees with the app.
 *
 * AppCompatDelegate.setDefaultNightMode changes the configuration inside the
 * app only. AppCompat never tells the system, and the system draws the splash
 * before the app runs, so the splash followed the phone's dark mode and not
 * the in-app choice. The system keeps the per-app mode across launches. A call
 * with the mode that the system already has changes nothing.
 *
 * @param nightMode an AppCompatDelegate night mode, as saved in "theme_mode".
 */
fun setSystemNightMode(context: Context, nightMode: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val mode = when (nightMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> UiModeManager.MODE_NIGHT_NO
            AppCompatDelegate.MODE_NIGHT_YES -> UiModeManager.MODE_NIGHT_YES
            // Follow the phone (for an app, AUTO removes the app's own mode).
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode)
    }
}

/**
 * This context with its night mode set to [night], so its resources pick the
 * -night variants (or the default ones) whatever the app's Appearance is.
 * For art that sits on a fixed background: the white QR disc takes the day
 * logo, the dark widgets take the night logo.
 */
fun Context.withNightMode(night: Boolean): Context {
    val config = Configuration(resources.configuration)
    config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    return createConfigurationContext(config)
}
