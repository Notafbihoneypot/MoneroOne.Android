package one.monero.moneroone.ui.screens.wallet

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.review.ReviewPromptGate
import one.monero.moneroone.core.review.openPlayListing
import one.monero.moneroone.ui.components.DismissTextButton

/**
 * The rating ask on the wallet home after the first send that went out
 * (ReviewPromptGate), in the app's AlertDialog. It opens when the home has
 * been on screen and idle for about a second, as on iOS: resumed (no pushed
 * screen, transition or system dialog) and [isIdle] (no wallet list, History,
 * sheet or other dialog open on it). A pending ask waits for the next time
 * the home shows.
 */
@Composable
fun ReviewPromptAfterSend(isIdle: Boolean) {
    val context = LocalContext.current
    val gate = remember { ReviewPromptGate.create(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val idle by rememberUpdatedState(isIdle)
    var showing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(gate, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var quietChecks = 0
            while (gate.isPending) {
                delay(500)
                quietChecks = if (idle) quietChecks + 1 else 0
                if (quietChecks >= 2 && gate.claim(homeIsIdle = true)) showing = true
            }
        }
    }

    // The gate counts it as asked once it opens: Rate, No Thanks, Back and a tap outside only close it.
    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = {
                Text(
                    text = tr("I hate asking for this"),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = tr("But a rating really helps people find Monero One. I'll never ask again."),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showing = false
                        openPlayListing(context)
                    }
                ) {
                    Text(tr("Rate Monero One"))
                }
            },
            dismissButton = {
                DismissTextButton(onClick = { showing = false }) {
                    Text(tr("No Thanks"))
                }
            }
        )
    }
}
