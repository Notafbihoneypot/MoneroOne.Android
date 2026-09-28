package one.monero.moneroone.ui.screens.send

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.horizontalsystems.monerokit.model.NetworkType
import io.horizontalsystems.monerokit.model.Wallet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.monero.moneroone.R
import one.monero.moneroone.core.openalias.AddressCheck
import one.monero.moneroone.core.openalias.DnssecStatus
import one.monero.moneroone.core.openalias.OpenAlias
import one.monero.moneroone.core.openalias.OpenAliasConfig
import one.monero.moneroone.core.openalias.OpenAliasFailure
import one.monero.moneroone.core.openalias.OpenAliasName
import one.monero.moneroone.core.openalias.OpenAliasOutcome
import one.monero.moneroone.core.openalias.OpenAliasPinStore
import one.monero.moneroone.core.openalias.OpenAliasResolver
import one.monero.moneroone.core.openalias.OpenAliasResult
import one.monero.moneroone.core.openalias.OpenAliasSummary
import one.monero.moneroone.core.openalias.PinState
import one.monero.moneroone.core.openalias.RecipientInput
import one.monero.moneroone.core.openalias.recordAfterSend
import one.monero.moneroone.core.wallet.SendState
import one.monero.moneroone.ui.components.CapsuleShape
import one.monero.moneroone.ui.components.DismissTextButton
import one.monero.moneroone.ui.components.FieldShape
import one.monero.moneroone.ui.theme.ErrorRed
import one.monero.moneroone.ui.theme.MonoCaption
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.WarningYellow
import java.text.DateFormat
import java.util.Date

// ============================================================
// Lookup state (spec sections 5.1 and 5.2)
// ============================================================

/** What the row under the recipient field shows. */
internal sealed class LookupStatus {
    /** Nothing: empty text, or typed text inside the typing delay. */
    data object Idle : LookupStatus()

    /** Address text, checked after the typing delay: the existing "Valid address" or "Invalid address" row. */
    data class Address(val text: String, val valid: Boolean) : LookupStatus()
    data class Running(val name: OpenAliasName) : LookupStatus()
    data class Resolved(val name: OpenAliasName, val result: OpenAliasResult, val pin: PinState) : LookupStatus() {
        fun summary() = OpenAliasSummary(name, result.address, result.dnssec, pin)

        /** A changed pin, or DNSSEC off, asks the user first. */
        val needsConfirmation: Boolean get() = pin is PinState.Changed || result.dnssec == DnssecStatus.OFF
    }
    data class Failed(val name: OpenAliasName, val failure: OpenAliasFailure) : LookupStatus()

    /** The invalid-name row, or the reserved row for `monero.one` names and `@name`. */
    data class Rejected(val reserved: Boolean) : LookupStatus()
}

/**
 * Classifies the recipient text and runs name lookups for the Send flow: the
 * typing delay, cancellation, and a generation counter that makes a late
 * result harmless. It lives with the flow (not the address step), so a result
 * survives a step back from the amount step.
 */
@Stable
internal class OpenAliasLookupModel(
    private val scope: CoroutineScope,
    private val lookup: suspend (OpenAliasName) -> OpenAliasOutcome,
    private val pins: OpenAliasPinStore?,
    private val isValidAddress: (String) -> Boolean,
    /** The typing delay. Tests pass their own. */
    private val typingPause: suspend () -> Unit = { delay(OpenAliasConfig.TYPING_DELAY_MS) },
) {
    var status: LookupStatus by mutableStateOf(LookupStatus.Idle)
        private set

    private var generation = 0
    private var job: Job? = null

    /** What the status or the waiting delay is for: a query name, an address or a rejected text. */
    private var activeKey: String? = null
    private var waiting = false

    /**
     * Typed text is classified after the typing delay, so no row shows while
     * the user types. Paste, Scan and Return ([immediate]) classify at once.
     */
    fun onInputChanged(text: String, immediate: Boolean) {
        when (val input = OpenAlias.classify(text)) {
            RecipientInput.Empty -> cancel()
            is RecipientInput.Name -> {
                val name = input.name
                val key = "name:" + name.query
                // Same query name (a space added, other case): keep the state, with the new text.
                if (key == activeKey) status = status.withName(name)
                settle(key, immediate, redRow = null) { start(name) }
            }
            is RecipientInput.Address -> {
                val valid = isValidAddress(input.text)
                settle("address:" + input.text, immediate, redRow = if (valid) null else RedRow.ADDRESS) {
                    status = LookupStatus.Address(input.text, valid)
                }
            }
            RecipientInput.Reserved -> settle("reserved:" + text.trim(), immediate, redRow = RedRow.NAME) {
                status = LookupStatus.Rejected(reserved = true)
            }
            RecipientInput.InvalidName -> settle("invalid:" + text.trim(), immediate, redRow = RedRow.NAME) {
                status = LookupStatus.Rejected(reserved = false)
            }
        }
    }

    fun retry() {
        (status as? LookupStatus.Failed)?.let { start(it.name) }
    }

    fun cancel() {
        stopWork()
        activeKey = null
        status = LookupStatus.Idle
    }

    /** Writes the pin when a send to a resolved name succeeds. */
    fun onSendState(state: SendState, summary: OpenAliasSummary?, sentAddress: String) {
        pins?.recordAfterSend(state, summary, sentAddress)
    }

    /**
     * Runs [action] after the typing delay, or at once when [immediate].
     * [redRow]: the red row that the new text gets, if any.
     */
    private fun settle(key: String, immediate: Boolean, redRow: RedRow?, action: () -> Unit) {
        if (key == activeKey) {
            // The same text: keep the current state.
            when {
                // Paste or Return: at once, or again after a failed lookup.
                immediate && (waiting || status is LookupStatus.Failed) -> {
                    stopWork()
                    action()
                }
                // Still typing (a trailing dot, for example): the delay starts again.
                waiting -> {
                    stopWork()
                    schedule(action)
                }
            }
            return
        }
        stopWork()
        activeKey = key
        // A red row stays on screen while the new text gets the same red row;
        // the action then changes it in place. Other rows go at once, so the
        // address row and the name row never show together.
        if (redRow == null || status.redRow != redRow) status = LookupStatus.Idle
        if (immediate) action() else schedule(action)
    }

    /** The red rows: "Invalid address" in the address row; "Invalid name" or the reserved row in the name row. */
    private enum class RedRow { ADDRESS, NAME }

    private val LookupStatus.redRow: RedRow?
        get() = when {
            this is LookupStatus.Address && !valid -> RedRow.ADDRESS
            this is LookupStatus.Rejected -> RedRow.NAME
            else -> null
        }

    private fun stopWork() {
        generation++
        job?.cancel()
        job = null
        waiting = false
    }

    private fun schedule(action: () -> Unit) {
        val scheduled = generation
        waiting = true
        job = scope.launch {
            typingPause()
            if (scheduled != generation) return@launch
            waiting = false
            job = null
            action()
        }
    }

    private fun start(name: OpenAliasName) {
        stopWork()
        val started = generation
        status = LookupStatus.Running(name)
        job = scope.launch {
            val outcome = try {
                lookup(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OpenAliasOutcome.Failed(OpenAliasFailure.NETWORK)
            }
            if (started != generation) return@launch
            job = null
            status = when (outcome) {
                is OpenAliasOutcome.Resolved -> LookupStatus.Resolved(
                    name,
                    outcome.result,
                    pins?.state(name.query, outcome.result.address) ?: PinState.None
                )
                is OpenAliasOutcome.Failed -> LookupStatus.Failed(name, outcome.failure)
            }
        }
    }

    private fun LookupStatus.withName(name: OpenAliasName): LookupStatus = when (this) {
        is LookupStatus.Running -> copy(name = name)
        is LookupStatus.Resolved -> copy(name = name)
        is LookupStatus.Failed -> copy(name = name)
        else -> this
    }
}

@Composable
internal fun rememberOpenAliasLookup(): OpenAliasLookupModel {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember {
        val resolver = OpenAliasResolver()
        OpenAliasLookupModel(
            scope = scope,
            lookup = { name -> resolver.resolve(name, ::openAliasAddressCheck) },
            pins = OpenAliasPinStore.create(context),
            isValidAddress = ::isValidMoneroAddress
        )
    }
}

/** Mainnet wallet: valid here, or valid on testnet (the other network). */
internal fun openAliasAddressCheck(address: String): AddressCheck = when {
    isValidMoneroAddress(address) -> AddressCheck.VALID
    isTestnetAddress(address) -> AddressCheck.OTHER_NETWORK
    else -> AddressCheck.INVALID
}

private fun isTestnetAddress(address: String): Boolean = try {
    Wallet.isAddressValid(address, NetworkType.NetworkType_Testnet.value)
} catch (e: Throwable) {
    false
}

// ============================================================
// Address step pieces
// ============================================================

/** The clear button in the recipient field, as on iOS: an x in a filled circle, in the secondary color. */
@Composable
internal fun OpenAliasClearButton(onClear: () -> Unit) {
    IconButton(onClick = onClear) {
        Icon(
            Icons.Filled.Cancel,
            contentDescription = stringResource(R.string.send_clear_address),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** The fade of the rows under the recipient field, as on iOS (0.2 s ease-out). */
internal val openAliasFadeSpec = tween<Float>(OpenAliasConfig.FADE_MS, easing = EaseOut)

/**
 * The row under the field on the name path. One node keeps the live region,
 * so TalkBack reads the result and the errors as they come.
 */
@Composable
internal fun OpenAliasStatusRow(status: LookupStatus, onRetry: () -> Unit) {
    // Addresses keep the existing validation row of the Send screen.
    val visible = status !is LookupStatus.Idle && status !is LookupStatus.Address
    val last = remember { arrayOfNulls<LookupStatus>(1) }
    if (visible) last[0] = status
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(openAliasFadeSpec),
        // When the address row takes this place, this row goes at once, so
        // the two never show together (as on iOS).
        exit = if (status is LookupStatus.Address) ExitTransition.None else fadeOut(openAliasFadeSpec)
    ) {
        val shown = last[0] ?: return@AnimatedVisibility
        StatusRowContent(shown, onRetry)
    }
}

@Composable
private fun StatusRowContent(status: LookupStatus, onRetry: () -> Unit) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val announcement: String? = when (status) {
        is LookupStatus.Resolved ->
            stringResource(R.string.openalias_found_a11y, status.name.display) + ". " + dnssecWord(status.result.dnssec)
        is LookupStatus.Failed -> failureText(status.failure)
        else -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {
                    if (announcement != null) {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = announcement
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (status) {
                is LookupStatus.Running -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = MoneroOrange,
                        strokeWidth = 2.dp
                    )
                    Text(
                        stringResource(R.string.openalias_looking_up, status.name.display),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary
                    )
                }
                is LookupStatus.Resolved -> DnssecLineContent(status.result.dnssec)
                is LookupStatus.Failed -> ErrorLineContent(failureText(status.failure))
                is LookupStatus.Rejected -> ErrorLineContent(
                    stringResource(
                        if (status.reserved) R.string.openalias_error_reserved else R.string.openalias_error_invalid_name
                    )
                )
                LookupStatus.Idle, is LookupStatus.Address -> Unit
            }
        }
        if (status is LookupStatus.Failed && status.failure.canRetry) {
            TryAgainButton(onRetry)
        }
    }
}

@Composable
private fun ErrorLineContent(text: String) {
    Icon(Icons.Filled.Cancel, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(14.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = ErrorRed)
}

@Composable
private fun DnssecLineContent(dnssec: DnssecStatus) {
    val valid = dnssec == DnssecStatus.VALID
    Icon(
        if (valid) Icons.Filled.Lock else Icons.Filled.LockOpen,
        contentDescription = null,
        tint = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else WarningYellow,
        modifier = Modifier.size(14.dp)
    )
    Text(
        dnssecWord(dnssec),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun TryAgainButton(onRetry: () -> Unit) {
    val hint = stringResource(R.string.openalias_try_again_hint)
    Text(
        text = stringResource(R.string.openalias_try_again),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = MoneroOrange,
        modifier = Modifier
            .clip(CapsuleShape)
            .clickable(onClickLabel = hint, role = Role.Button, onClick = onRetry)
            // No vertical padding: the row keeps its height when the button shows.
            // Compose still gives the button a 48 dp touch target.
            .padding(horizontal = 8.dp)
    )
}

@Composable
private fun dnssecWord(dnssec: DnssecStatus): String = stringResource(
    if (dnssec == DnssecStatus.VALID) R.string.openalias_dnssec_valid else R.string.openalias_dnssec_off
)

@Composable
private fun failureText(failure: OpenAliasFailure): String = stringResource(
    when (failure) {
        OpenAliasFailure.NO_RECORD -> R.string.openalias_error_no_record
        OpenAliasFailure.SEVERAL_ADDRESSES -> R.string.openalias_error_several
        OpenAliasFailure.PAYMENT_ID -> R.string.openalias_error_payment_id
        OpenAliasFailure.INVALID_ADDRESS -> R.string.openalias_error_invalid_address
        OpenAliasFailure.WRONG_NETWORK -> R.string.openalias_error_wrong_network
        OpenAliasFailure.DNSSEC_FAILED -> R.string.openalias_error_dnssec_failed
        OpenAliasFailure.TIMEOUT -> R.string.openalias_error_timeout
        OpenAliasFailure.NETWORK -> R.string.openalias_error_network
        OpenAliasFailure.TOR -> R.string.openalias_error_tor
    }
)

/** The resolved address and the pin line, in the field's color and shape. One TalkBack element. */
@Composable
internal fun OpenAliasResultBlock(status: LookupStatus) {
    val last = remember { arrayOfNulls<LookupStatus.Resolved>(1) }
    val resolved = status as? LookupStatus.Resolved
    if (resolved != null) last[0] = resolved
    AnimatedVisibility(
        visible = resolved != null,
        enter = fadeIn(openAliasFadeSpec),
        exit = fadeOut(openAliasFadeSpec)
    ) {
        val shown = last[0] ?: return@AnimatedVisibility
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, FieldShape)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.openalias_address_label),
                style = MaterialTheme.typography.bodySmall,
                color = secondary
            )
            SelectionContainer {
                Text(shown.result.address, style = MonoCaption)
            }
            when (shown.pin) {
                is PinState.Same -> Text(
                    stringResource(R.string.openalias_pin_same),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary
                )
                is PinState.Changed -> Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = WarningYellow, modifier = Modifier.size(14.dp))
                    Text(
                        stringResource(R.string.openalias_pin_changed),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary
                    )
                }
                PinState.None -> Unit
            }
        }
    }
}

/** "Address Changed" when the pin changed, else "Name Not Signed". */
@Composable
internal fun OpenAliasContinueDialog(
    request: LookupStatus.Resolved,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val changed = request.pin as? PinState.Changed
    val title: String
    val message: String
    val confirm: String
    if (changed != null) {
        val paidOn = remember(changed.pinnedAt) {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(changed.pinnedAt * 1000L))
        }
        title = stringResource(R.string.openalias_changed_title)
        message = stringResource(R.string.openalias_changed_message, request.name.display, paidOn)
        confirm = stringResource(R.string.openalias_use_new_address)
    } else {
        title = stringResource(R.string.openalias_unsigned_title)
        message = stringResource(R.string.openalias_unsigned_message, request.name.display)
        confirm = stringResource(R.string.openalias_continue_anyway)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm) }
        },
        dismissButton = {
            DismissTextButton(onClick = onDismiss) { Text(stringResource(R.string.openalias_cancel)) }
        }
    )
}

/** Continue while a lookup runs: TalkBack says why it is off. */
@Composable
internal fun openAliasContinueHint(running: Boolean): Modifier {
    if (!running) return Modifier
    val hint = stringResource(R.string.openalias_continue_hint_wait)
    return Modifier.semantics { stateDescription = hint }
}

// ============================================================
// Amount and review step pieces (spec sections 5.3 and 5.4)
// ============================================================

@Composable
internal fun openAliasAmountTitle(summary: OpenAliasSummary): String =
    stringResource(R.string.send_to_name, summary.name.display)

/** The review recipient row as one element: "Recipient: name, address …". */
@Composable
internal fun openAliasRecipientSemantics(summary: OpenAliasSummary?, address: String): Modifier {
    if (summary == null) return Modifier
    val label = stringResource(R.string.openalias_review_a11y, summary.name.display, address)
    return Modifier.clearAndSetSemantics { contentDescription = label }
}

/**
 * The review name, shortened to fit one line: only the part before "@" loses
 * letters (from its end, with an ellipsis). "@domain" is the part to check,
 * so it always stays whole. [fits] tells whether a text fits on the line.
 * A name without "@" is all domain, so it comes back whole.
 */
internal fun shortenReviewName(display: String, fits: (String) -> Boolean): String {
    if (fits(display)) return display
    val at = display.indexOf('@')
    if (at <= 0) return display
    val local = display.substring(0, at)
    val domain = display.substring(at)
    // The longest start of the local part that fits; one letter at least.
    var shortest = 1
    var longest = local.length - 1
    var best = 1
    while (shortest <= longest) {
        val count = (shortest + longest) / 2
        if (fits(local.take(count) + "\u2026" + domain)) {
            best = count
            shortest = count + 1
        } else {
            longest = count - 1
        }
    }
    return local.take(best) + "\u2026" + domain
}

@Composable
internal fun OpenAliasReviewName(summary: OpenAliasSummary) {
    // One line, as on iOS, with the domain always whole (see shortenReviewName).
    // A text that still does not fit wraps, so the domain is never cut.
    val style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val width = constraints.maxWidth
        val text = remember(summary.name.display, width, style) {
            shortenReviewName(summary.name.display) { candidate ->
                measurer.measure(candidate, style, softWrap = false, maxLines = 1).size.width <= width
            }
        }
        Text(text = text, style = style)
    }
}

/** Under the review recipient row: the DNSSEC line, and the first-payment note. */
@Composable
internal fun OpenAliasReviewNotes(summary: OpenAliasSummary, modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DnssecLineContent(summary.dnssec)
        }
        if (summary.pin == PinState.None) {
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = secondary, modifier = Modifier.size(14.dp))
                Text(
                    stringResource(R.string.openalias_first_payment),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary
                )
            }
        }
    }
}
