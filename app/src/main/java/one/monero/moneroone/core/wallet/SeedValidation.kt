package one.monero.moneroone.core.wallet

import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.monerokit.CakeWalletStyleConverter
import io.horizontalsystems.monerokit.MoneroKit
import io.horizontalsystems.monerokit.Seed
import io.horizontalsystems.monerokit.toElectrum
import one.monero.moneroone.core.wallet.seed.LegacyMnemonic
import one.monero.moneroone.core.wallet.seed.Polyseed
import one.monero.moneroone.core.wallet.seed.SeedFormat
import one.monero.moneroone.core.wallet.seed.SeedPhraseValidator
import timber.log.Timber
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * Seed acceptance rules applied BEFORE anything is persisted or the running
 * kit is torn down. H1 closed the BIP39 side (the eager Bip39→Electrum
 * conversion throws for unconvertible input); this closes the rest:
 *
 *  - BIP39: wordlist + checksum via hd-wallet-kit (a typo'd word converts to
 *    a DIFFERENT wallet silently otherwise).
 *  - 25-word Electrum (English): every word in the wordlist and the 25th
 *    word equal to the CRC32 checksum word — wallet2's rule. The kit's
 *    `Seed.Electrum` only checks the count, so a typo used to be persisted
 *    as an active wallet that can never open.
 *  - Any seed: derived keys must not be null (all-zero) and the primary
 *    address must be a well-formed mainnet address without a null-key run.
 *    This rejects only seeds whose private spend key reduces to zero:
 *    `abbey` x25 and the 15 encodings of k*l (k = 1..15, l the group order).
 *    Such a seed yields a burn address: funds received there are lost.
 *    Weak seeds with a real key still pass: `zoom` x25, and the all-"abandon
 *    ... art" BIP39 vector (spend key 4fe2e8fa...). iOS accepts them too, and
 *    blocking a restore would stop a user sweeping funds out of that wallet.
 *
 * [validate] runs native key derivation; call it off the main thread.
 */
object SeedValidation {

    class Validated(val electrum: Seed.Electrum, val primaryAddress: String)

    private const val PREFIX_LENGTH = 3
    private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")

    /** 32 zero bytes render as a long run of '1' in Monero base58; a real key never yields 16 in a row. */
    private val NULL_KEY_RUN = Regex("1{16,}")

    /** Mainnet primary address: 95 base58 chars starting with '4'. */
    private val MAINNET_PRIMARY = Regex("^4[1-9A-HJ-NP-Za-km-z]{94}$")

    private val englishWords: Set<String> by lazy { CakeWalletStyleConverter.MONERO_WORDLIST.toHashSet() }

    /** English wordlist words by 3-letter prefix, only where exactly one word has that prefix. */
    private val englishWordByPrefix: Map<String, String> by lazy {
        CakeWalletStyleConverter.MONERO_WORDLIST
            .groupBy { it.take(PREFIX_LENGTH) }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
    }

    /** True when every word is in the English Electrum wordlist. */
    fun isEnglishElectrum(words: List<String>): Boolean = words.isNotEmpty() && words.all { it in englishWords }

    /**
     * wallet2 reads an English Electrum word by its unique 3-letter prefix, so
     * "abbxy" restores as "abbey". Returns [words] with each word replaced by
     * the list word wallet2 reads, when all of these are true: 25 words, each
     * word in the list or sharing its first 3 letters with exactly one list
     * word, and the result passes the checksum. Otherwise returns [words] as
     * they are. Pure (no native code).
     */
    fun canonicalElectrumWords(words: List<String>): List<String> {
        if (words.size != 25) return words
        val canonical = words.map { word ->
            if (word in englishWords) word else englishWordByPrefix[word.take(PREFIX_LENGTH)] ?: return words
        }
        return if (electrumSeedProblem(canonical) == null) canonical else words
    }

    /**
     * Pure wallet2 checksum rule for an English 25-word seed (no native code).
     * Returns null when valid, otherwise a short reason (never echoes the seed).
     */
    fun electrumSeedProblem(words: List<String>): String? {
        if (words.size != 25) return "expected 25 words, got ${words.size}"
        if (!isEnglishElectrum(words)) return "word not in wordlist"
        val trimmed = StringBuilder()
        for (word in words.take(24)) {
            trimmed.append(word.substring(0, minOf(PREFIX_LENGTH, word.length)))
        }
        val crc = CRC32().apply { update(trimmed.toString().toByteArray(StandardCharsets.UTF_8)) }.value
        val expected = words[(crc % 24).toInt()]
        return if (words[24] == expected) null else "checksum word mismatch"
    }

    /** Null, malformed, or all-zero 32-byte key (hex). */
    fun isNullKey(hex: String?): Boolean =
        hex == null || !HEX_64.matches(hex) || hex.all { it == '0' }

    /** Well-formed mainnet primary address with no null-key run. */
    fun isPlausiblePrimaryAddress(address: String?): Boolean =
        address != null && MAINNET_PRIMARY.matches(address) && !NULL_KEY_RUN.containsMatchIn(address)

    /**
     * The seed the kit restores from. A 25-word seed goes in as its list
     * words ([legacyKitWords]); a Polyseed as the 25 legacy words of its
     * spend key (PBKDF2: call off Main). Throws [InvalidSeedException] for a
     * stored Polyseed that does not decode. Only the kit gets these words:
     * callers store, compare and hash (cache ids) the words they hold.
     */
    fun kitSeed(words: List<String>, type: SeedType): Seed = when (type) {
        SeedType.ELECTRUM_25 -> Seed.Electrum(legacyKitWords(words), "")
        SeedType.BIP39_24 -> Seed.Bip39(words, "")
        SeedType.POLYSEED_16 -> Seed.Electrum(polyseedLegacyWords(words), "")
    }

    /**
     * The 25 words wallet2 gets for a legacy seed: each word in its exact
     * list form (the list's case, NFC), picked by the index wallet2 reads.
     *
     * wallet2 matches a word through towlower on both sides. bionic's
     * towlower may leave non-ASCII letters as they are (an Android version
     * or an ICU load failure), and then "ästhetik" (stored lowercase) does
     * not match the German list word "Ästhetik". The list form matches byte
     * for byte, so the platform does not matter. Rows stored by older builds
     * (lowercase, prefixes as typed) get the same treatment; nothing is
     * written back.
     *
     * Returns [words] unchanged (wallet2 then decides, as before) when they
     * do not decode to a valid seed, when the list form would decode to a
     * different language or key, or when the word lists cannot load. No
     * native code; the first call reads the word lists.
     */
    fun legacyKitWords(words: List<String>): List<String> {
        val decoded = try {
            LegacyMnemonic.decode(words)
        } catch (e: Exception) {
            Timber.w("Legacy words kept as stored: word lists unavailable (${e.javaClass.simpleName})")
            return words
        }
        val language = decoded.language
        val indices = decoded.indices
        val key = decoded.key
        try {
            if (decoded.problem != null || language == null || indices == null || key == null) return words
            val listForm = indices.map { language.words[it] }
            val check = LegacyMnemonic.decode(listForm)
            val same = check.problem == null && check.language === language && key.contentEquals(check.key)
            check.key?.fill(0)
            check.indices?.fill(0)
            return if (same) listForm else words
        } finally {
            key?.fill(0)
            indices?.fill(0)
        }
    }

    /**
     * The 25 English legacy words of the same wallet as the 16-word
     * Polyseed [words]: decode, keygen (PBKDF2-HMAC-SHA256, coin Monero),
     * sc_reduce32, then wallet2's word encoding (account_base
     * create_from_polyseed with no passphrase). Call off Main.
     */
    fun polyseedLegacyWords(words: List<String>): List<String> {
        val decoded = Polyseed.decode(words.joinToString(" "))
        val data = decoded.data
        if (decoded.status != Polyseed.Status.OK || data == null || data.isEncrypted) {
            data?.wipe()
            Timber.w("Polyseed rejected: ${decoded.status}${if (data?.isEncrypted == true) " (passphrase)" else ""}")
            throw InvalidSeedException()
        }
        return try {
            Polyseed.legacyWords(data)
        } finally {
            data.wipe()
        }
    }

    /**
     * Full validation. Throws [InvalidSeedException] for anything that must
     * not become a wallet. Runs native key derivation (JNI).
     */
    fun validate(words: List<String>, type: SeedType): Validated {
        when (type) {
            SeedType.BIP39_24 -> try {
                Mnemonic().validate(words)
            } catch (e: Exception) {
                Timber.w("Seed rejected: BIP39 validation failed (${e.javaClass.simpleName})")
                throw InvalidSeedException()
            }
            SeedType.ELECTRUM_25 -> if (isEnglishElectrum(words)) {
                electrumSeedProblem(words)?.let {
                    Timber.w("Seed rejected: $it")
                    throw InvalidSeedException()
                }
            }
            SeedType.POLYSEED_16 -> if (SeedPhraseValidator.validate(SeedFormat.POLYSEED, words) !is SeedPhraseValidator.Verdict.Valid) {
                // Checksum, language, reserved features or a passphrase (not supported).
                Timber.w("Phrase rejected: Polyseed validation failed")
                throw InvalidSeedException()
            }
            // Non-English Electrum seeds are left to wallet2's own decoder
            // (below + the post-start check in the ViewModel).
        }

        val electrum = try {
            kitSeed(words, type).toElectrum()
        } catch (e: Exception) {
            Timber.w("Seed rejected: conversion failed (${e.javaClass.simpleName})")
            throw InvalidSeedException()
        }

        val keys = try {
            MoneroKit.getKeys(electrum)
        } catch (e: Throwable) {
            Timber.w("Seed rejected: key derivation failed (${e.javaClass.simpleName})")
            throw InvalidSeedException()
        }
        if (isNullKey(keys.privateSpendKey) || isNullKey(keys.publicSpendKey) ||
            isNullKey(keys.privateViewKey) || isNullKey(keys.publicViewKey)
        ) {
            Timber.w("Seed rejected: null or malformed derived key")
            throw InvalidSeedException()
        }

        val address = try {
            MoneroKit.getAddress(electrum, 0, 0)
        } catch (e: Throwable) {
            Timber.w("Seed rejected: address derivation failed (${e.javaClass.simpleName})")
            throw InvalidSeedException()
        }
        if (!isPlausiblePrimaryAddress(address)) {
            Timber.w("Seed rejected: implausible primary address")
            throw InvalidSeedException()
        }
        return Validated(electrum, address)
    }
}
