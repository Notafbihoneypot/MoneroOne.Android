package one.monero.moneroone.ui.screens.receive

import one.monero.moneroone.core.locale.tr
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import one.monero.moneroone.core.wallet.ReceiveAddressLogic
import androidx.compose.foundation.layout.aspectRatio
import java.util.Locale
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import one.monero.moneroone.core.wallet.WalletViewModel
import one.monero.moneroone.core.wallet.addressesOf
import androidx.compose.ui.graphics.toArgb
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import one.monero.moneroone.R
import one.monero.moneroone.ui.components.CapsuleShape
import one.monero.moneroone.ui.components.GlassButton
import one.monero.moneroone.ui.components.GlassCard
import one.monero.moneroone.ui.components.MoneroTextField
import one.monero.moneroone.ui.theme.ErrorRed
import one.monero.moneroone.ui.theme.MonoCaption
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.truncateMiddle
import one.monero.moneroone.ui.theme.withNightMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    walletViewModel: WalletViewModel,
    onBack: () -> Unit,
    onSelectAddress: (() -> Unit)? = null
) {
    val walletState by walletViewModel.walletState.collectAsState()
    val activeWallet by walletViewModel.activeWallet.collectAsState()
    val context = LocalContext.current

    val selectedAddressIndex = walletState.receiveIndex
    val scope = rememberCoroutineScope()
    var creating by remember(activeWallet?.id) { mutableStateOf(false) }

    // Re-read when screen resumes (e.g., returning from AddressPickerScreen)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                walletViewModel.refreshAddresses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Only the addresses published for the active wallet: a kit still held for
    // the wallet just left must never show its addresses under this name.
    val addresses = walletState.addressesOf(activeWallet?.id)
    val keysUnavailable = addresses?.blocked == true
    val sub = addresses?.shownAddress(selectedAddressIndex)
    val address = sub?.address.orEmpty()
    val addressLabel = ReceiveAddressLogic.name(sub?.addressIndex ?: 0,
        activeWallet?.addressLabels?.get(sub?.addressIndex) ?: sub?.label.orEmpty())
    val addressRows = remember(addresses, walletState.transactions, activeWallet?.addressLabels) {
        ReceiveAddressLogic.rows(addresses?.list.orEmpty(), walletState.transactions, activeWallet?.addressLabels.orEmpty())
    }
    val canCreate = addresses?.complete == true && !keysUnavailable && !creating &&
        ReceiveAddressLogic.unusedAfterLastUsed(addressRows) < ReceiveAddressLogic.STOP_THRESHOLD
    val canShareAddress = address.isNotBlank() && !keysUnavailable

    var requestAmount by remember { mutableStateOf("") }
    var isFiatMode by remember { mutableStateOf(false) }
    var fiatAmount by remember { mutableStateOf("") }
    val currentPrice by walletViewModel.currentPrice.collectAsState()
    val selectedCurrency by walletViewModel.selectedCurrency.collectAsState()
    val xmrPrice = currentPrice?.price
    val currencySymbol = selectedCurrency.symbol

    val qrData = remember(address, requestAmount) {
        if (address.isBlank()) ""
        else if (requestAmount.isBlank()) address
        else "monero:$address?tx_amount=$requestAmount"
    }

    // Match the full payment request: an old address or amount must not remain
    // visible while the replacement QR code is generated off the main thread.
    var qrCode by remember { mutableStateOf<Pair<String, Bitmap>?>(null) }
    LaunchedEffect(qrData) {
        if (qrData.isNotBlank()) {
            val encodedRequest = qrData
            val bitmap = withContext(Dispatchers.Default) {
                generateQRCode(qrData, 1024, context)
            }
            qrCode = bitmap?.let { encodedRequest to it }
        } else {
            qrCode = null
        }
    }
    val qrBitmap = qrCode?.takeIf { it.first == qrData && canShareAddress }?.second

    // Copy and Share act only on a shown address (iOS disables both without one).
    val copyAddress = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copyText = if (requestAmount.isNotBlank()) qrData else address
        val clip = ClipData.newPlainText(tr("Monero Address"), copyText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, tr("Copied to clipboard"), Toast.LENGTH_SHORT).show()
    }
    val shareAddress = {
        val shareText = if (requestAmount.isNotBlank()) qrData else address
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(intent, tr("Share Address")))
    }

    QrFocusContainer(qrBitmap, qrData) { qrModifier, expandQr ->
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = tr("Receive XMR"),
                        style = MaterialTheme.typography.titleMedium
                    )
                },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // QR Code
            GlassCard(
                modifier = Modifier.size(280.dp).clickable(enabled = qrBitmap != null) { expandQr() },
                cornerRadius = 20.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = qrBitmap
                    if (keysUnavailable) {
                        KeysUnavailableMessage()
                    } else if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = tr("QR Code"),
                            modifier = Modifier
                                .fillMaxSize()
                                .then(qrModifier)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Text(
                            text = tr("Generating..."),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // "Request Amount (optional)" label row with currency toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("Request Amount (optional)"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                if (xmrPrice != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(CapsuleShape)
                            .background(MoneroOrange.copy(alpha = 0.15f))
                            .clickable {
                                if (isFiatMode) {
                                    isFiatMode = false
                                } else {
                                    val xmrVal = requestAmount.toDoubleOrNull() ?: 0.0
                                    fiatAmount = if (xmrVal > 0) "%.2f".format(Locale.US, xmrVal * xmrPrice) else ""
                                    isFiatMode = true
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("⇅", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MoneroOrange)
                        Text(
                            text = if (isFiatMode) "XMR" else selectedCurrency.code.uppercase(),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MoneroOrange
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Amount input field
            if (isFiatMode) {
                MoneroTextField(
                    value = fiatAmount,
                    onValueChange = { input ->
                        val it = input.replace(',', '.')
                        if (it.isEmpty() || it.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                            fiatAmount = it
                            val price = xmrPrice
                            if (price != null && price > 0) {
                                val fiatVal = it.toDoubleOrNull() ?: 0.0
                                val xmr = fiatVal / price
                                requestAmount = if (xmr > 0) "%.12f".format(Locale.US, xmr).trimEnd('0').trimEnd('.') else ""
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("0.00") },
                    prefix = { Text(currencySymbol, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingIcon = {
                        if (fiatAmount.isNotBlank()) {
                            IconButton(onClick = { fiatAmount = ""; requestAmount = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = tr("Clear"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            } else {
                MoneroTextField(
                    value = requestAmount,
                    onValueChange = { input ->
                        val it = input.replace(',', '.')
                        if (it.isEmpty() || it.matches(Regex("^\\d*\\.?\\d*$"))) requestAmount = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("0.0") },
                    suffix = { Text("XMR", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingIcon = {
                        if (requestAmount.isNotBlank()) {
                            IconButton(onClick = { requestAmount = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = tr("Clear"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Address card (truncated, matching iOS): radius 12 on the fill
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
                shadow = false,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = onSelectAddress != null) { onSelectAddress?.invoke() }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = addressLabel,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (address.length > 20) truncateMiddle(address)
                                else address.ifBlank { if (keysUnavailable) "" else tr("Loading...") },
                            style = MonoCaption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    addressRows.firstOrNull { it.index == sub?.addressIndex }?.let { row ->
                        Text(row.description, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp).width(100.dp))
                    }
                    if (onSelectAddress != null) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = tr("Select address"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(
                    onClick = {
                        creating = true
                        scope.launch {
                            try {
                                if (!walletViewModel.createSubaddress()) Toast.makeText(context, tr("Unable to create address"), Toast.LENGTH_SHORT).show()
                            } finally { creating = false }
                        }
                    }, enabled = canCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) {
                    if (creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("New Address"))
                }
                }
            }
            ReceiveAddressLogic.creationWarning(addressRows)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Copy / Share buttons (tall glass cards with icon above text, matching iOS)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlassButton(
                    modifier = Modifier
                        .weight(1f)
                        .height(90.dp)
                        .alpha(if (canShareAddress) 1f else DISABLED_ALPHA),
                    enabled = canShareAddress,
                    onClick = copyAddress
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(tr("Copy"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                GlassButton(
                    modifier = Modifier
                        .weight(1f)
                        .height(90.dp)
                        .alpha(if (canShareAddress) 1f else DISABLED_ALPHA),
                    enabled = canShareAddress,
                    onClick = shareAddress
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = MoneroOrange, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(tr("Share"), style = MaterialTheme.typography.labelLarge, color = MoneroOrange)
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
    }
}

/** Copy and Share while no address can be shown (iOS dims disabled buttons the same way). */
private const val DISABLED_ALPHA = 0.4f

/**
 * Shown in place of the QR code when the wallet's keys failed a check (iOS
 * ReceiveView keysUnavailable): no receive address may be shown.
 */
@Composable
internal fun KeysUnavailableMessage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = tr("Wallet keys unavailable. No receive address can be shown.")
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            tint = ErrorRed,
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = tr("Wallet keys unavailable"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = tr("The wallet couldn't load its keys, so no receive address can be shown. Do not send funds to any address from this app until this is resolved. Force-quit and reopen the app; if this persists, restore the wallet from its seed."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun generateQRCode(data: String, size: Int, context: Context): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H // High error correction for logo overlay
        )
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(
                    x, y,
                    if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                )
            }
        }

        // Add Monero logo overlay in center (22% of QR size)
        addMoneroLogoOverlay(bitmap, context)
    } catch (e: Exception) {
        null
    }
}

private fun addMoneroLogoOverlay(qrBitmap: Bitmap, context: Context): Bitmap {
    val size = qrBitmap.width
    val logoSize = (size * 0.22).toInt()
    val radius = logoSize / 2f

    val result = qrBitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(result)
    val cx = size / 2f
    val cy = size / 2f

    // White circle background (slightly larger for border)
    val bgPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
    }
    canvas.drawCircle(cx, cy, radius + 4f, bgPaint)

    // Draw logo into a circle-clipped bitmap. The disc is white in both modes,
    // so take the day art (its plate is white; the night plate is black).
    val logoDrawable = ContextCompat.getDrawable(context.withNightMode(false), R.drawable.monero_logo) ?: return result
    val clipped = Bitmap.createBitmap(logoSize, logoSize, Bitmap.Config.ARGB_8888)
    val clipCanvas = android.graphics.Canvas(clipped)

    // Circle clip mask
    val maskPaint = android.graphics.Paint().apply { isAntiAlias = true }
    clipCanvas.drawCircle(radius, radius, radius, maskPaint)
    maskPaint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)

    // Draw the drawable centered, filling the circle
    val logoBitmap = Bitmap.createBitmap(logoSize, logoSize, Bitmap.Config.ARGB_8888)
    val logoCanvas = android.graphics.Canvas(logoBitmap)
    logoDrawable.setBounds(0, 0, logoSize, logoSize)
    logoDrawable.draw(logoCanvas)
    clipCanvas.drawBitmap(logoBitmap, 0f, 0f, maskPaint)

    // Place centered on QR
    canvas.drawBitmap(clipped, cx - radius, cy - radius, null)
    return result
}
