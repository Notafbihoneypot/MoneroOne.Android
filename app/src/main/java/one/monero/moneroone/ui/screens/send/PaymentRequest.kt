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

/**
 * Why a payment request was refused, with the copy iOS shows for it
 * (MoneroPaymentURI.ParseError).
 */
enum class PaymentRequestError {
    /** Not a payment link at all, a parameter without a value, or a key twice. */
    NOT_PAYMENT_LINK,
    INVALID_ADDRESS,
    INVALID_AMOUNT,
    /** Separate payment IDs cannot be carried by the current send API. */
    PAYMENT_ID;

    val message: String
        get() = when (this) {
            NOT_PAYMENT_LINK -> tr("This is not a valid Monero payment link.")
            INVALID_ADDRESS -> tr("This link has no valid Monero address.")
            INVALID_AMOUNT -> tr("The amount in this link is not a valid XMR amount.")
            PAYMENT_ID -> tr("This link uses a payment ID, which Monero no longer supports. Ask the recipient for a subaddress or an integrated address.")
        }
}

sealed interface PaymentRequestResult {
    data class Valid(val data: MoneroUriData) : PaymentRequestResult
    data class Invalid(val error: PaymentRequestError) : PaymentRequestResult
}

/** Parsing is pure; callers supply the wallet library's checksum validation. */
fun parsePaymentRequest(input: String, validateAddress: (String) -> Boolean): MoneroUriData? =
    (readPaymentRequest(input, validateAddress) as? PaymentRequestResult.Valid)?.data

/**
 * [parsePaymentRequest] with the reason for a refusal, so a payment link
 * can say what is wrong with it, as iOS does.
 */
fun readPaymentRequest(input: String, validateAddress: (String) -> Boolean): PaymentRequestResult {
    fun refuse(error: PaymentRequestError) = PaymentRequestResult.Invalid(error)
    val raw = input.trim()
    if (raw.length > 8192 || '#' in raw) return refuse(PaymentRequestError.NOT_PAYMENT_LINK)
    val body = if (raw.startsWith("monero:", true)) raw.substring(7) else raw
    val address = body.substringBefore('?')
    if (address.length !in listOf(95, 106) || address.firstOrNull() !in listOf('4', '8') || !validateAddress(address)) {
        return refuse(PaymentRequestError.INVALID_ADDRESS)
    }
    val params = mutableMapOf<String, String>()
    if ('?' in body) for (part in body.substringAfter('?').split('&')) {
        if (part.isEmpty()) continue
        val pair = part.split('=', limit = 2)
        if (pair.size != 2) return refuse(PaymentRequestError.NOT_PAYMENT_LINK)
        val decoded = runCatching { URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") }.getOrNull()
            ?: return refuse(PaymentRequestError.NOT_PAYMENT_LINK)
        val key = when (val k = decoded.first.lowercase(Locale.ROOT)) {
            "amount" -> "tx_amount"
            "description", "message" -> "tx_description"
            "payment_id" -> "tx_payment_id"
            else -> k
        }
        if (key.startsWith("req-") || params.put(key, decoded.second) != null) return refuse(PaymentRequestError.NOT_PAYMENT_LINK)
    }
    // Separate payment IDs cannot be carried by the current send API. Never silently discard one.
    if (params["tx_payment_id"]?.isNotEmpty() == true) return refuse(PaymentRequestError.PAYMENT_ID)
    val amount = params["tx_amount"]
    if (amount != null) {
        if (!amount.matches(Regex("[0-9]+(?:\\.[0-9]{1,12})?"))) return refuse(PaymentRequestError.INVALID_AMOUNT)
        val atomic = runCatching { BigDecimal(amount).movePointRight(12).longValueExact() }.getOrNull()
            ?: return refuse(PaymentRequestError.INVALID_AMOUNT)
        if (atomic <= 0) return refuse(PaymentRequestError.INVALID_AMOUNT)
    }
    return PaymentRequestResult.Valid(MoneroUriData(address, amount, params["recipient_name"], params["tx_description"]))
}
