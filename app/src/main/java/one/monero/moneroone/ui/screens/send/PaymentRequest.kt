package one.monero.moneroone.ui.screens.send

import one.monero.moneroone.core.locale.tr
import java.net.URLDecoder
import java.math.BigDecimal
import java.util.Locale

data class MoneroUriData(
    val address: String,
    val amount: String? = null,
    val recipientName: String? = null,
    val description: String? = null,
    val paymentId: String? = null
)

/** Parsing is pure; callers supply the wallet library's checksum validation. */
fun parsePaymentRequest(input: String, validateAddress: (String) -> Boolean): MoneroUriData? {
    val raw = input.trim()
    if (raw.length > 8192 || '#' in raw) return null
    val body = if (raw.startsWith("monero:", true)) raw.substring(7) else raw
    val address = body.substringBefore('?')
    if (address.length !in listOf(95, 106) || address.firstOrNull() !in listOf('4', '8') || !validateAddress(address)) return null
    val params = mutableMapOf<String, String>()
    if ('?' in body) for (part in body.substringAfter('?').split('&')) {
        if (part.isEmpty()) continue
        val pair = part.split('=', limit = 2)
        if (pair.size != 2) return null
        val decoded = runCatching { URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") }.getOrNull() ?: return null
        val key = when (val k = decoded.first.lowercase(Locale.ROOT)) {
            "amount" -> "tx_amount"
            "description", "message" -> "tx_description"
            "payment_id" -> "tx_payment_id"
            else -> k
        }
        if (key.startsWith("req-") || params.put(key, decoded.second) != null) return null
    }
    // Separate payment IDs cannot be carried by the current send API. Never silently discard one.
    if (params["tx_payment_id"]?.isNotEmpty() == true) return null
    val amount = params["tx_amount"]
    if (amount != null) {
        if (!amount.matches(Regex("[0-9]+(?:\\.[0-9]{1,12})?"))) return null
        val atomic = runCatching { BigDecimal(amount).movePointRight(12).longValueExact() }.getOrNull() ?: return null
        if (atomic <= 0) return null
    }
    return MoneroUriData(address, amount, params["recipient_name"], params["tx_description"])
}
