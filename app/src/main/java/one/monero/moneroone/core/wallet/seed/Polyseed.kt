package one.monero.moneroone.core.wallet.seed

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure Kotlin port of the Polyseed library (monero `external/polyseed/src`):
 * `polyseed.c` (decode, encode, keygen, str_split), `lang.c` (language
 * order, comparers, bsearch), `gf.c` (GF(2048) checksum, poly <-> data),
 * `features.c` and `birthday.h`. The golden vectors in the unit tests come
 * from the C library itself.
 *
 * The comparers work on UTF-8 bytes as signed chars, as on Apple and x86
 * (the targets the word lists were sorted and checked on).
 *
 * The C wrapper refuses phrases whose NFKD form is 360 bytes or longer
 * (POLYSEED_STR_SIZE). This port has no such limit: the app splits the
 * phrase into cells first, and some valid Japanese and Korean phrases come
 * close to it.
 */
object Polyseed {
    const val NUM_WORDS = 16
    const val LIST_SIZE = 2048
    const val COIN_MONERO = 0

    /** birthday.h: 1st November 2021 12:00 UTC. */
    const val EPOCH = 1_635_768_000L

    /** birthday.h: 30.436875 days, 1/12 of the Gregorian year. */
    const val TIME_STEP = 2_629_746L

    private const val KDF_NUM_ITERATIONS = 10_000
    private const val NUM_CHARS_PREFIX = 4
    private const val SECRET_BUFFER_SIZE = 32
    private const val SECRET_BITS = 150
    private const val SECRET_SIZE = 19
    private const val GF_BITS = 11
    private const val SHARE_BITS = 10
    private const val DATE_BITS = 10
    private const val DATE_MASK = (1 shl DATE_BITS) - 1
    private const val FEATURE_BITS = 5
    private const val FEATURE_MASK = (1 shl FEATURE_BITS) - 1
    private const val ENCRYPTED_MASK = 16

    /** features.c: everything but the encrypted bit is reserved (no user features enabled). */
    private const val RESERVED_FEATURES = FEATURE_MASK xor ENCRYPTED_MASK

    private val MUL2_TABLE = intArrayOf(5, 7, 1, 3, 13, 15, 9, 11)

    /** polyseed.h polyseed_status. */
    enum class Status(val code: Int) {
        OK(0),
        ERR_NUM_WORDS(1),
        ERR_LANG(2),
        ERR_CHECKSUM(3),
        ERR_UNSUPPORTED(4),
        ERR_FORMAT(5),
        ERR_MEMORY(6),
        ERR_MULT_LANG(7),
    }

    class Spec(
        val englishName: String,
        val file: String,
        val separator: String,
        val isSorted: Boolean,
        val hasPrefix: Boolean,
        val hasAccents: Boolean,
        val compose: Boolean
    )

    /** lang.c language order ("sorted wordlists first") and each lang_*.c's flags. */
    val SPECS = listOf(
        Spec("English", "polyseed/en.txt", " ", isSorted = true, hasPrefix = true, hasAccents = false, compose = false),
        Spec("Japanese", "polyseed/jp.txt", "　", isSorted = true, hasPrefix = false, hasAccents = false, compose = true),
        Spec("Korean", "polyseed/ko.txt", " ", isSorted = true, hasPrefix = false, hasAccents = false, compose = true),
        Spec("Spanish", "polyseed/es.txt", " ", isSorted = true, hasPrefix = true, hasAccents = true, compose = true),
        Spec("French", "polyseed/fr.txt", " ", isSorted = true, hasPrefix = true, hasAccents = true, compose = true),
        Spec("Italian", "polyseed/it.txt", " ", isSorted = true, hasPrefix = true, hasAccents = false, compose = false),
        Spec("Czech", "polyseed/cs.txt", " ", isSorted = true, hasPrefix = true, hasAccents = false, compose = false),
        Spec("Portuguese", "polyseed/pt.txt", " ", isSorted = true, hasPrefix = true, hasAccents = false, compose = false),
        Spec("Chinese (Simplified)", "polyseed/zh_s.txt", " ", isSorted = false, hasPrefix = false, hasAccents = false, compose = false),
        Spec("Chinese (Traditional)", "polyseed/zh_t.txt", " ", isSorted = false, hasPrefix = false, hasAccents = false, compose = false),
    )

    /** One Polyseed language. [words] are stored decomposed (NFKD), as in the C source. */
    class Language internal constructor(val spec: Spec, val words: List<String>) {
        private val wordBytes: Array<ByteArray> = Array(words.size) { words[it].toByteArray(StandardCharsets.UTF_8) }

        /** The words as polyseed_encode prints them (NFC for the languages that compose). */
        val displayWords: List<String> =
            if (spec.compose) words.map { Normalizer.normalize(it, Normalizer.Form.NFC) } else words

        init {
            require(words.size == LIST_SIZE) { "${spec.englishName}: ${words.size} words" }
        }

        /** polyseed_lang_find_word for a single word (any normalization). Returns -1 when absent. */
        fun find(word: String): Int = search(utf8Nfkd(word))

        /** lang_search: BSD bsearch on sorted lists, else the first linear match. */
        internal fun search(key: ByteArray): Int {
            if (spec.isSorted) {
                var base = 0
                var lim = LIST_SIZE
                while (lim != 0) {
                    val p = base + (lim shr 1)
                    val c = compare(key, wordBytes[p])
                    if (c == 0) return p
                    if (c > 0) {
                        base = p + 1
                        lim--
                    }
                    lim = lim shr 1
                }
                return -1
            }
            for (j in 0 until LIST_SIZE) if (compare(key, wordBytes[j]) == 0) return j
            return -1
        }

        /** get_comparer. */
        private fun compare(key: ByteArray, elm: ByteArray): Int = when {
            spec.hasPrefix && spec.hasAccents -> comparePrefixNoAccent(key, elm, NUM_CHARS_PREFIX)
            spec.hasPrefix -> comparePrefix(key, elm, NUM_CHARS_PREFIX)
            spec.hasAccents -> compareStrNoAccent(key, elm)
            else -> compareStr(key, elm)
        }

        override fun toString(): String = spec.englishName
    }

    /** polyseed_data. [secret] is the 32-byte buffer (150 bits used). */
    class Data internal constructor(
        val secret: ByteArray,
        val birthday: Int,
        val features: Int,
        val checksum: Int
    ) {
        val isEncrypted: Boolean get() = features and ENCRYPTED_MASK != 0

        /** polyseed_get_birthday: the creation time (Unix seconds) the phrase stores. */
        val birthdayUnix: Long get() = birthdayUnix(birthday)

        fun wipe() = secret.fill(0)
    }

    class Decoded(val status: Status, val language: Language?, val data: Data?)

    /** birthday_decode. */
    fun birthdayUnix(birthday: Int): Long = EPOCH + birthday * TIME_STEP

    /**
     * The block height to scan from for a Polyseed restored at [birthdayUnix]:
     * wallet2 `estimate_blockchain_height(seed.birthday())` with no daemon
     * connection, which is how iOS restores (`createWalletFromPolyseed` with
     * restore height 0, before the wallet connects): the mainnet v2-fork
     * estimate minus one month of blocks (21600), so it is never later
     * than the birthday.
     */
    fun restoreHeight(birthdayUnix: Long): Long {
        val forkTime = 1_458_748_658L
        val forkBlock = 1_009_827L
        val secondsPerBlock = 120L
        val blocksPerMonth = 60L * 60 * 24 * 30 / secondsPerBlock
        if (birthdayUnix < forkTime) return 0
        val approx = forkBlock + (birthdayUnix - forkTime) / secondsPerBlock
        return if (approx > blocksPerMonth) approx - blocksPerMonth else 0
    }

    /** polyseed_decode: NFKD, split on spaces, find the one language, checksum, features. */
    fun decode(
        phrase: String,
        coin: Int = COIN_MONERO,
        languages: List<Language> = SeedWordLists.polyseed
    ): Decoded {
        val words = splitWords(utf8Nfkd(phrase)) ?: return Decoded(Status.ERR_NUM_WORDS, null, null)

        var found: Language? = null
        var foundIdx: IntArray? = null
        for (language in languages) {
            val idx = IntArray(NUM_WORDS)
            var success = true
            for (wi in 0 until NUM_WORDS) {
                val value = language.search(words[wi])
                if (value < 0) {
                    success = false
                    break
                }
                idx[wi] = value
            }
            if (!success) continue
            if (found != null) return Decoded(Status.ERR_MULT_LANG, null, null)
            found = language
            foundIdx = idx
        }
        if (found == null || foundIdx == null) return Decoded(Status.ERR_LANG, null, null)

        val poly = foundIdx
        poly[1] = poly[1] xor coin
        if (eval(poly) != 0) return Decoded(Status.ERR_CHECKSUM, found, null)
        val data = polyToData(poly)
        poly.fill(0)
        if (data.features and RESERVED_FEATURES != 0) {
            data.wipe()
            return Decoded(Status.ERR_UNSUPPORTED, found, null)
        }
        return Decoded(Status.OK, found, data)
    }

    /** polyseed_encode: the phrase for [data] in [language] (words joined by its separator, NFC if it composes). */
    fun encode(data: Data, language: Language, coin: Int = COIN_MONERO): String {
        val poly = IntArray(NUM_WORDS)
        poly[0] = data.checksum
        dataToPoly(data, poly)
        poly[1] = poly[1] xor coin
        val joined = poly.joinToString(language.spec.separator) { language.words[it] }
        return if (language.spec.compose) Normalizer.normalize(joined, Normalizer.Form.NFC) else joined
    }

    /** The data polyseed_create would make for this secret, birthday and features (tests build phrases with it). */
    internal fun dataWithChecksum(secret: ByteArray, birthday: Int, features: Int): Data {
        val draft = Data(secret.copyOf(SECRET_BUFFER_SIZE), birthday and DATE_MASK, features, 0)
        val poly = IntArray(NUM_WORDS)
        dataToPoly(draft, poly)
        return Data(draft.secret, draft.birthday, draft.features, eval(poly))
    }

    /**
     * polyseed_keygen: PBKDF2-HMAC-SHA256 over the 32-byte secret buffer
     * with the "POLYSEED key" salt (coin, birthday and features), 10000
     * iterations, 32 bytes. Slow on purpose: call off Main.
     */
    fun keygen(data: Data, coin: Int = COIN_MONERO): ByteArray {
        val salt = ByteArray(32)
        "POLYSEED key".toByteArray(StandardCharsets.US_ASCII).copyInto(salt)
        salt[13] = 0xFF.toByte()
        salt[14] = 0xFF.toByte()
        salt[15] = 0xFF.toByte()
        store32(salt, 16, coin)
        store32(salt, 20, data.birthday)
        store32(salt, 24, data.features)
        return pbkdf2HmacSha256(data.secret, salt, KDF_NUM_ITERATIONS, 32)
    }

    /**
     * The spend key wallet2 derives from [data] (`account_base::create_from_polyseed`
     * with no passphrase: keygen, then `generate(key, recover = true)`, which
     * reduces it mod l). Call off Main.
     */
    fun spendKey(data: Data): ByteArray {
        val key = keygen(data)
        return Ed25519Scalar.reduce32(key).also { key.fill(0) }
    }

    /**
     * The 25-word English legacy seed of the same wallet (its spend key
     * through bytes_to_words). The kit restores Polyseed wallets with these
     * words. Call off Main.
     */
    fun legacyWords(data: Data): List<String> {
        val spend = spendKey(data)
        return LegacyMnemonic.bytesToWords(spend, SeedWordLists.englishLegacy).also { spend.fill(0) }
    }

    /** gf_elem_mul2. */
    private fun mul2(x: Int): Int =
        if (x < 1024) 2 * x else MUL2_TABLE[x % 8] + 16 * ((x - 1024) / 8)

    /** gf_poly_eval: Horner's method at x = 2. */
    internal fun eval(coeff: IntArray): Int {
        var result = coeff[NUM_WORDS - 1]
        for (i in NUM_WORDS - 2 downTo 0) result = mul2(result) xor coeff[i]
        return result
    }

    /** polyseed_data_to_poly: fills coefficients 1..15. */
    private fun dataToPoly(data: Data, poly: IntArray) {
        val extraVal = (data.features shl DATE_BITS) or data.birthday
        var extraBits = FEATURE_BITS + DATE_BITS
        var wordBits = 0
        var wordVal = 0
        var secretIdx = 0
        var secretVal = data.secret[secretIdx].toInt() and 0xFF
        var secretBits = 8
        var seedRemBits = SECRET_BITS - 8

        for (i in 0 until NUM_WORDS - 1) {
            while (wordBits < SHARE_BITS) {
                if (secretBits == 0) {
                    secretIdx++
                    secretBits = minOf(seedRemBits, 8)
                    secretVal = data.secret[secretIdx].toInt() and 0xFF
                    seedRemBits -= secretBits
                }
                val chunkBits = minOf(secretBits, SHARE_BITS - wordBits)
                secretBits -= chunkBits
                wordBits += chunkBits
                wordVal = wordVal shl chunkBits
                wordVal = wordVal or ((secretVal ushr secretBits) and ((1 shl chunkBits) - 1))
            }
            wordVal = wordVal shl 1
            extraBits--
            wordVal = wordVal or ((extraVal ushr extraBits) and 1)
            poly[1 + i] = wordVal
            wordVal = 0
            wordBits = 0
        }
        check(seedRemBits == 0 && secretBits == 0 && extraBits == 0)
    }

    /** polyseed_poly_to_data. */
    private fun polyToData(poly: IntArray): Data {
        val secret = ByteArray(SECRET_BUFFER_SIZE)
        var extraVal = 0
        var extraBits = 0
        var secretIdx = 0
        var secretBits = 0
        var seedBits = 0

        for (i in 1 until NUM_WORDS) {
            var wordVal = poly[i]
            extraVal = (extraVal shl 1) or (wordVal and 1)
            wordVal = wordVal ushr 1
            var wordBits = GF_BITS - 1
            extraBits++

            while (wordBits > 0) {
                if (secretBits == 8) {
                    secretIdx++
                    seedBits += secretBits
                    secretBits = 0
                }
                val chunkBits = minOf(wordBits, 8 - secretBits)
                wordBits -= chunkBits
                val chunkMask = (1 shl chunkBits) - 1
                var b = secret[secretIdx].toInt() and 0xFF
                if (chunkBits < 8) b = (b shl chunkBits) and 0xFF
                b = b or ((wordVal ushr wordBits) and chunkMask)
                secret[secretIdx] = b.toByte()
                secretBits += chunkBits
            }
        }
        seedBits += secretBits
        check(seedBits == SECRET_BITS && extraBits == FEATURE_BITS + DATE_BITS)
        return Data(secret, extraVal and DATE_MASK, extraVal ushr DATE_BITS, poly[0])
    }

    /**
     * str_split on the NFKD bytes: exactly 16 words split on single spaces
     * (one trailing space allowed, as in C), or null.
     */
    private fun splitWords(str: ByteArray): List<ByteArray>? {
        val end = str.indexOf(0.toByte()).let { if (it < 0) str.size else it }
        val words = ArrayList<ByteArray>(NUM_WORDS)
        var pos = 0
        var start = 0
        var count = 0
        while (pos < end) {
            while (pos < end && str[pos] != SPACE) pos++
            words += str.copyOfRange(start, pos)
            if (pos < end) pos++
            start = pos
            count++
            if (count == NUM_WORDS) {
                if (pos < end) count++ // too many words
                break
            }
        }
        return if (count == NUM_WORDS) words else null
    }

    private const val SPACE = ' '.code.toByte()

    internal fun utf8Nfkd(s: String): ByteArray =
        Normalizer.normalize(s, Normalizer.Form.NFKD).toByteArray(StandardCharsets.UTF_8)

    /** The byte at [i] as a signed char, or the terminating NUL past the end. */
    private fun at(a: ByteArray, i: Int): Int = if (i < a.size) a[i].toInt() else 0

    private fun sign(key: Int, elm: Int): Int = (if (key > elm) 1 else 0) - (if (key < elm) 1 else 0)

    private fun compareStr(key: ByteArray, elm: ByteArray): Int {
        var k = 0
        var e = 0
        while (true) {
            if (at(key, k) == 0 || at(key, k) != at(elm, e)) break
            k++
            e++
        }
        return sign(at(key, k), at(elm, e))
    }

    private fun comparePrefix(key: ByteArray, elm: ByteArray, n: Int): Int {
        var k = 0
        var e = 0
        var i = 1
        while (true) {
            if (at(key, k) == 0) break
            if (i >= n && at(key, k + 1) == 0) break
            if (at(key, k) != at(elm, e)) break
            k++
            e++
            i++
        }
        return sign(at(key, k), at(elm, e))
    }

    private fun compareStrNoAccent(key: ByteArray, elm: ByteArray): Int {
        var k = 0
        var e = 0
        while (true) {
            while (at(key, k) < 0) k++
            while (at(elm, e) < 0) e++
            if (at(key, k) == 0 || at(key, k) != at(elm, e)) break
            k++
            e++
        }
        return sign(at(key, k), at(elm, e))
    }

    private fun comparePrefixNoAccent(key: ByteArray, elm: ByteArray, n: Int): Int {
        var k = 0
        var e = 0
        var i = 1
        while (true) {
            while (at(key, k) < 0) k++
            while (at(elm, e) < 0) e++
            if (at(key, k) == 0) break
            if (i >= n && at(key, k + 1) == 0) break
            if (at(key, k) != at(elm, e)) break
            k++
            e++
            i++
        }
        while (at(key, k) < 0) k++
        while (at(elm, e) < 0) e++
        return sign(at(key, k), at(elm, e))
    }

    private fun store32(p: ByteArray, offset: Int, u: Int) {
        for (b in 0 until 4) p[offset + b] = (u ushr (8 * b)).toByte()
    }

    /** PBKDF2 (RFC 8018) with HMAC-SHA256 and a raw byte password (javax's PBEKeySpec takes chars). */
    internal fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val out = ByteArray(length)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (iter in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(hLen, length - offset)
            t.copyInto(out, offset, 0, n)
            t.fill(0)
            u.fill(0)
            offset += n
            block++
        }
        return out
    }
}
