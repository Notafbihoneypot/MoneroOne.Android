package one.monero.moneroone.core.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

/**
 * Whether the app may post notifications, and two ways to ask.
 *
 * [enabled] follows the system: it is read again after the permission
 * dialog closes and on every resume, since the user may change it in
 * Settings. Before Android 13 there is no dialog, and notifications can
 * only be turned off (and back on) in Settings.
 *
 * Destructures as (enabled, request), as the old pair did.
 */
@Stable
class NotificationAccess internal constructor(
    private val state: MutableState<Boolean>,
    /**
     * Asks the system, which shows its dialog while it still may. When it
     * no longer may, the request returns "denied" and shows nothing.
     */
    val request: () -> Unit,
    /**
     * The system dialog when the system still shows one, else the app's
     * notification settings (iOS PriceAlertsView's Enable).
     */
    val enable: () -> Unit
) {
    val enabled: Boolean get() = state.value

    operator fun component1(): Boolean = enabled
    operator fun component2(): () -> Unit = request
}

@Composable
fun rememberNotificationPermission(): NotificationAccess {
    val context = LocalContext.current
    val state = remember { mutableStateOf(notificationsEnabled(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { state.value = notificationsEnabled(context) }

    // Back from Settings, or from the dialog after this screen was rebuilt.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = notificationsEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(launcher) {
        val prefs = context.getSharedPreferences("monero_wallet", Context.MODE_PRIVATE)

        fun permissionGranted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        // After two refusals the system stops showing the dialog: a launch
        // returns "denied" at once, and the rationale flag is off again.
        // The rationale flag is also off after a dialog closed with no
        // answer, so this only decides where Enable goes.
        fun dialogAvailable(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || permissionGranted()) return false
            if (!prefs.getBoolean(ASKED_KEY, false)) return true
            val activity = context.findActivity() ?: return true
            return ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.POST_NOTIFICATIONS
            )
        }

        fun showDialog() {
            prefs.edit().putBoolean(ASKED_KEY, true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        NotificationAccess(
            state = state,
            request = { if (!permissionGranted()) showDialog() },
            enable = {
                when {
                    notificationsEnabled(context) -> state.value = true
                    dialogAvailable() -> showDialog()
                    else -> {
                        val settings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        context.findActivity()?.startActivity(settings)
                            ?: context.startActivity(settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        )
    }
}

/** The permission (Android 13+) and the app's notification switch in Settings. */
private fun notificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private const val ASKED_KEY = "notification_permission_asked"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
