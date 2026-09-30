package one.monero.moneroone.ui.screens.settings

import one.monero.moneroone.core.locale.tr
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.monero.moneroone.ui.components.FocusableQrPlate
import one.monero.moneroone.ui.components.GlassCard
import one.monero.moneroone.ui.components.QrFocusContainer
import one.monero.moneroone.ui.components.QrFocusItem
import one.monero.moneroone.ui.components.QrRecedeEdge
import one.monero.moneroone.ui.components.qrFocusRecede
import one.monero.moneroone.ui.components.PrimaryButton
import one.monero.moneroone.ui.theme.MonoCaption
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.SuccessGreen

private const val DONATION_ADDRESS = "86AWuSFkMKCNp4e7dWho3CBvFpvAzj8hnZNWM9fedD5LKb2mXVfnmH9XuDD9zYqzzR6LAFxUSsdGTVUDABzcgjMfFVfBHpP"
private const val SUGGESTED_DONATION_AMOUNT = "0.25"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonationScreen(
    onBack: () -> Unit,
    onSendXmr: (address: String, amount: String) -> Unit
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    // Back to the copy label after 2 s, as on iOS
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    QrFocusContainer { focus ->
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = tr("Donate XMR"),
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

            // The header steps back toward the top in focus mode (iOS DonationView).
            Column(
                Modifier.qrFocusRecede(focus, QrRecedeEdge.Top),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // Heart in the brand orange, as on iOS
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = MoneroOrange,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = tr("Support Development"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            }

            Spacer(modifier = Modifier.height(32.dp))

            // The code on its plate; a tap grows it into focus mode.
            FocusableQrPlate(
                item = QrFocusItem("monero:$DONATION_ADDRESS", tr("Donate")),
                side = 240.dp,
                focus = focus
            )

            Spacer(modifier = Modifier.height(24.dp))

            // The address and the buttons step back toward the bottom.
            Column(Modifier.fillMaxWidth().qrFocusRecede(focus, QrRecedeEdge.Bottom)) {

            // Address display: iOS radius-12 card on the fill
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
                shadow = false,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = tr("Monero Address"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // The whole address, wrapped rather than cut.
                    Text(
                        text = DONATION_ADDRESS,
                        style = MonoCaption,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Copy: glass with the brand label, green once copied (iOS)
                PrimaryButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(tr("Donation Address"), DONATION_ADDRESS)
                        clipboard.setPrimaryClip(clip)
                        copied = true
                        Toast.makeText(context, tr("Address copied"), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    contentColor = if (copied) SuccessGreen else MoneroOrange
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(text = if (copied) tr("Copied!") else tr("Copy"))
                }

                // Send XMR: glass with the brand label (iOS)
                PrimaryButton(
                    onClick = {
                        onSendXmr(DONATION_ADDRESS, SUGGESTED_DONATION_AMOUNT)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(text = tr("Send XMR"))
                }
            }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    }
}
