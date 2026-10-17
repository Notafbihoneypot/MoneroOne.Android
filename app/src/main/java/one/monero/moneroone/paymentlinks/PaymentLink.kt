package one.monero.moneroone.paymentlinks

import one.monero.moneroone.core.wallet.SeedValidation
import java.net.URLDecoder

/**
 * A payment link (envelope): the secret of a one-time wallet plus hints for
 * the claim. Both apps build and parse the same bytes
 * (docs/2026-10-03-payment-links-spec.md). New links are v2 ([EnvelopeV2],
 * about 55 characters), with the body behind a fragment directive:
 *
 *     https://monero.one/c#2:~:m1=<secret><height>
 *
 * WebKit hides everything after `:~:` from the page's script, so the page
 * sees only "2" (Chromium still exposes it through its navigation APIs: an
 * extra layer, not a boundary). Every link the app makes has this shape.
 *
 * Older shapes still parse and claim (links made before must keep working):
 *
 *     https://monero.one/c#2<secret><height>
 *     https://monero.one/c#v=1&s=<words>&h=<height>&a=<atomic>&e=<expiry>&n=<id>
 *
 * The secret lives only in the fragment, so it never reaches a server.
 * [toString] never prints the words or the secret: the link is cash to whoever holds it.
 */
class PaymentLink private constructor(
    /** 25 lowercase English legacy words the envelope restores from. v1: from the link; v2: derived from the secret. */
    val words: List<String>,
    /** Restore height: the sender's daemon height at creation minus 10, floor 0. */
    val height: Long,
    /** The amount the sender meant, in atomic units. A display hint: the chain is the truth. 0 in v2. */
    val amount: Long,
    /** Advisory expiry, unix seconds. 0 in v2, which carries none. */
    val expiry: Long,
    /** Envelope ID, 16 lowercase hex chars. The dedupe key on both sides. v1: `n`; v2: derived. */
    val id: String,
    private val secretBytes: ByteArray?
) {
    /** A v1 link. */
    constructor(words: List<String>, height: Long, amount: Long, expiry: Long, id: String) :
        this(words, height, amount, expiry, id, null)

    /** 1 or 2. */
    val version: Int get() = if (secretBytes == null) 1 else 2

    /** The v2 secret (16 bytes), null for a v1 link. */
    val secret: ByteArray? get() = secretBytes?.copyOf()

    /** The v2 secret as lowercase hex, as records keep it; null for a v1 link. */
    val secretHex: String? get() = secretBytes?.toHex()

    /**
     * The https link: v2 `https://monero.one/c#2:~:m1=<secret><height>`, v1 keys
     * in the order v, s, h, a, e, n.
     */
    fun url(): String = when (val s = secretBytes) {
        null -> "$PREFIX#${v1Fragment()}"
        // The fragment's version stays in front for the page; its body moves behind the directive.
        else -> "$PREFIX#${EnvelopeV2.VERSION}$DIRECTIVE" + EnvelopeV2.fragment(s, height).orEmpty().drop(1)
    }

    private fun v1Fragment(): String = "v=1&s=${words.joinToString("-")}&h=$height&a=$amount&e=$expiry&n=$id"

    override fun toString(): String = "PaymentLink(v=$version, n=$id, h=$height, a=$amount, e=$expiry, s=<redacted>)"

    override fun equals(other: Any?): Boolean =
        other is PaymentLink && other.words == words && other.height == height &&
            other.amount == amount && other.expiry == expiry && other.id == id &&
            (other.secretBytes?.contentEquals(secretBytes) ?: (secretBytes == null))

    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val PREFIX = "https://monero.one/c"

        /** The fragment directive the v2 body sits behind: `#2:~:m1=<body>`. */
        const val DIRECTIVE = ":~:m1="
        const val WORD_COUNT = 25
        private val ID = Regex("^[0-9a-f]{16}$")

        fun isValidId(id: String): Boolean = ID.matches(id)

        /** A v2 link. Null when [secret] is not 16 bytes or [height] is negative. */
        fun v2(secret: ByteArray, height: Long): PaymentLink? {
            if (secret.size != EnvelopeV2.SECRET_BYTES || height < 0) return null
            val words = EnvelopeV2.words(secret) ?: return null
            return PaymentLink(words, height, 0, 0, EnvelopeV2.id(secret), secret.copyOf())
        }

        /** A v2 link from a record's hex secret. */
        fun v2(secretHex: String, height: Long): PaymentLink? = hexToBytes(secretHex)?.let { v2(it, height) }
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
     * True when [text] is a claim link by its shape alone: `https://monero.one/c`,
     * with or without the scheme (pasted), then `#`, `?`, `/` or the end. Such text
     * opens the claim sheet, never the address field. `http` is not a claim link.
     */
    fun isClaimLink(text: String): Boolean = locationEnd(clean(text)) != null

    /**
     * Every shape: `#2:~:m1=<body>` (new), `#2<body>` (before the directive),
     * `#v=1&...` (v1), after any of the accepted prefixes.
     */
    fun parse(text: String): PaymentLinkParse {
        val trimmed = clean(text)
        val end = locationEnd(trimmed) ?: return PaymentLinkParse.NotALink
        val rest = trimmed.substring(end)
        val hash = rest.indexOf('#')
        if (hash < 0) return PaymentLinkParse.Incomplete
        val fragment = rest.substring(hash + 1)
        val directive = fragment.indexOf(":~:")
        if (directive >= 0) return parseDirective(fragment.substring(0, directive), fragment.substring(directive + 3))
        // A fragment with `=` is the v1 key-value format; one without is the short format.
        if ('=' !in fragment) return parseShort(fragment)
        val fields = readFields(fragment)

        // Only a whole version number above 1 asks for an update: "v=" alone is a link cut short.
        val version = fields["v"]?.let(::readDecimal) ?: return PaymentLinkParse.Incomplete
        if (version != 1L) return if (version > 1L) PaymentLinkParse.NeedsUpdate else PaymentLinkParse.Incomplete

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

    /**
     * The short format, `2<secret><height>`. Its first character is the version:
     * another ASCII letter or digit is a newer link; anything else, or nothing,
     * is a link cut short or mangled.
     */
    private fun parseShort(fragment: String): PaymentLinkParse {
        val first = fragment.firstOrNull() ?: return PaymentLinkParse.Incomplete
        if (first != EnvelopeV2.VERSION) {
            return if (first in 'a'..'z' || first in 'A'..'Z' || first in '0'..'9') PaymentLinkParse.NeedsUpdate
            else PaymentLinkParse.Incomplete
        }
        val (secret, height) = EnvelopeV2.parseBody(fragment.substring(1)) ?: return PaymentLinkParse.Incomplete
        return PaymentLink.v2(secret, height)?.let { PaymentLinkParse.Valid(it) } ?: PaymentLinkParse.Incomplete
    }

    /**
     * The new shape's fragment, split at `:~:`: [head] is the version ("2"),
     * [directives] holds `m1=<body>` (other directives, `&`-joined, are ignored).
     */
    private fun parseDirective(head: String, directives: String): PaymentLinkParse {
        val first = head.singleOrNull() ?: return PaymentLinkParse.Incomplete
        if (first != EnvelopeV2.VERSION) {
            return if (first in 'a'..'z' || first in 'A'..'Z' || first in '0'..'9') PaymentLinkParse.NeedsUpdate
            else PaymentLinkParse.Incomplete
        }
        val body = directives.split('&').firstOrNull { it.startsWith("m1=") }?.substring(3)
            ?: return PaymentLinkParse.Incomplete
        return parseShort("$first$body")
    }

    /**
     * Surrounding space, and the sentence's punctuation when a chat app took it
     * with the link ("...: https://monero.one/c#2... ."): no link ends in one of these.
     */
    private fun clean(text: String): String = text.trim().trimEnd('.', ',', ';', '!', '?', ')')

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
