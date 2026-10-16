package one.monero.moneroone.ui.screens.transactions

import one.monero.moneroone.core.locale.pluralTr
import one.monero.moneroone.core.wallet.ReceiveAddressLogic
import one.monero.moneroone.core.wallet.addressesOf
import one.monero.moneroone.data.model.PriceHistory
import one.monero.moneroone.data.util.MoneyFormat
import one.monero.moneroone.data.util.XmrFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material.icons.filled.Link
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
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
import one.monero.moneroone.paymentlinks.PaymentLinkIndex
import one.monero.moneroone.paymentlinks.PaymentLinksViewModel
import one.monero.moneroone.paymentlinks.PaymentLinkActivityRules
import one.monero.moneroone.paymentlinks.PaymentLinkStore
import one.monero.moneroone.paymentlinks.ReceivedLink
import one.monero.moneroone.ui.components.ActivityRow
import one.monero.moneroone.ui.components.TransactionAmounts
import one.monero.moneroone.ui.components.amountFiat
import one.monero.moneroone.ui.components.fiatAt
import one.monero.moneroone.ui.components.transactionStatus
import one.monero.moneroone.paymentlinks.LinkRowAmounts
import one.monero.moneroone.paymentlinks.linkRowText
import one.monero.moneroone.paymentlinks.rememberDaemonHeight
import one.monero.moneroone.data.model.Currency
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import one.monero.moneroone.paymentlinks.rememberPaymentLinkIndex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class TransactionFilter(val label: String) {
    ALL("All"),
    RECEIVED("Received"),
    SENT("Sent"),
    PENDING("Pending"),

    /** Payment link transactions (funding, reclaim, claim) and the received links waiting to be claimed above them. */
    LINKS("Links")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TransactionListScreen(
    walletViewModel: WalletViewModel,
    onBack: () -> Unit,
    onTransactionClick: (String) -> Unit,
    /** Checks sent links' envelopes while the Links filter shows. */
    paymentLinks: PaymentLinksViewModel? = null,
    /** From History: only transactions up to this moment (ms); null for all. */
    asOf: Long? = null,
    /** The filter it opens on: Links from the balance card's open-links line. */
    initialFilter: TransactionFilter = TransactionFilter.ALL
) {
    val walletState by walletViewModel.walletState.collectAsState()
    val fiatMode by walletViewModel.fiatMode.collectAsState()
    val priceHistory by walletViewModel.priceHistory.collectAsState()
    val currentPrice by walletViewModel.currentPrice.collectAsState()
    val currency by walletViewModel.selectedCurrency.collectAsState()
    val activeWallet by walletViewModel.activeWallet.collectAsState()
    var receivingIndex by rememberSaveable(activeWallet?.id) { mutableStateOf<Int?>(null) }
    var showAddresses by remember { mutableStateOf(false) }
    var selectedFilter by rememberSaveable(activeWallet?.id) { mutableStateOf(initialFilter) }
    var searchQuery by remember { mutableStateOf("") }
    val linkIndex = rememberPaymentLinkIndex()

    // Opened from a past moment in History, the list stops there.
    val transactions = remember(walletState.transactions, asOf) { TransactionHistoryLogic.through(asOf, walletState.transactions) }
    val addressRows = remember(walletState.addresses, transactions, activeWallet?.addressLabels) {
        ReceiveAddressLogic.rows(walletState.addressesOf(activeWallet?.id)?.list.orEmpty(), transactions, activeWallet?.addressLabels.orEmpty())
    }
    val filteredTransactions = remember(transactions, selectedFilter, searchQuery, receivingIndex, addressRows, linkIndex) {
        TransactionHistoryLogic.filter(transactions, selectedFilter, receivingIndex, searchQuery, addressRows) { linkIndex.match(it) != null }
    }
    // Under Links (now only, no search), the received links waiting to be claimed head the list: they
    // have no transaction yet. A sent link shows once, as its "Sent by Link" row. No other filter shows them.
    val openReceived = if (asOf == null && searchQuery.isBlank() && receivingIndex == null &&
        selectedFilter == TransactionFilter.LINKS
    ) PaymentLinkActivityRules.pendingRecords(linkIndex.received) else emptyList()
    val linkStore = PaymentLinkStore.get(LocalContext.current)

    // Under Links (now only), look at sent links someone could have claimed, so their rows say so. A look
    // holds the wallet runtime for a moment, so no other filter runs it; it stops in the background.
    if (paymentLinks != null && asOf == null && selectedFilter == TransactionFilter.LINKS) {
        LifecycleStartEffect(paymentLinks) {
            paymentLinks.refreshSent()
            onStopOrDispose { paymentLinks.stopRefresh() }
        }
    }
    val dates = rememberChartDateFormats()
    val totals = remember(filteredTransactions) { TransactionHistoryLogic.totals(filteredTransactions) }
    val fiatTotals = remember(filteredTransactions, priceHistory, currentPrice) {
        TransactionHistoryLogic.fiatTotals(filteredTransactions) {
            PriceHistory.priceAt(it, priceHistory, currentPrice?.price, System.currentTimeMillis() / 1000)
        }
    }

    // Totals of what the list shows. Under Links it follows the received links waiting to be claimed.
    val summaryCard: @Composable () -> Unit = {
        GlassCard(Modifier.fillMaxWidth(), shadow = false, cornerRadius = 16.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (selectedFilter == TransactionFilter.LINKS) pluralTr("%s link transactions", totals.count) else pluralTr("%s transactions", totals.count), style = MaterialTheme.typography.titleSmall)
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
                    // The row scrolls (Links ends past the edge on a phone): the selected chip shows whole.
                    val requester = remember { BringIntoViewRequester() }
                    if (selectedFilter == filter) LaunchedEffect(Unit) { requester.bringIntoView() }
                    // Chips: capsules, the selected one tinted in the brand hue.
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        modifier = Modifier.bringIntoViewRequester(requester),
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
            if (filteredTransactions.isEmpty() && openReceived.isEmpty()) {
                summaryCard()
                Spacer(Modifier.height(16.dp))
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
                        val links = searchQuery.isBlank() && selectedFilter == TransactionFilter.LINKS
                        Text(
                            text = when {
                                searchQuery.isNotBlank() -> tr("No matching transactions")
                                links -> tr("No payment links yet")
                                else -> tr("No transactions")
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val hint = when {
                            searchQuery.isNotBlank() -> tr("Try a different search term")
                            links -> tr("In Send, choose Link to make one.")
                            else -> null
                        }
                        if (hint != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(items = openReceived, key = { "openReceived-${it.id}" }) { link ->
                        ReceivedLinkListItem(
                            link = link,
                            fiatMode = fiatMode,
                            price = currentPrice?.price,
                            currency = currency,
                            onOpen = { linkStore.openClaim(link.id) }
                        )
                    }
                    if (openReceived.isNotEmpty()) item(key = "openReceivedGap") { Spacer(Modifier.height(8.dp)) }
                    item(key = "summary") {
                        summaryCard()
                        Spacer(Modifier.height(8.dp))
                    }
                    items(
                        items = filteredTransactions,
                        key = { it.hash }
                    ) { transaction ->
                        TransactionListItem(
                            transaction = transaction,
                            linkIndex = linkIndex,
                            fiatMode = fiatMode,
                            fiatValue = one.monero.moneroone.ui.components.transactionFiat(transaction, priceHistory, currentPrice?.price, currency),
                            fiatOf = { amountFiat(it, transaction.timestamp, priceHistory, currentPrice?.price, currency) },
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
    /** Payment link rows show the link's name, amount and status. */
    linkIndex: PaymentLinkIndex,
    fiatMode: Boolean,
    fiatValue: String?,
    /** An amount's value at the time of [transaction]. */
    fiatOf: (Long) -> String?,
    /** The subaddress an incoming payment arrived on, spoken only; null for sends and the main address. */
    receivedOn: String?,
    onClick: () -> Unit
) {
    val isIncoming = transaction.direction == TransactionInfo.Direction.Direction_In
    val date = formatDate(transaction.timestamp * 1000)
    val link = linkRowText(transaction, linkIndex, date, fiatMode, fiatOf, TransactionHistoryLogic.rowStatus(transaction))
    ActivityRow(
        incoming = isIncoming,
        title = link?.title ?: if (isIncoming) tr("Received") else tr("Sent"),
        subtitle = date,
        spoken = link?.spoken ?: TransactionHistoryLogic.rowLabel(transaction, fiatMode, fiatValue, date, receivedOn),
        onClick = onClick,
        linkBadge = link != null
    ) {
        if (link != null) {
            LinkRowAmounts(link, transactionStatus(transaction))
        } else {
            TransactionAmounts(transaction = transaction, fiatMode = fiatMode, fiatValue = fiatValue)
        }
    }
}

/**
 * A received link not claimed yet, under Links: the row of a received
 * payment (green ↙ disc with the link badge), its name over the date it was
 * opened, the amount a claim delivers as an estimate and Ready or Waiting.
 * Its tap opens the claim sheet.
 */
@Composable
private fun ReceivedLinkListItem(
    link: ReceivedLink,
    fiatMode: Boolean,
    /** The live XMR price, for the fiat amount; null while unknown. */
    price: Double?,
    currency: Currency,
    onOpen: () -> Unit
) {
    val daemonHeight = rememberDaemonHeight()
    val state = PaymentLinkActivityRules.pendingState(link, daemonHeight, System.currentTimeMillis())
    val date = formatDate(link.receivedAtMillis)
    val text = PaymentLinkActivityRules.pendingRow(link, state, date, fiatMode, link.claimAmount?.let { fiatAt(it, price, currency) })
    val hint = tr("Opens the link to claim it")
    ActivityRow(
        incoming = true,
        title = text.title,
        subtitle = date,
        spoken = text.spoken,
        onClick = onOpen,
        linkBadge = true,
        modifier = Modifier
            .testTag("activity.receivedLink.${link.id}")
            .semantics { onClick(label = hint) { onOpen(); true } }
    ) {
        LinkRowAmounts(text, null)
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
