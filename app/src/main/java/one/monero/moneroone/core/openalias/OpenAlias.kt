package one.monero.moneroone.core.openalias

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Tunables of the OpenAlias send flow, kept in one place (spec section 3.6). */
object OpenAliasConfig {
    const val KIT_TIMEOUT_DIRECT_MS = 12_000
    const val KIT_TIMEOUT_TOR_MS = 30_000
    const val DOH_TIMEOUT_DIRECT_MS = 5_000L
    const val DOH_TIMEOUT_TOR_MS = 15_000L
    const val QUERY_CAP = 64
    const val TYPING_DELAY_MS = 800L
    const val PIN_CAP = 500
    const val NAME_DISPLAY_CAP = 40
    const val FADE_MS = 200

    /** DoH servers in order: the next one is used after a transport failure (decision D3). */
    val DOH_SERVERS = listOf(
        "https://dns.quad9.net/dns-query",
        "https://dns.mullvad.net/dns-query",
    )
}

/** What the text in the recipient field is (spec section 4.1). */
sealed class RecipientInput {
    data object Empty : RecipientInput()
    data class Address(val text: String) : RecipientInput()
    data class Name(val name: OpenAliasName) : RecipientInput()
    data object Reserved : RecipientInput()
    data object InvalidName : RecipientInput()
}

/** [display]: as typed, in lowercase, with its `@` kept. [query]: the DNS name to look up. */
data class OpenAliasName(val display: String, val query: String)

enum class AddressCheck { VALID, OTHER_NETWORK, INVALID }

enum class DnssecStatus { VALID, OFF }

enum class OpenAliasFailure {
    NO_RECORD, SEVERAL_ADDRESSES, PAYMENT_ID, INVALID_ADDRESS, WRONG_NETWORK,
    DNSSEC_FAILED, TIMEOUT, NETWORK, TOR;

    /** The errors that a second try can fix. */
    val canRetry: Boolean get() = this == TIMEOUT || this == NETWORK || this == TOR
}

/** [recipientName] is parsed and sanitized, and the app shows it nowhere (decision D11). */
data class OpenAliasResult(val address: String, val recipientName: String?, val dnssec: DnssecStatus)

/** [pinnedAt] is in Unix seconds. */
sealed class PinState {
    data object None : PinState()
    data class Same(val pinnedAt: Long) : PinState()
    data class Changed(val pinnedAt: Long) : PinState()
}

sealed class OpenAliasOutcome {
    data class Resolved(val result: OpenAliasResult) : OpenAliasOutcome()
    data class Failed(val failure: OpenAliasFailure) : OpenAliasOutcome()
}

/** What the Send flow keeps after Continue on a resolved name. */
data class OpenAliasSummary(
    val name: OpenAliasName,
    val address: String,
    val dnssec: DnssecStatus,
    val pin: PinState,
)

/** One parsed `oa1:xmr` record. [addresses] keeps every `recipient_address` value. */
data class Oa1Record(val addresses: List<String>, val recipientName: String?, val paymentId: String?)

/** OpenAlias input and record rules (spec section 4). Pure: no I/O. */
object OpenAlias {

    private val excludedTopLabels = setOf("onion", "local", "localhost", "invalid", "arpa")

    /** Classifies the recipient field text (spec section 4.1). */
    fun classify(text: String): RecipientInput {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return RecipientInput.Empty
        if (trimmed.startsWith("monero:", ignoreCase = true)) return RecipientInput.Address(trimmed)
        if ('.' !in trimmed && '@' !in trimmed) return RecipientInput.Address(trimmed)
        if (trimmed.startsWith('@') && '.' !in trimmed) return RecipientInput.Reserved
        if (trimmed.any { it.code !in 0x21..0x7E }) return RecipientInput.InvalidName

        val display = trimmed.lowercase(Locale.ROOT).removeSuffix(".")
        val query = when (display.count { it == '@' }) {
            0 -> display
            1 -> {
                val local = display.substringBefore('@')
                val domain = display.substringAfter('@')
                if (local.isEmpty() || domain.isEmpty()) return RecipientInput.InvalidName
                "$local.$domain"
            }
            else -> return RecipientInput.InvalidName
        }

        if (query.length > 253) return RecipientInput.InvalidName
        val labels = query.split('.')
        if (labels.size < 2 || !labels.all(::isValidLabel)) return RecipientInput.InvalidName
        val top = labels.last()
        val topOk = (top.length >= 2 && top.all { it in 'a'..'z' }) || top.startsWith("xn--")
        if (!topOk || top in excludedTopLabels) return RecipientInput.InvalidName

        if (query == "monero.one" || query.endsWith(".monero.one")) return RecipientInput.Reserved
        return RecipientInput.Name(OpenAliasName(display = display, query = query))
    }

    private fun isValidLabel(label: String): Boolean =
        label.length in 1..63 &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' } &&
            !label.startsWith('-') && !label.endsWith('-')

    /**
     * Decodes one TXT RDATA, given as hex, to text (spec section 4.2). Returns
     * null for a record to drop: bad hex, empty RDATA, a length that runs past
     * the end, or bad UTF-8.
     */
    fun decodeTxt(hex: String): String? {
        val bytes = hexToBytes(hex) ?: return null
        if (bytes.isEmpty()) return null
        val joined = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val length = bytes[i].toInt() and 0xFF
            if (i + 1 + length > bytes.size) return null
            joined.write(bytes, i + 1, length)
            i += 1 + length
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(joined.toByteArray()))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val high = Character.digit(hex[2 * i], 16)
            val low = Character.digit(hex[2 * i + 1], 16)
            if (high < 0 || low < 0) return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    /**
     * Parses one record (spec section 4.3). Returns null when the record is not
     * `oa1:xmr` or is malformed.
     */
    fun parseOa1(text: String): Oa1Record? {
        if (text.length < 8 || !text.regionMatches(0, "oa1:xmr", 0, 7, ignoreCase = true) || text[7] != ' ') {
            return null
        }
        val addresses = mutableListOf<String>()
        var recipientName: String? = null
        var paymentId: String? = null
        val n = text.length
        var i = 8
        while (true) {
            while (i < n && text[i] == ' ') i++
            if (i >= n) break

            val equals = text.indexOf('=', i)
            val semicolon = text.indexOf(';', i)
            if (equals < 0 || (semicolon in 0 until equals)) {
                // A segment with no '=' before the next ';' is skipped.
                if (semicolon < 0) break
                i = semicolon + 1
                continue
            }
            val key = text.substring(i, equals).trim().lowercase(Locale.ROOT)
            i = equals + 1
            while (i < n && text[i] == ' ') i++

            val value = StringBuilder()
            if (i < n && text[i] == '"') {
                i++
                var closed = false
                while (i < n) {
                    val c = text[i]
                    if (c == '\\') {
                        if (i + 1 >= n) break
                        value.append(text[i + 1])
                        i += 2
                        continue
                    }
                    if (c == '"') {
                        closed = true
                        i++
                        break
                    }
                    value.append(c)
                    i++
                }
                if (!closed) return null
                while (i < n && text[i] == ' ') i++
                if (i < n) {
                    if (text[i] != ';') return null
                    i++
                }
            } else {
                while (i < n) {
                    val c = text[i]
                    if (c == '\\') {
                        if (i + 1 < n) value.append(text[i + 1])
                        i += 2
                        continue
                    }
                    if (c == ';') {
                        i++
                        break
                    }
                    value.append(c)
                    i++
                }
                val trimmedValue = value.toString().trim(' ')
                value.setLength(0)
                value.append(trimmedValue)
            }

            val v = value.toString()
            when (key) {
                "recipient_address" -> addresses += v
                "recipient_name" -> if (recipientName == null) recipientName = v
                "tx_payment_id" -> if (paymentId == null) paymentId = v
            }
        }
        return Oa1Record(addresses, recipientName, paymentId)
    }

    /** Selects the one address across the decoded records of an answer (spec section 4.4). */
    fun select(texts: List<String>, dnssec: DnssecStatus, check: (String) -> AddressCheck): OpenAliasOutcome {
        val records = texts.mapNotNull(::parseOa1)
        val addresses = records.flatMap { record -> record.addresses.filter { it.isNotEmpty() } }
        if (addresses.isEmpty()) return OpenAliasOutcome.Failed(OpenAliasFailure.NO_RECORD)
        if (addresses.toSet().size > 1) return OpenAliasOutcome.Failed(OpenAliasFailure.SEVERAL_ADDRESSES)
        if (records.any { !it.paymentId.isNullOrEmpty() }) return OpenAliasOutcome.Failed(OpenAliasFailure.PAYMENT_ID)

        val address = addresses.first()
        when (check(address)) {
            AddressCheck.VALID -> Unit
            AddressCheck.OTHER_NETWORK -> return OpenAliasOutcome.Failed(OpenAliasFailure.WRONG_NETWORK)
            AddressCheck.INVALID -> return OpenAliasOutcome.Failed(OpenAliasFailure.INVALID_ADDRESS)
        }

        val names = records.mapNotNull { it.recipientName?.takeIf(String::isNotEmpty) }.map(::sanitizeName)
        val name = if (names.toSet().size == 1) names.first() else null
        return OpenAliasOutcome.Resolved(OpenAliasResult(address, name, dnssec))
    }

    /**
     * Sanitizes a `recipient_name` (spec section 4.5). Whitespace (line breaks
     * included) becomes a space first, then Cc, Cf, Zl and Zp characters go,
     * then runs of spaces collapse. Caps at 40 code points plus "…". Returns
     * null when nothing is left.
     */
    fun sanitizeName(raw: String): String? {
        val mapped = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            if (isWhitespace(cp)) {
                mapped.append(' ')
                continue
            }
            when (Character.getType(cp)) {
                Character.CONTROL.toInt(),
                Character.FORMAT.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt() -> continue
            }
            mapped.appendCodePoint(cp)
        }
        val collapsed = mapped.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        if (collapsed.isEmpty()) return null
        val cap = OpenAliasConfig.NAME_DISPLAY_CAP
        if (collapsed.codePointCount(0, collapsed.length) <= cap) return collapsed
        return collapsed.substring(0, collapsed.offsetByCodePoints(0, cap)) + "…"
    }

    private fun isWhitespace(cp: Int): Boolean {
        if (cp in 0x09..0x0D || cp == 0x85) return true
        val type = Character.getType(cp)
        return type == Character.SPACE_SEPARATOR.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt()
    }

    /**
     * Maps the kit JSON and the bridge counts to one outcome (spec section 3.7).
     * The first rule that matches wins.
     */
    fun mapAnswer(
        json: String,
        synthesizedCount: Int,
        torOn: Boolean,
        check: (String) -> AddressCheck,
    ): OpenAliasOutcome {
        val netError = OpenAliasOutcome.Failed(if (torOn) OpenAliasFailure.TOR else OpenAliasFailure.NETWORK)
        val answer = parseAnswer(json) ?: return netError
        when (answer.status) {
            "timeout" -> return OpenAliasOutcome.Failed(OpenAliasFailure.TIMEOUT)
            "ok" -> Unit
            else -> return netError
        }
        val ok = answer.ok ?: return netError
        if (synthesizedCount > 0 && (ok.bogus || ok.rcode != 0)) return netError
        if (ok.bogus) return OpenAliasOutcome.Failed(OpenAliasFailure.DNSSEC_FAILED)
        if (ok.rcode == 3 || ok.nxdomain) return OpenAliasOutcome.Failed(OpenAliasFailure.NO_RECORD)
        if (ok.rcode != 0) return netError
        if (!ok.havedata || ok.records.isEmpty()) return OpenAliasOutcome.Failed(OpenAliasFailure.NO_RECORD)
        val texts = ok.records.mapNotNull(::decodeTxt)
        return select(texts, if (ok.secure) DnssecStatus.VALID else DnssecStatus.OFF, check)
    }

    private class KitAnswer(val status: String, val ok: KitOk?)

    private class KitOk(
        val rcode: Int,
        val havedata: Boolean,
        val nxdomain: Boolean,
        val secure: Boolean,
        val bogus: Boolean,
        val records: List<String>,
    )

    /** Null when the JSON fails to parse, or when an "ok" answer lacks a field. */
    private fun parseAnswer(json: String): KitAnswer? {
        val obj: JsonObject = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: Exception) {
            return null
        }
        val status = obj.string("status") ?: return null
        if (status != "ok") return KitAnswer(status, null)
        val records = (obj["records"] as? JsonArray)?.map { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        } ?: return null
        val ok = KitOk(
            rcode = (obj["rcode"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null,
            havedata = obj.bool("havedata") ?: return null,
            nxdomain = obj.bool("nxdomain") ?: return null,
            secure = obj.bool("secure") ?: return null,
            bogus = obj.bool("bogus") ?: return null,
            records = records,
        )
        return KitAnswer(status, ok)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}
