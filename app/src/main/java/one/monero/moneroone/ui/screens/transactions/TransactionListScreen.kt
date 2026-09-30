package one.monero.moneroone.ui.screens.transactions

import one.monero.moneroone.core.locale.pluralTr
import one.monero.moneroone.core.wallet.ReceiveAddressLogic
import one.monero.moneroone.core.wallet.addressesOf
import one.monero.moneroone.data.model.PriceHistory
import one.monero.moneroone.data.util.MoneyFormat
import one.monero.moneroone.data.util.XmrFormat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import one.monero.moneroone.ui.components.ShrinkToFitText
import one.monero.moneroone.core.locale.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import one.monero.moneroone.ui.components.rememberChartDateFormats
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.horizontalsystems.monerokit.model.TransactionInfo
import one.monero.moneroone.core.wallet.WalletViewModel
import one.monero.moneroone.ui.components.CapsuleShape
import one.monero.moneroone.ui.components.GlassCard
import one.monero.moneroone.ui.components.MoneroTextField
import one.monero.moneroone.ui.components.StatusDot
import one.monero.moneroone.ui.components.TransactionStatus
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.MoneroTheme
import one.monero.moneroone.ui.theme.PendingOrange
import one.monero.moneroone.ui.theme.SuccessGreen
import one.monero.moneroone.ui.theme.ErrorRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class TransactionFilter(val label: String) {
    ALL("All"),
    RECEIVED("Received"),
    SENT("Sent"),
    PENDING("Pending")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    walletViewModel: WalletViewModel,
    onBack: () -> Unit,
    onTransactionClick: (String) -> Unit,
    /** From History: only transactions up to this moment (ms); null for all. */
    asOf: Long? = null
) {
    val walletState by walletViewModel.walletState.collectAsState()
    val fiatMode by walletViewModel.fiatMode.collectAsState()
    val priceHistory by walletViewModel.priceHistory.collectAsState()
    val currentPrice by walletViewModel.currentPrice.collectAsState()
    val currency by walletViewModel.selectedCurrency.collectAsState()
    val activeWallet by walletViewModel.activeWallet.collectAsState()
    var receivingIndex by rememberSaveable(activeWallet?.id) { mutableStateOf<Int?>(null) }
    var showAddresses by remember { mutableStateOf(false) }
    var selectedFilter by rememberSaveable(activeWallet?.id) { mutableStateOf(TransactionFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    // Opened from a past moment in History, the list stops there.
    val transactions = remember(walletState.transactions, asOf) { TransactionHistoryLogic.through(asOf, walletState.transactions) }
    val addressRows = remember(walletState.addresses, transactions, activeWallet?.addressLabels) {
        ReceiveAddressLogic.rows(walletState.addressesOf(activeWallet?.id)?.list.orEmpty(), transactions, activeWallet?.addressLabels.orEmpty())
    }
    val filteredTransactions = remember(transactions, selectedFilter, searchQuery, receivingIndex, addressRows) {
        TransactionHistoryLogic.filter(transactions, selectedFilter, receivingIndex, searchQuery, addressRows)
    }
    val dates = rememberChartDateFormats()
    val totals = remember(filteredTransactions) { TransactionHistoryLogic.totals(filteredTransactions) }
    val fiatTotals = remember(filteredTransactions, priceHistory, currentPrice) {
        TransactionHistoryLogic.fiatTotals(filteredTransactions) {
            PriceHistory.priceAt(it, priceHistory, currentPrice?.price, System.currentTimeMillis() / 1000)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(tr("All Transactions"), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Search bar
            MoneroTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(tr("Search transactions")) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Filter chips
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransactionFilter.entries.forEach { filter ->
                    // Chips: capsules, the selected one tinted in the brand hue.
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(tr(filter.label), fontWeight = FontWeight.SemiBold) },
                        shape = CapsuleShape,
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            selectedContainerColor = MoneroOrange.copy(alpha = 0.15f),
                            selectedLabelColor = MoneroOrange
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box {
                TextButton(onClick = { showAddresses = true }) {
                    Text(tr("Receiving address") + ": " + (addressRows.firstOrNull { it.index == receivingIndex }?.name ?: tr("Any")))
                }
                DropdownMenu(expanded = showAddresses, onDismissRequest = { showAddresses = false }) {
                    DropdownMenuItem(text = { Text(tr("Any")) }, onClick = { receivingIndex = null; showAddresses = false })
                    addressRows.filter { it.index == 0 || it.labeled || it.usage.payments > 0 }.sortedBy { it.index }.forEach { row ->
                        DropdownMenuItem(text = { Text(row.name) }, onClick = { receivingIndex = row.index; showAddresses = false })
                    }
                }
            }
            if (asOf != null) {
                Text(
                    text = tr("As of %s", dates.abbreviatedDateTime(asOf)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 8.dp).testTag("transactions.asOf")
                )
            }
            GlassCard(Modifier.fillMaxWidth(), shadow = false, cornerRadius = 16.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(pluralTr("%s transactions", totals.count), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf(Triple(tr("Received"), totals.received, fiatTotals?.received), Triple(tr("Sent"), totals.sent, fiatTotals?.sent)).forEach { (title, amount, fiat) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val fiatValue = fiat?.let { MoneyFormat.format(it.toDouble(), currency, fractionDigits = 2) }
                                ShrinkToFitText(if (fiatMode && fiatValue != null) fiatValue else "${XmrFormat.format(amount)} XMR",
                                    style = MaterialTheme.typography.titleMedium, minScale = 0.5f)
                                if (fiatMode && fiatValue != null) Text("${XmrFormat.format(amount)} XMR", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text(tr("Sent total includes fees"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))

            if (filteredTransactions.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) tr("No matching transactions") else tr("No transactions"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (searchQuery.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = tr("Try a different search term"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(
                        items = filteredTransactions,
                        key = { it.hash }
                    ) { transaction ->
                        TransactionListItem(
                            transaction = transaction,
                            formatXmr = walletViewModel::formatXmr,
                            fiatMode = fiatMode,
                            fiatValue = one.monero.moneroone.ui.components.transactionFiat(transaction, priceHistory, currentPrice?.price, currency),
                            receivedOn = TransactionHistoryLogic.receivedOnName(transaction, addressRows),
                            onClick = { onTransactionClick(transaction.hash) }
                        )
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TransactionListItem(
    transaction: TransactionInfo,
    formatXmr: (Long) -> String,
    fiatMode: Boolean,
    fiatValue: String?,
    /** The subaddress an incoming payment arrived on, spoken only; null for sends and the main address. */
    receivedOn: String?,
    onClick: () -> Unit
) {
    val isIncoming = transaction.direction == TransactionInfo.Direction.Direction_In
    val date = formatDate(transaction.timestamp * 1000)
    val label = TransactionHistoryLogic.rowLabel(transaction, fiatMode, fiatValue, date, receivedOn)
    val iconColor = if (isIncoming) SuccessGreen else MoneroOrange
    val amountPrefix = if (isIncoming) "+" else "-"

    val status = when {
        transaction.isFailed -> TransactionStatus.Failed
        transaction.confirmations == 0L -> TransactionStatus.Pending
        transaction.confirmations < 10 -> TransactionStatus.Locked
        else -> TransactionStatus.Confirmed
    }

    val statusColor = when (status) {
        TransactionStatus.Pending -> PendingOrange
        TransactionStatus.Locked -> MoneroOrange
        TransactionStatus.Confirmed -> SuccessGreen
        TransactionStatus.Failed -> ErrorRed
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = label }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Direction icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isIncoming) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp).rotate(45f)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isIncoming) tr("Received") else tr("Sent"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            one.monero.moneroone.ui.components.TransactionAmounts(
                transaction = transaction,
                fiatMode = fiatMode,
                fiatValue = fiatValue
            )

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MoneroTheme.colors.labelTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < TimeUnit.DAYS.toMillis(1) -> {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
        }
        diff < TimeUnit.DAYS.toMillis(7) -> {
            SimpleDateFormat("EEE, h:mm a", Locale.getDefault()).format(Date(timestamp))
        }
        else -> {
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
