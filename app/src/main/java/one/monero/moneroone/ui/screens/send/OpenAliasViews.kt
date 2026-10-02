package one.monero.moneroone.ui.screens.send

import android.content.Context
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.horizontalsystems.monerokit.model.NetworkType
import io.horizontalsystems.monerokit.model.Wallet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.monero.moneroone.R
import one.monero.moneroone.core.locale.tr
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
import one.monero.moneroone.ui.components.MoneroLogo
import one.monero.moneroone.ui.theme.ErrorRed
import one.monero.moneroone.ui.theme.MonoCaption
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.MoneroTheme
import one.monero.moneroone.ui.theme.WarningYellow
import one.monero.moneroone.ui.theme.truncateMiddle
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
        fun summary() = OpenAliasSummary(
            name, result.address, result.dnssec, pin,
            recipientName = result.recipientName,
            txDescription = result.txDescription
        )

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
    /** Plays when a lookup resolves: one light haptic, as on iOS. */
    private val resolvedFeedback: () -> Unit = {},
    /** Speaks the end of a lookup (resolved or failed) to TalkBack, as iOS posts an announcement. */
    private val announce: (LookupStatus) -> Unit = {},
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
            val finished = when (outcome) {
                is OpenAliasOutcome.Resolved -> LookupStatus.Resolved(
                    name,
                    outcome.result,
                    pins?.state(name.query, outcome.result.address) ?: PinState.None
                )
                is OpenAliasOutcome.Failed -> LookupStatus.Failed(name, outcome.failure)
            }
            status = finished
            if (finished is LookupStatus.Resolved) resolvedFeedback()
            announce(finished)
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
    val context = LocalContext.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    return remember {
        val resolver = OpenAliasResolver()
        OpenAliasLookupModel(
            scope = scope,
            lookup = { name -> resolver.resolve(name, ::openAliasAddressCheck) },
            pins = OpenAliasPinStore.create(context.applicationContext),
            isValidAddress = ::isValidMoneroAddress,
            // The keypad tick of the app, as iOS plays its soft tick.
            resolvedFeedback = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            announce = { status -> lookupAnnouncement(context, status)?.let { announce(view, it) } }
        )
    }
}

/** What TalkBack hears when a lookup ends, as on iOS. */
private fun lookupAnnouncement(context: Context, status: LookupStatus): String? = when (status) {
    is LookupStatus.Resolved ->
        context.getString(R.string.openalias_found_a11y, status.name.display) + ". " +
            context.getString(dnssecWordRes(status.result.dnssec))
    is LookupStatus.Failed -> context.getString(failureTextRes(status.failure))
    else -> null
}

/** A spoken note for TalkBack, as iOS posts a VoiceOver announcement. */
@Suppress("DEPRECATION")
private fun announce(view: View, text: String) = view.announceForAccessibility(text)

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
 * The row under the field on the name path, in place of the "Valid address"
 * row: a failed lookup or a rejected name. While a lookup runs, the spinner
 * sits in the field. A resolved name turns the field into
 * [OpenAliasRecipientCard], with [OpenAliasResolvedLines] here. The model
 * speaks the end of a lookup to TalkBack.
 */
@Composable
internal fun OpenAliasStatusRow(status: LookupStatus, onRetry: () -> Unit) {
    val visible = status is LookupStatus.Failed || status is LookupStatus.Rejected
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (status) {
                is LookupStatus.Failed -> ErrorLineContent(failureText(status.failure))
                is LookupStatus.Rejected -> ErrorLineContent(
                    stringResource(
                        if (status.reserved) R.string.openalias_error_reserved else R.string.openalias_error_invalid_name
                    )
                )
                LookupStatus.Idle, is LookupStatus.Address, is LookupStatus.Running, is LookupStatus.Resolved -> Unit
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

/**
 * The lock icon and the DNSSEC word. Valid is quiet: a secondary lock and
 * text, never green and never "Verified" (D5). Off is a caution: a yellow
 * open lock, with the text in the label color.
 */
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
        color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
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

@StringRes
private fun dnssecWordRes(dnssec: DnssecStatus): Int =
    if (dnssec == DnssecStatus.VALID) R.string.openalias_dnssec_valid else R.string.openalias_dnssec_off

@Composable
private fun dnssecWord(dnssec: DnssecStatus): String = stringResource(dnssecWordRes(dnssec))

@StringRes
private fun failureTextRes(failure: OpenAliasFailure): Int = when (failure) {
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

@Composable
private fun failureText(failure: OpenAliasFailure): String = stringResource(failureTextRes(failure))

@StringRes
private fun pinTextRes(pin: PinState): Int? = when (pin) {
    PinState.None -> null
    is PinState.Same -> R.string.openalias_pin_same
    is PinState.Changed -> R.string.openalias_pin_changed
}

/** The TalkBack value of the recipient card: the DNSSEC word and the pin. [text] reads a string resource. */
internal fun openAliasSpokenStatus(summary: OpenAliasSummary, text: (Int) -> String): String =
    listOfNotNull(text(dnssecWordRes(summary.dnssec)), pinTextRes(summary.pin)?.let(text)).joinToString(", ")

/** The spinner in the recipient field while a lookup runs, as on iOS. */
@Composable
internal fun OpenAliasLookupSpinner(name: OpenAliasName) {
    val label = stringResource(R.string.openalias_looking_up, name.display)
    CircularProgressIndicator(
        modifier = Modifier
            .size(16.dp)
            .clearAndSetSemantics { contentDescription = label },
        color = MoneroOrange,
        strokeWidth = 2.dp
    )
}

/**
 * The step-forward spring of iOS (response 0.3 s, damping 0.85): short, with
 * no visible bounce. The field turns into the recipient card with it, and back.
 * Stiffness is (2 pi / response) squared.
 */
private const val CARD_SPRING_STIFFNESS = 438.6f
internal fun <T> openAliasCardSpring(visibilityThreshold: T? = null) =
    spring(dampingRatio = 0.85f, stiffness = CARD_SPRING_STIFFNESS, visibilityThreshold = visibilityThreshold)

/** The gray of the monogram disc (iOS `Color.gray.gradient`): lighter at the top. */
private val MonogramFill = Brush.verticalGradient(
    0f to Color(0xFFADADB2),
    0.7f to Color(0xFF8E8E93)
)

/**
 * A Contacts-style monogram: up to two initials on a gray disc, or a person
 * glyph when no letter is left. The app never fetches an image or a favicon
 * for a name: that request would show the user's IP address to the domain.
 */
@Composable
internal fun RecipientMonogram(initials: String?, diameter: Dp = 44.dp) {
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(MonogramFill)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        // A fixed size: the disc does not grow with the font size.
        val density = LocalDensity.current
        val glyph = diameter * 0.4f
        if (initials != null) {
            val measurer = rememberTextMeasurer()
            val room = with(density) { (diameter * 0.76f).roundToPx() }
            val style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.SemiBold)
            // Shrinks wide initials to fit, down to half the size (iOS minimumScaleFactor 0.5).
            val fitted = remember(initials, diameter, density, style) {
                var scale = 1f
                while (scale > 0.5f) {
                    val size = with(density) { (glyph * scale).toSp() }
                    val width = measurer.measure(initials, style.copy(fontSize = size, lineHeight = size), softWrap = false).size.width
                    if (width <= room) break
                    scale -= 0.05f
                }
                with(density) { (glyph * scale.coerceAtLeast(0.5f)).toSp() }
            }
            Text(
                text = initials,
                style = style.copy(fontSize = fitted, lineHeight = fitted),
                maxLines = 1,
                softWrap = false
            )
        } else {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(glyph))
        }
    }
}

/** The picture of a recipient: the Monero One logo for Monero One's own names (see [OpenAliasSummary.showsMoneroOneLogo]), else the monogram. */
@Composable
internal fun RecipientAvatar(summary: OpenAliasSummary, diameter: Dp = 44.dp) {
    if (summary.showsMoneroOneLogo) {
        // The logo of Welcome and Unlock, still, clipped to the circle the
        // same way. It comes from the app: nothing is fetched.
        MoneroLogo(size = diameter, modifier = Modifier.clearAndSetSemantics { })
    } else {
        RecipientMonogram(summary.initials, diameter)
    }
}

/**
 * The field after a lookup resolved: the recipient, the way Mail turns an
 * address into a contact. The record name is the title and the OpenAlias name
 * the subtitle. Without a record name, the OpenAlias name is the title and the
 * short address the subtitle. The address step draws the field background
 * around it. One TalkBack element: the names, the DNSSEC state and the pin,
 * with Clear as an action.
 */
@Composable
internal fun OpenAliasRecipientCard(summary: OpenAliasSummary, onClear: () -> Unit) {
    val context = LocalContext.current
    val spokenStatus = openAliasSpokenStatus(summary, context::getString)
    val clearLabel = stringResource(R.string.send_clear_address)
    // Large text (the iOS accessibility sizes): the names get the full width, under the monogram.
    val largeText = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE
    val semantics = Modifier.clearAndSetSemantics {
        contentDescription = summary.spokenName
        stateDescription = spokenStatus
        customActions = listOf(CustomAccessibilityAction(clearLabel) { onClear(); true })
    }
    if (largeText) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(semantics)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                RecipientAvatar(summary)
                Spacer(modifier = Modifier.weight(1f))
                OpenAliasClearButton(onClear)
            }
            RecipientNames(summary, largeText = true, modifier = Modifier.padding(end = 16.dp))
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(semantics)
                // The clear button's 48 dp target ends at the edge, so its
                // glyph keeps the place it has in the field.
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RecipientAvatar(summary)
            Spacer(modifier = Modifier.width(12.dp))
            RecipientNames(summary, largeText = false, modifier = Modifier.weight(1f))
            OpenAliasClearButton(onClear)
        }
    }
}

/** iOS starts its accessibility text sizes at about 1.65 times the default size. */
private const val LARGE_TEXT_SCALE = 1.6f

@Composable
private fun RecipientNames(summary: OpenAliasSummary, largeText: Boolean, modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val recipientName = summary.recipientName
        if (recipientName != null) {
            Text(
                text = recipientName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = if (largeText) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis
            )
            OpenAliasNameText(summary.name.display)
        } else {
            OpenAliasNameText(
                summary.name.display,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(truncateMiddle(summary.address), style = MonoCaption, color = secondary)
        }
    }
}

/**
 * An OpenAlias name, every character of it, with no hyphen added:
 * "donate@get-monero.org" reads as another domain, and a cut-off name can hide
 * the end of its domain. A long name wraps after the "@" or before a "."
 * first, and between two characters only inside a longer part.
 */
@Composable
internal fun OpenAliasNameText(
    name: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(text = openAliasWrappable(name), style = style.copy(hyphens = Hyphens.None), color = color)
}

/** The name with a zero-width space after the "@" and before each ".", the places where a line can break. */
internal fun openAliasWrappable(name: String): String = buildString {
    for (character in name) {
        if (character == '.') append('\u200B')
        append(character)
        if (character == '@') append('\u200B')
    }
}

/**
 * The quiet lines under the recipient card: the DNSSEC state, the short
 * address (when the card shows none), and the pin state. TalkBack reads them
 * on the card.
 */
@Composable
internal fun OpenAliasResolvedLines(summary: OpenAliasSummary?) {
    val last = remember { arrayOfNulls<OpenAliasSummary>(1) }
    if (summary != null) last[0] = summary
    AnimatedVisibility(
        visible = summary != null,
        enter = fadeIn(openAliasCardSpring()),
        exit = fadeOut(openAliasCardSpring())
    ) {
        val shown = last[0] ?: return@AnimatedVisibility
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clearAndSetSemantics { },
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val dnssecLine: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    DnssecLineContent(shown.dnssec)
                }
            }
            if (shown.recipientName != null) {
                SideBySideOrStacked(
                    rowGap = 6.dp,
                    columnGap = 4.dp,
                    separator = { Text("·", style = MaterialTheme.typography.bodySmall, color = MoneroTheme.colors.labelTertiary) },
                    first = dnssecLine,
                    second = { Text(truncateMiddle(shown.address), style = MonoCaption, color = secondary) }
                )
            } else {
                dnssecLine()
            }
            when (shown.pin) {
                is PinState.Same -> Text(
                    stringResource(R.string.openalias_pin_same),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary
                )
                // The amount step's caution style: a yellow triangle, the text in the label color.
                is PinState.Changed -> Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = WarningYellow,
                        modifier = Modifier.padding(top = 1.dp).size(14.dp)
                    )
                    Text(
                        stringResource(R.string.openalias_pin_changed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                PinState.None -> Unit
            }
        }
    }
}

/**
 * iOS ViewThatFits for two parts: side by side when both fit on one line at
 * their full width, else stacked. [separator] shows only side by side, between
 * them. [spread] puts the second part at the end of the line.
 */
@Composable
private fun SideBySideOrStacked(
    rowGap: Dp,
    columnGap: Dp,
    modifier: Modifier = Modifier,
    spread: Boolean = false,
    separator: @Composable () -> Unit = {},
    first: @Composable () -> Unit,
    second: @Composable () -> Unit
) {
    Layout(contents = listOf(first, separator, second), modifier = modifier) { (a, b, c), constraints ->
        val firstPart = a.first()
        val dot = b.firstOrNull()
        val secondPart = c.first()
        val gap = rowGap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val dotWidth = dot?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
        val lineWidth = firstPart.maxIntrinsicWidth(Constraints.Infinity) +
            secondPart.maxIntrinsicWidth(Constraints.Infinity) +
            (if (dot != null) dotWidth + 2 * gap else gap)
        if (lineWidth <= constraints.maxWidth) {
            val one = firstPart.measure(loose)
            val middle = dot?.measure(loose)
            val two = secondPart.measure(loose)
            val height = maxOf(one.height, middle?.height ?: 0, two.height)
            val width = if (spread) constraints.maxWidth else lineWidth.coerceAtLeast(constraints.minWidth)
            layout(width, height) {
                one.placeRelative(0, (height - one.height) / 2)
                var x = one.width + gap
                if (middle != null) {
                    middle.placeRelative(x, (height - middle.height) / 2)
                    x += middle.width + gap
                }
                two.placeRelative(if (spread) width - two.width else x, (height - two.height) / 2)
            }
        } else {
            val one = firstPart.measure(loose)
            val two = secondPart.measure(loose)
            val space = columnGap.roundToPx()
            val width = maxOf(one.width, two.width).coerceIn(constraints.minWidth, constraints.maxWidth)
            layout(width, one.height + space + two.height) {
                one.placeRelative(0, 0)
                two.placeRelative(0, one.height + space)
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
        val recipientName = request.result.recipientName
        message = if (recipientName != null) {
            tr(
                "The address for %s (%s) is different from the address you paid on %s. Check the new address with the recipient.",
                recipientName,
                request.name.display,
                paidOn
            )
        } else {
            stringResource(R.string.openalias_changed_message, request.name.display, paidOn)
        }
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

/** The review recipient row as one element: "Recipient: record name, OpenAlias name, address …". */
@Composable
internal fun openAliasRecipientSemantics(summary: OpenAliasSummary?, address: String): Modifier {
    if (summary == null) return Modifier
    val label = stringResource(R.string.openalias_review_a11y, summary.spokenName, address)
    return Modifier.clearAndSetSemantics { contentDescription = label }
}

/**
 * The names in the review recipient row, under "Recipient": the record name
 * and the OpenAlias name, then every character of the address.
 */
@Composable
internal fun OpenAliasReviewNames(summary: OpenAliasSummary) {
    val recipientName = summary.recipientName
    if (recipientName != null) {
        Text(text = recipientName, style = MaterialTheme.typography.titleMedium)
        OpenAliasNameText(summary.name.display)
    } else {
        OpenAliasNameText(
            summary.name.display,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    // Wraps at any character, with no hyphen.
    Text(
        text = summary.address,
        style = MonoCaption.copy(hyphens = Hyphens.None),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/**
 * The `tx_description` of the record, as a row of the review card. The
 * record's owner wrote it, like the name. Side by side when it fits on one
 * line, else the text under the label.
 */
@Composable
internal fun OpenAliasDescriptionRow(text: String) {
    val style = MaterialTheme.typography.bodyMedium
    SideBySideOrStacked(
        rowGap = 16.dp,
        columnGap = 4.dp,
        spread = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        first = { Text(tr("Description"), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        second = { Text(text, style = style) }
    )
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
