package one.monero.moneroone.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.network.*
import one.monero.moneroone.ui.components.*
import one.monero.moneroone.ui.theme.MoneroOrange

@Composable
internal fun TorProxySection(config: TorConfig, onRetry: () -> Unit = {}, onChange: (TorConfig) -> Unit) {
    var confirmEnable by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var checkedAddress by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var checkAttempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(config, checkAttempt) {
        if (!config.enabled) { available = null; checking = false; return@LaunchedEffect }
        checking = true
        available = null
        checkedAddress = config.address
        try { available = TorProxyProbe.available(config.address) } finally { checking = false }
    }

    SettingsSectionHeader(tr("Tor Proxy"))
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 16.dp, shadow = false) {
        Column {
            val toggle: (Boolean) -> Unit = { if (it) confirmEnable = true else onChange(config.copy(enabled = false)) }
            // One TalkBack element with title, subtitle and switch state, as VoiceOver reads the iOS Toggle.
            // Touch still goes to the switch alone.
            Row(Modifier.padding(16.dp).semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = ToggleableState(config.enabled)
                onClick { toggle(!config.enabled); true }
            }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("Use Tor Proxy"), style = MaterialTheme.typography.titleSmall)
                    Text(tr("Route traffic through SOCKS5 proxy"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.clearAndSetSemantics {}) { MoneroSwitch(checked = config.enabled, onCheckedChange = toggle) }
            }
            if (config.enabled) {
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tr("Proxy address"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(config.address, style = MaterialTheme.typography.bodyLarge)
                    }
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, tr("Edit Proxy"), tint = MoneroOrange) }
                }
                Row(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(when {
                        checking -> "Checking..."
                        checkedAddress == config.address && available == true -> "Proxy available"
                        else -> "Proxy unavailable. Start Orbot or check the proxy address."
                    }), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { checkAttempt++; onRetry() }, enabled = !checking) { Text(tr("Retry")) }
                }
            }
        }
    }
    Text(tr(if (config.enabled) "Wallet sync, node checks, and prices use this proxy. If it is unavailable, connections stay offline."
            else "Requires Orbot or a local Tor daemon"),
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (confirmEnable) AlertDialog(
        onDismissRequest = { confirmEnable = false }, title = { Text(tr("Tor Proxy Required")) },
        text = { Text(tr("Monero One does not include Tor. Start Orbot or another Tor proxy before turning this on.")) },
        confirmButton = { TextButton(onClick = {
            confirmEnable = false
            if (parseProxyEndpoint(config.address) != null) onChange(config.copy(enabled = true)) else editing = true
        }) { Text(tr("Turn On")) } },
        dismissButton = { DismissTextButton(onClick = { confirmEnable = false }) { Text(tr("Cancel")) } }
    )
    if (editing) {
        var input by remember { mutableStateOf(config.address) }
        val endpoint = parseProxyEndpoint(input)
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(tr("Edit Proxy")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneroTextField(value = input, onValueChange = { input = it }, singleLine = true,
                    label = { Text(tr("Proxy address")) }, placeholder = { Text(DEFAULT_TOR_PROXY) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = endpoint == null)
                Text(tr("Use an IP address and port, for example 127.0.0.1:9050."), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(enabled = endpoint != null, onClick = {
                endpoint?.let { onChange(config.copy(address = it.address)); editing = false }
            }) { Text(tr("Save")) } },
            dismissButton = { DismissTextButton(onClick = { editing = false }) { Text(tr("Cancel")) } })
    }
}
