package one.monero.moneroone.ui.screens.receive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.*
import one.monero.moneroone.ui.components.*
import one.monero.moneroone.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AddressPickerScreen(walletViewModel: WalletViewModel, onBack: () -> Unit, onAddressSelected: (String, Int) -> Unit) {
    val state by walletViewModel.walletState.collectAsState()
    val wallet by walletViewModel.activeWallet.collectAsState()
    val addresses = state.addressesOf(wallet?.id)
    val rows = remember(addresses, state.transactions, wallet?.addressLabels) {
        ReceiveAddressLogic.rows(addresses?.list.orEmpty(), state.transactions, wallet?.addressLabels.orEmpty())
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var creating by remember(wallet?.id) { mutableStateOf(false) }
    val walletId = wallet?.id
    key(walletId) {
        AddressPickerContent(
            rows = rows, selectedIndex = state.receiveIndex,
            canCreate = addresses?.complete == true && !addresses.blocked && !creating &&
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
                            val fresh = walletViewModel.walletState.value
                            fresh.addressesOf(walletId)?.shownAddress(fresh.receiveIndex)?.let { onAddressSelected(it.address, it.addressIndex) }
                        } else Toast.makeText(context, tr("Unable to create address"), Toast.LENGTH_SHORT).show()
                    } finally { creating = false }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AddressPickerContent(
    rows: List<ReceiveAddressRow>, selectedIndex: Int, canCreate: Boolean, creating: Boolean,
    onBack: () -> Unit, onSelect: (ReceiveAddressRow) -> Unit, onRename: (ReceiveAddressRow, String) -> Unit, onCreate: () -> Unit
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<ReceiveAddressRow?>(null) }
    var actions by remember { mutableStateOf<ReceiveAddressRow?>(null) }

    val displayedRows = rows.sortedWith(compareBy<ReceiveAddressRow> { it.index != 0 }.thenByDescending { it.index })

    fun copy(row: ReceiveAddressRow) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(row.name, row.address.address))
        Toast.makeText(context, tr("Address copied"), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = MoneroTheme.colors.bgGrouped,
        topBar = {
            TopAppBar(
                title = { Text(tr("Select Address"), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
                actions = { TextButton(onClick = { editing = !editing }, enabled = rows.any { it.index > 0 }) {
                    Text(tr(if (editing) "Done" else "Edit"))
                } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MoneroTheme.colors.bgGrouped)
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth(), shadow = false, cornerRadius = 16.dp) {
                    TextButton(
                        enabled = canCreate && !editing,
                        onClick = onCreate,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) {
                        if (creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("New Address"))
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
            ReceiveAddressLogic.creationWarning(rows)?.let { warning -> item {
                Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            // Main address followed by newest subaddresses; every address stays available.
            items(displayedRows, key = { it.index }) { row ->
                val top = if (row.index == displayedRows.first().index) 16.dp else 0.dp
                val bottom = if (row.index == displayedRows.last().index) 16.dp else 0.dp
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
                    .background(MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                selected = selectedIndex == row.index
                                customActions = buildList {
                                    add(CustomAccessibilityAction(tr("Copy address")) { copy(row); true })
                                    if (row.index > 0) add(CustomAccessibilityAction(tr("Rename")) { rename = row; true })
                                }
                            }
                            .combinedClickable(
                                onClick = {
                                    if (editing && row.index > 0) rename = row
                                    else if (!editing) {
                                        onSelect(row)
                                    }
                                },
                                onLongClick = { actions = row }
                            ).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(truncateMiddle(row.address.address), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(row.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (editing && row.index > 0) Icon(Icons.Default.Edit, tr("Rename"), tint = MoneroOrange)
                        else if (selectedIndex == row.index) Icon(Icons.Default.Check, tr("Selected"), tint = MoneroOrange)
                    }
                    if (row.index != displayedRows.last().index) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    actions?.let { row ->
        AlertDialog(onDismissRequest = { actions = null }, title = { Text(row.name) },
            text = { Column {
                TextButton(onClick = { copy(row); actions = null }) { Text(tr("Copy address")) }
                if (row.index > 0) TextButton(onClick = { rename = row; actions = null }) { Text(tr("Rename")) }
            } }, confirmButton = { DismissTextButton(onClick = { actions = null }) { Text(tr("Done")) } })
    }
    rename?.let { row ->
        var label by remember(row.index) { mutableStateOf(if (row.labeled) row.name else "") }
        var showEmoji by remember(row.index) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text(tr("Rename Subaddress")) },
            text = { Column {
                MoneroTextField(value = label, onValueChange = { label = it.take(80) }, singleLine = true,
                    label = { Text(tr("Name")) }, placeholder = { Text(tr("Name")) })
                TextButton(onClick = { showEmoji = !showEmoji }) { Text(tr("Emoji")) }
                if (showEmoji) one.monero.moneroone.ui.screens.wallet.EmojiPickerGrid(selected = "", onSelect = {
                    label = "$it ${label.trim()}".take(80)
                    showEmoji = false
                })
            } },
            confirmButton = { TextButton(onClick = {
                onRename(row, label)
                rename = null
            }) { Text(tr("Save")) } },
            dismissButton = { DismissTextButton(onClick = { rename = null }) { Text(tr("Cancel")) } })
    }
}
