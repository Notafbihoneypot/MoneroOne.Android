package one.monero.moneroone.paymentlinks

import one.monero.moneroone.core.wallet.SeedValidation
import java.net.URLDecoder

/**
 * A payment link (envelope): the 25-word legacy seed of a one-time wallet plus
 * display hints. Format v1, the same bytes on iOS and Android
 * (docs/2026-10-03-payment-links-spec.md):
 *
 *     https://monero.one/c#v=1&s=<words>&h=<height>&a=<atomic>&e=<expiry>&n=<id>
 *
 * The secret lives only in the fragment, so it never reaches a server.
 * [toString] never prints the words: the link is cash to whoever holds it.
 */
class PaymentLink(
    /** 25 lowercase English legacy words. */
    val words: List<String>,
    /** Restore height: the sender's daemon height at creation minus 10, floor 0. */
    val height: Long,
    /** The amount the sender meant, in atomic units. A display hint: the chain is the truth. */
    val amount: Long,
    /** Advisory expiry, unix seconds. */
    val expiry: Long,
    /** Envelope ID, 16 lowercase hex chars. The dedupe key on both sides. */
    val id: String
) {
    /** The link string. Keys in the order v, s, h, a, e, n. */
    fun url(): String =
        "$PREFIX#v=1&s=${words.joinToString("-")}&h=$height&a=$amount&e=$expiry&n=$id"

    override fun toString(): String = "PaymentLink(n=$id, h=$height, a=$amount, e=$expiry, s=<redacted>)"

    override fun equals(other: Any?): Boolean =
        other is PaymentLink && other.words == words && other.height == height &&
            other.amount == amount && other.expiry == expiry && other.id == id

    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val PREFIX = "https://monero.one/c"
        const val WORD_COUNT = 25
        private val ID = Regex("^[0-9a-f]{16}$")

        fun isValidId(id: String): Boolean = ID.matches(id)
    }
}

/** What a piece of text holds, as far as payment links go. */
sealed class PaymentLinkParse {
    data class Valid(val link: PaymentLink) : PaymentLinkParse()

    /** A claim link with a missing or bad key: "This link is not complete." */
    object Incomplete : PaymentLinkParse()

    /** A claim link of a format this app does not know: "Update Monero One to open this link." */
    object NeedsUpdate : PaymentLinkParse()

    /** Not a claim link at all (an address, a monero: URI, http, another site). */
    object NotALink : PaymentLinkParse()
}

object PaymentLinkCodec {

    private val DECIMAL = Regex("^[0-9]{1,19}$")
    private val WORD_SEPARATORS = Regex("[-+\\s]+")

    /**
     * True when [text] is a claim link by its shape alone: `https://monero.one/c`
     * or `monero.one/c` (pasted without a scheme), then `#`, `?`, `/` or the end.
     * Such text opens the claim sheet, never the address field. `http` is not a claim link.
     */
    fun isClaimLink(text: String): Boolean = locationEnd(text.trim()) != null

    fun parse(text: String): PaymentLinkParse {
        val trimmed = text.trim()
        val end = locationEnd(trimmed) ?: return PaymentLinkParse.NotALink
        val rest = trimmed.substring(end)
        val hash = rest.indexOf('#')
        if (hash < 0) return PaymentLinkParse.Incomplete
        val fields = readFields(rest.substring(hash + 1))

        val version = fields["v"] ?: return PaymentLinkParse.Incomplete
        if (version != "1") return PaymentLinkParse.NeedsUpdate

        val words = fields["s"]?.let(::readWords) ?: return PaymentLinkParse.Incomplete
        if (words.size != PaymentLink.WORD_COUNT) return PaymentLinkParse.Incomplete
        // A word typed wrong would restore a different, empty wallet: wallet2's checksum word catches it.
        if (SeedValidation.electrumSeedProblem(words) != null) return PaymentLinkParse.Incomplete

        val height = fields["h"]?.let(::readDecimal) ?: return PaymentLinkParse.Incomplete
        val amount = fields["a"]?.let(::readDecimal) ?: return PaymentLinkParse.Incomplete
        val expiry = fields["e"]?.let(::readDecimal) ?: return PaymentLinkParse.Incomplete
        val id = fields["n"]?.lowercase() ?: return PaymentLinkParse.Incomplete
        if (!PaymentLink.isValidId(id)) return PaymentLinkParse.Incomplete

        return PaymentLinkParse.Valid(PaymentLink(words, height, amount, expiry, id))
    }

    /** Index just past `monero.one/c` when [text] starts with an accepted claim-link prefix. */
    private fun locationEnd(text: String): Int? {
        val lower = text.lowercase()
        val start = when {
            lower.startsWith("https://") -> "https://".length
            lower.startsWith("http:") -> return null
            lower.contains("://") -> return null
            else -> 0
        }
        val location = "monero.one/c"
        if (!lower.startsWith(location, start)) return null
        val end = start + location.length
        if (end < text.length && text[end] !in "#?/") return null
        // "monero.one/c/" is the same page; anything after it on the path is another page.
        if (end < text.length && text[end] == '/' && end + 1 < text.length && text[end + 1] != '#') return null
        return end
    }

    /** First value of each key; unknown keys are ignored by the caller. */
    private fun readFields(fragment: String): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        for (pair in fragment.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = pair.substring(0, eq)
            if (key !in fields) fields[key] = pair.substring(eq + 1)
        }
        return fields
    }

    /** Words joined by `-`, `+`, `%20` or spaces, case-insensitive. */
    private fun readWords(value: String): List<String>? {
        val decoded = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: IllegalArgumentException) {
            return null
        }
        return decoded.split(WORD_SEPARATORS).filter { it.isNotEmpty() }.map { it.lowercase() }
    }

    private fun readDecimal(value: String): Long? =
        if (DECIMAL.matches(value)) value.toLongOrNull() else null
}
