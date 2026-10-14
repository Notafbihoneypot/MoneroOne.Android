package one.monero.moneroone.paymentlinks

import io.horizontalsystems.monerokit.CakeWalletStyleConverter
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32

/**
 * Payment link format v2 (2026-10-14): a 16-byte secret in place of the 25
 * seed words of v1, so the link is about 48 characters long:
 *
 *     https://monero.one/c#2<secret><height>
 *
 * - `2`: the format version, one character.
 * - `<secret>`: 16 random bytes read as one big-endian unsigned integer,
 *   written in Base58 (Monero/Bitcoin alphabet), left-padded with `1` to
 *   exactly 22 characters.
 * - `<height>`: the restore height as a Base58 integer, 1 to 11 characters.
 *
 * Derivation, the same on iOS and Android (labels are UTF-8):
 * - `k = SHA-256("monero.one payment link v2 spend" || secret)`
 * - envelope spend key = `sc_reduce32(k)`; the wallet restores from the 25
 *   legacy (Electrum, English) words of that reduced key. wallet2 derives
 *   the view key from the reduced spend key; a port that derives keys itself
 *   must hash the reduced key too (hashing the unreduced `k` gives another
 *   wallet: the spec's vector 2 "wrong" address).
 * - envelope id = lowercase hex of the first 8 bytes of
 *   `SHA-256("monero.one payment link v2 id" || secret)`.
 *
 * The link carries no amount and no expiry: the sender keeps the expiry in
 * its own record, and the recipient learns the amount from the envelope.
 * Spec: docs/2026-10-03-payment-links-spec.md, "Link format v2".
 */
object EnvelopeV2 {
    const val VERSION = '2'
    const val SECRET_BYTES = 16
    const val SECRET_LENGTH = 22

    /** Base58 digits of UInt64.max. */
    const val MAX_HEIGHT_LENGTH = 11

    private const val SPEND_LABEL = "monero.one payment link v2 spend"
    private const val ID_LABEL = "monero.one payment link v2 id"

    private val random = SecureRandom()

    /** 16 random bytes. */
    fun newSecret(): ByteArray = ByteArray(SECRET_BYTES).also { random.nextBytes(it) }

    /** `k = SHA-256(spendLabel || secret)`, 32 bytes. */
    fun k(secret: ByteArray): ByteArray = sha256(SPEND_LABEL.toByteArray(StandardCharsets.UTF_8) + secret)

    /** The envelope's secret spend key: `sc_reduce32(k)`. */
    fun spendKey(secret: ByteArray): ByteArray = Ed25519Scalar.reduce32(k(secret))

    /** The 25 legacy words the envelope wallet restores from; null for a zero spend key (never in practice). */
    fun words(secret: ByteArray): List<String>? {
        val spend = spendKey(secret)
        if (spend.all { it == 0.toByte() }) return null
        return LegacyWords.encode(spend)
    }

    /** Envelope id: first 8 bytes of `SHA-256(idLabel || secret)`, lowercase hex. */
    fun id(secret: ByteArray): String =
        sha256(ID_LABEL.toByteArray(StandardCharsets.UTF_8) + secret).copyOf(8).toHex()

    /** The fragment after `#`: `2<secret><height>`. Null for a secret that is not 16 bytes or a negative height. */
    fun fragment(secret: ByteArray, height: Long): String? {
        if (secret.size != SECRET_BYTES || height < 0) return null
        val encoded = Base58Integer.encode(secret, SECRET_LENGTH) ?: return null
        return "$VERSION$encoded${Base58Integer.encode(height.toULong())}"
    }

    /**
     * Reads the part after the version character: 22 secret characters, then 1
     * to 11 height characters, every one in the Base58 alphabet. Null for a
     * wrong length, a character outside the alphabet or a value that does not
     * fit (secret ≥ 2^128, height > UInt64.max, or, on Android, > Long.MAX_VALUE).
     */
    fun parseBody(body: String): Pair<ByteArray, Long>? {
        if (body.length <= SECRET_LENGTH || body.length > SECRET_LENGTH + MAX_HEIGHT_LENGTH) return null
        val secret = Base58Integer.decode(body.substring(0, SECRET_LENGTH), SECRET_BYTES) ?: return null
        val height = Base58Integer.decodeULong(body.substring(SECRET_LENGTH)) ?: return null
        if (height > Long.MAX_VALUE.toULong()) return null
        return secret to height.toLong()
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/**
 * Unsigned integers in Base58, most significant digit first, with the
 * Monero/Bitcoin alphabet. Not Monero's block Base58 (for addresses) and not
 * Bitcoin's leading-zero-byte rule: the value is one number, padded with `1`
 * (digit 0) to a fixed width where one is asked.
 */
object Base58Integer {
    const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = 58.toULong()

    private fun digit(c: Char): Int = ALPHABET.indexOf(c)

    /** Shortest form, at least one character ("1" for zero). */
    fun encode(value: ULong): String {
        var rest = value
        val out = StringBuilder()
        do {
            out.append(ALPHABET[(rest % BASE).toInt()])
            rest /= BASE
        } while (rest > 0u)
        return out.reverse().toString()
    }

    /** Null for an empty text, a character outside the alphabet or a value past UInt64.max. */
    fun decodeULong(text: String): ULong? {
        if (text.isEmpty()) return null
        var value = 0.toULong()
        for (c in text) {
            val d = digit(c)
            if (d < 0) return null
            if (value > (ULong.MAX_VALUE - d.toULong()) / BASE) return null
            value = value * BASE + d.toULong()
        }
        return value
    }

    /** [bytes] as one big-endian integer, left-padded with `1` to [width]. Null when it needs more digits. */
    fun encode(bytes: ByteArray, width: Int): String? {
        var number = BigInteger(1, bytes)
        val fifty8 = BigInteger.valueOf(58)
        val digits = StringBuilder()
        while (number.signum() > 0) {
            val (q, r) = number.divideAndRemainder(fifty8)
            digits.append(ALPHABET[r.toInt()])
            number = q
        }
        if (digits.length > width) return null
        return "1".repeat(width - digits.length) + digits.reverse()
    }

    /** The big-endian bytes of [text], exactly [byteCount] long. Null for a bad character or a value that does not fit. */
    fun decode(text: String, byteCount: Int): ByteArray? {
        if (text.isEmpty()) return null
        var value = BigInteger.ZERO
        val fifty8 = BigInteger.valueOf(58)
        for (c in text) {
            val d = digit(c)
            if (d < 0) return null
            value = value.multiply(fifty8).add(BigInteger.valueOf(d.toLong()))
        }
        if (value.bitLength() > byteCount * 8) return null
        val raw = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        if (raw.size > byteCount) return null
        return ByteArray(byteCount - raw.size) + raw
    }
}

/** Scalars mod l, the order of the ed25519 base point, as 32 little-endian bytes. */
object Ed25519Scalar {
    /** l = 2^252 + 27742317777372353535851937790883648493. */
    val L: BigInteger = BigInteger("1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed", 16)

    /** `sc_reduce32`: the 32 bytes as a little-endian integer, mod l. */
    fun reduce32(bytes: ByteArray): ByteArray {
        require(bytes.size == 32) { "Expected 32 bytes" }
        return toLittleEndian32(fromLittleEndian(bytes).mod(L))
    }

    fun fromLittleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    fun toLittleEndian32(value: BigInteger): ByteArray {
        val big = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        require(big.size <= 32) { "Value exceeds 32 bytes" }
        return (ByteArray(32 - big.size) + big).reversedArray()
    }
}

/**
 * The legacy (Electrum-style, English) 25-word encoding of a 32-byte key:
 * 3 words per 4 bytes, then the checksum word (CRC32 of the 3-letter
 * prefixes). The same algorithm as wallet2's `bytes_to_words`.
 */
object LegacyWords {
    private const val PREFIX_LENGTH = 3
    private val list: Array<String> get() = CakeWalletStyleConverter.MONERO_WORDLIST
    private val index: Map<String, Int> by lazy { list.withIndex().associate { (i, w) -> w to i } }

    fun encode(key: ByteArray): List<String> {
        require(key.size == 32) { "Expected 32 bytes" }
        val n = list.size.toLong()
        val words = ArrayList<String>(25)
        for (i in 0 until 8) {
            val value = (key[i * 4].toLong() and 0xff) or
                ((key[i * 4 + 1].toLong() and 0xff) shl 8) or
                ((key[i * 4 + 2].toLong() and 0xff) shl 16) or
                ((key[i * 4 + 3].toLong() and 0xff) shl 24)
            val w1 = value % n
            val w2 = (value / n + w1) % n
            val w3 = (value / n / n + w2) % n
            words += list[w1.toInt()]
            words += list[w2.toInt()]
            words += list[w3.toInt()]
        }
        words += words[checksumIndex(words)]
        return words
    }

    /** The 32 bytes of 25 words (or 24 without the checksum word); null for a bad word, checksum or triple. */
    fun decode(words: List<String>): ByteArray? {
        if (words.size != 24 && words.size != 25) return null
        val n = list.size.toLong()
        val out = ByteArray(32)
        for (i in 0 until 8) {
            val w1 = index[words[i * 3]]?.toLong() ?: return null
            val w2 = index[words[i * 3 + 1]]?.toLong() ?: return null
            val w3 = index[words[i * 3 + 2]]?.toLong() ?: return null
            val value = w1 + n * ((n - w1 + w2) % n) + n * n * ((n - w2 + w3) % n)
            if (value % n != w1 || value > 0xffffffffL) return null
            for (b in 0 until 4) out[i * 4 + b] = ((value shr (8 * b)) and 0xff).toByte()
        }
        if (words.size == 25 && words[24] != words[checksumIndex(words.subList(0, 24))]) return null
        return out
    }

    private fun checksumIndex(first24: List<String>): Int {
        val prefixes = StringBuilder()
        for (word in first24.take(24)) prefixes.append(word.take(PREFIX_LENGTH))
        val crc = CRC32().apply { update(prefixes.toString().toByteArray(StandardCharsets.UTF_8)) }.value
        return (crc % 24).toInt()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Bytes from hex (either case); null for an odd length or a non-hex character. */
internal fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(hex[i * 2], 16)
        val lo = Character.digit(hex[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
