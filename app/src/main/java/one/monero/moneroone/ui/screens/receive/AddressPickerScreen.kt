package one.monero.moneroone.ui.screens.receive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import one.monero.moneroone.core.locale.pluralTr
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.*
import one.monero.moneroone.data.util.XmrFormat
import one.monero.moneroone.ui.components.CellFill
import one.monero.moneroone.ui.components.DismissTextButton
import one.monero.moneroone.ui.components.MoneroTextField
import one.monero.moneroone.ui.components.moneroTextFieldColors
import one.monero.moneroone.ui.screens.settings.SettingsSectionFooter
import one.monero.moneroone.ui.screens.settings.SettingsSectionHeader
import one.monero.moneroone.ui.screens.wallet.EmojiPickerGrid
import one.monero.moneroone.ui.screens.wallet.emojiCircleSemantics
import one.monero.moneroone.ui.theme.*

/**
 * Select Address, as iOS AddressPickerView: the main address in its own
 * section, then Subaddresses with New Address first and the rest newest
 * first. A tap shows that address on Receive; a long press copies or
 * renames it. Edit puts a pencil on each subaddress, and a tap renames it.
 */
@Composable
fun AddressPickerScreen(walletViewModel: WalletViewModel, onBack: () -> Unit, onAddressSelected: (String, Int) -> Unit) {
    val state by walletViewModel.walletState.collectAsState()
    val wallet by walletViewModel.activeWallet.collectAsState()
    val addresses = state.addressesOf(wallet?.id)
    val rows = remember(addresses, state.transactions, wallet?.addressLabels) {
        ReceiveAddressLogic.rows(addresses?.list.orEmpty(), state.transactions, wallet?.addressLabels.orEmpty())
    }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var creating by remember(wallet?.id) { mutableStateOf(false) }
    var createFailed by remember { mutableStateOf(false) }
    val walletId = wallet?.id
    key(walletId) {
        AddressPickerContent(
            rows = rows, selectedIndex = state.receiveIndex,
            canCreate = addresses?.complete == true && !addresses.blocked &&
                ReceiveAddressLogic.unusedAfterLastUsed(rows) < ReceiveAddressLogic.STOP_THRESHOLD,
            creating = creating, onBack = onBack,
            onSelect = { row ->
                if (walletViewModel.activeWallet.value?.id == walletId) {
                    walletViewModel.setSelectedAddressIndex(row.index)
                    onAddressSelected(row.address.address, row.index)
                }
            },
            onRename = { row, label -> if (walletId != null) walletViewModel.renameReceiveAddress(walletId, row.index, label) },
            onCreate = {
                creating = true
                scope.launch {
                    try {
                        if (walletViewModel.createSubaddress()) {
                            // iOS stays here: the new row slides in at the top of
                            // Subaddresses with the check on it.
                            resultHaptic(view, success = true)
                            val index = walletViewModel.walletState.value.receiveIndex
                            announce(view, tr("Showing new address, %s", ReceiveAddressLogic.name(index)))
                        } else {
                            resultHaptic(view, success = false)
                            createFailed = true
                        }
                    } finally { creating = false }
                }
            }
        )
    }
    if (createFailed) {
        AlertDialog(
            onDismissRequest = { createFailed = false },
            title = { Text(tr("Couldn't Create Address")) },
            text = { Text(tr("Please wait until the wallet finishes syncing and try again.")) },
            confirmButton = { TextButton(onClick = { createFailed = false }) { Text(tr("OK")) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddressPickerContent(
    rows: List<ReceiveAddressRow>, selectedIndex: Int, canCreate: Boolean, creating: Boolean,
    onBack: () -> Unit, onSelect: (ReceiveAddressRow) -> Unit, onRename: (ReceiveAddressRow, String) -> Unit, onCreate: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var editing by remember { mutableStateOf(false) }
    var renamingIndex by remember { mutableStateOf<Int?>(null) }
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var search by remember { mutableStateOf("") }

    val main = rows.firstOrNull { it.index == 0 }
    val subaddresses = rows.filter { it.index > 0 }.sortedByDescending { it.index }
    val searching = search.isNotBlank()
    val visibleMain = main?.takeIf { ReceiveAddressLogic.matchesSearch(it, search) }
    val visibleRows = subaddresses.filter { ReceiveAddressLogic.matchesSearch(it, search) }
    val footer = ReceiveAddressLogic.creationWarning(rows)
        ?: if (subaddresses.isEmpty()) tr("Create subaddresses for better privacy when receiving payments.") else null

    fun copy(row: ReceiveAddressRow) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(row.name, row.address.address))
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        // Android 13 and later confirm a copy themselves, TalkBack included;
        // iOS shows nothing here either.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, tr("Address copied"), Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    fun AddressRow(row: ReceiveAddressRow) {
        AddressListRow(
            row = row, selected = row.index == selectedIndex, editing = editing,
            menuOpen = menuIndex == row.index, onMenu = { menuIndex = if (it) row.index else null },
            onTap = { if (editing && row.index > 0) renamingIndex = row.index else if (!editing) onSelect(row) },
            onCopy = { copy(row) }, onRename = { renamingIndex = row.index }
        )
    }

    Scaffold(
        containerColor = MoneroTheme.colors.bgGrouped,
        topBar = {
            TopAppBar(
                title = { Text(tr("Select Address"), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
                actions = {
                    TextButton(onClick = { editing = !editing }, enabled = subaddresses.isNotEmpty() || editing) {
                        Text(tr(if (editing) "Done" else "Edit"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MoneroTheme.colors.bgGrouped)
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
        ) {
            // Search appears once the list needs it, as on iOS.
            if (subaddresses.size >= ReceiveAddressLogic.SEARCH_THRESHOLD || searching) {
                item(key = "search") {
                    AddressSearchField(search, { search = it }, Modifier.animateItem().padding(bottom = 24.dp))
                }
            }
            if (visibleMain != null) {
                item(key = visibleMain.index) {
                    GroupCell(first = true, last = true, modifier = Modifier.animateItem()) { AddressRow(visibleMain) }
                }
                item(key = "main-footer") {
                    Box(Modifier.animateItem()) { SettingsSectionFooter(tr("Payments to your main address can be linked together.")) }
                }
            }
            item(key = "subaddresses") { Box(Modifier.animateItem()) { SettingsSectionHeader(tr("Subaddresses")) } }
            if (!searching) {
                item(key = "new") {
                    GroupCell(first = true, last = visibleRows.isEmpty(), modifier = Modifier.animateItem()) {
                        NewAddressRow(canCreate, creating, editing, onCreate)
                    }
                }
            }
            itemsIndexed(visibleRows, key = { _, row -> row.index }) { position, row ->
                GroupCell(first = searching && position == 0, last = position == visibleRows.lastIndex, modifier = Modifier.animateItem()) {
                    AddressRow(row)
                }
            }
            if (searching && visibleRows.isEmpty() && visibleMain == null) {
                item(key = "no-match") {
                    GroupCell(first = true, last = true, modifier = Modifier.animateItem()) {
                        Text(
                            tr("No matching addresses"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                        )
                    }
                }
            }
            if (footer != null) {
                item(key = "subaddresses-footer") { Box(Modifier.animateItem()) { SettingsSectionFooter(footer) } }
            }
        }
    }

    renamingIndex?.let { index -> rows.firstOrNull { it.index == index } }?.let { row ->
        RenameSubaddressSheet(
            row = row,
            onSave = { label ->
                onRename(row, label)
                renamingIndex = null
            },
            onDismiss = { renamingIndex = null }
        )
    }
}

/** Row text starts here: 16 padding, the 20dp check column, 12 spacing. Hairlines inset to it, as on iOS. */
private val RowTextInset = 48.dp

private val RowTitleWeight = FontWeight.SemiBold

/** One row of a grouped card: the card's rounded top on the first row, its bottom on the last, a hairline between. */
@Composable
private fun GroupCell(first: Boolean, last: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val top = if (first) 16.dp else 0.dp
    val bottom = if (last) 16.dp else 0.dp
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        if (!first) {
            HorizontalDivider(Modifier.padding(start = RowTextInset), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
        content()
    }
}

/**
 * One address, as iOS AddressListRow: a check on the one Receive shows (in
 * edit mode a pencil on each subaddress), the name and its number when it
 * has a label, the first and last eight characters, and what it has taken in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddressListRow(
    row: ReceiveAddressRow, selected: Boolean, editing: Boolean,
    menuOpen: Boolean, onMenu: (Boolean) -> Unit,
    onTap: () -> Unit, onCopy: () -> Unit, onRename: () -> Unit, modifier: Modifier = Modifier
) {
    val isMain = row.index == 0
    val renames = editing && !isMain
    // The main address has no name to edit.
    val dimmed = editing && isMain
    val alpha by animateFloatAsState(if (dimmed) 0.4f else 1f, label = "row")
    val checkAlpha by animateFloatAsState(if (selected && !editing) 1f else 0f, label = "check")
    val pencilAlpha by animateFloatAsState(if (renames) 1f else 0f, label = "pencil")
    val selectedText = tr("Selected")
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    enabled = !dimmed,
                    role = Role.Button,
                    onClickLabel = if (renames) tr("Rename") else null,
                    onClick = onTap,
                    onLongClick = { onMenu(true) }
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = ReceiveAddressLogic.spokenRow(row)
                    if (selected) stateDescription = selectedText
                    customActions = buildList {
                        add(CustomAccessibilityAction(tr("Copy Address")) { onCopy(); true })
                        if (!isMain) add(CustomAccessibilityAction(tr("Rename")) { onRename(); true })
                    }
                }
                .alpha(alpha)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = MoneroOrange, modifier = Modifier.size(18.dp).alpha(checkAlpha))
                Icon(Icons.Default.Edit, null, tint = MoneroOrange, modifier = Modifier.size(16.dp).alpha(pencilAlpha))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        row.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = RowTitleWeight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).alignByBaseline()
                    )
                    if (row.labeled && !isMain) {
                        Text(
                            "#${row.index}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.alignByBaseline()
                        )
                    }
                }
                Text(
                    truncateMiddle(row.address.address),
                    style = MonoCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            AddressUsageSummary(row.usage)
        }
        // iOS's context menu: Copy Address, and Rename for a subaddress.
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
            DropdownMenuItem(
                text = { Text(tr("Copy Address")) },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                onClick = { onMenu(false); onCopy() }
            )
            if (!isMain) {
                DropdownMenuItem(
                    text = { Text(tr("Rename")) },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = { onMenu(false); onRename() }
                )
            }
        }
    }
}

/**
 * What an address has taken in, on the trailing side of its row, as iOS
 * AddressUsageSummary: Received, the total in green and the number of
 * payments, or Unused.
 */
@Composable
private fun AddressUsageSummary(usage: AddressUsage) {
    if (usage.payments > 0) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr("Received"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                "${XmrFormat.compact(usage.received)} XMR",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                color = SuccessGreen,
                maxLines = 1
            )
            Text(pluralTr("%s payments", usage.payments), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    } else {
        Text(tr("Unused"), style = MaterialTheme.typography.bodySmall, color = MoneroTheme.colors.labelTertiary, maxLines = 1)
    }
}

/** The first row of Subaddresses, its plus in the check column (iOS newAddressRow). */
@Composable
private fun NewAddressRow(canCreate: Boolean, creating: Boolean, editing: Boolean, onCreate: () -> Unit) {
    val alpha by animateFloatAsState(if ((canCreate && !editing) || creating) 1f else 0.4f, label = "new")
    val label = if (creating) tr("Creating subaddress") else tr("New Address")
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = canCreate && !editing && !creating, role = Role.Button, onClick = onCreate)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .alpha(alpha)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.AddCircle, null, tint = MoneroOrange, modifier = Modifier.size(20.dp))
        Text(
            tr("New Address"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = RowTitleWeight,
            color = MoneroOrange,
            modifier = Modifier.weight(1f)
        )
        if (creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The search row iOS adds once there are eight subaddresses. */
@Composable
private fun AddressSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    MoneroTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(tr("Name, number, or address")) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (query.isEmpty()) null else {
            { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Cancel, tr("Clear address search")) } }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search
        ),
        colors = moneroTextFieldColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Rename Subaddress, as the iOS sheet: the icon in a circle (a tap opens the
 * emoji grid), the label, and a note that labels stay on this device. The
 * label is stored as "<emoji> <name>", as iOS stores it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameSubaddressSheet(row: ReceiveAddressRow, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val initial = remember(row.index) { ReceiveAddressLogic.splitLabel(if (row.labeled) row.name else "") }
    var emoji by remember(row.index) { mutableStateOf(initial.first) }
    var name by remember(row.index) { mutableStateOf(initial.second) }
    var picking by remember(row.index) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                DismissTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) { Text(tr("Cancel")) }
                Text(
                    tr("Rename Subaddress"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center).semantics { heading() }
                )
                TextButton(
                    onClick = { onSave(ReceiveAddressLogic.joinLabel(emoji, name)) },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) { Text(tr("Save"), color = MoneroOrange) }
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(80.dp).clip(CircleShape).background(CellFill)
                        .clickable { picking = !picking }
                        .emojiCircleSemantics(emoji),
                    contentAlignment = Alignment.Center
                ) {
                    Text(emoji, fontSize = 44.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(tr("Tap to change icon"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (picking) {
                    Spacer(Modifier.height(12.dp))
                    EmojiPickerGrid(selected = emoji, onSelect = {
                        emoji = it
                        picking = false
                    })
                }
                Spacer(Modifier.height(24.dp))
                MoneroTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text(tr("Label")) },
                    placeholder = { Text(tr("Label")) },
                    supportingText = { Text(tr("Labels stay on this device and aren't backed up with your seed.")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = moneroTextFieldColors(containerColor = CellFill),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Success or error, as iOS's notification haptics after New Address. */
private fun resultHaptic(view: View, success: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        view.performHapticFeedback(if (success) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT)
    }
}

/** A spoken note for TalkBack, as iOS posts a VoiceOver announcement. */
@Suppress("DEPRECATION")
private fun announce(view: View, text: String) = view.announceForAccessibility(text)
