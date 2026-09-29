package one.monero.moneroone.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.SecureFlagPolicy
import one.monero.moneroone.BuildConfig
import java.util.WeakHashMap

/**
 * Blocks screenshots, screen recording and the recents thumbnail while a screen that shows a seed,
 * takes a seed or asks for the PIN is on screen. Balances, charts, history and settings stay capturable.
 */
object SecureScreens {
    /** Release builds only: debug builds stay capturable, so device test runs can screenshot every screen. */
    @VisibleForTesting
    var enforced = !BuildConfig.DEBUG

    /** A dialog has a window of its own: one that shows a seed, keys or a PIN sets this policy. */
    val dialogPolicy: SecureFlagPolicy
        get() = if (enforced) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit

    // Screens overlap during navigation transitions, so each window counts its holders.
    private val holds = WeakHashMap<Window, Int>()

    internal fun acquire(window: Window) {
        val count = (holds[window] ?: 0) + 1
        holds[window] = count
        if (count == 1) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    internal fun release(window: Window) {
        val count = (holds[window] ?: return) - 1
        if (count > 0) {
            holds[window] = count
        } else {
            holds.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/**
 * Call first in a sensitive screen. The effect runs before the frame that first draws the screen,
 * and the flag stays until the last sensitive screen leaves, after its exit transition.
 */
@Composable
fun SecureScreen() {
    if (!SecureScreens.enforced) return
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        SecureScreens.acquire(window)
        onDispose { SecureScreens.release(window) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
