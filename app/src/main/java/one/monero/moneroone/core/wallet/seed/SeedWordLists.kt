package one.monero.moneroone.core.wallet.seed

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * The legacy (wallet2 electrum-words) and Polyseed word lists, bundled as
 * assets under `seedwords/`. They are generated from the monero sources and
 * are byte-identical with the iOS copy (see `seedwords/manifest.json`).
 *
 * Each list loads once, on first use. The restore screen calls [prewarm] off
 * Main so the first keystroke does not wait for the files. Unit tests point
 * the loader at the source folder with [useDirectory].
 */
object SeedWordLists {
    private const val ASSET_DIR = "seedwords"

    @Volatile
    private var opener: ((String) -> InputStream)? = null

    fun initialize(context: Context) {
        val assets = context.applicationContext.assets
        opener = { path -> assets.open("$ASSET_DIR/$path") }
    }

    /** Tests: read the lists from [dir] (`app/src/main/assets/seedwords`). */
    fun useDirectory(dir: File) {
        opener = { path -> File(dir, path).inputStream() }
    }

    internal fun readList(path: String): List<String> {
        val open = opener ?: error("SeedWordLists is not initialized")
        return open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toList()
        }
    }

    /** In wallet2's language search order (electrum-words.cpp find_seed_language). */
    val legacy: List<LegacyMnemonic.Language> by lazy {
        LegacyMnemonic.SPECS.map { LegacyMnemonic.Language(it, readList(it.file)) }
    }

    /** In the Polyseed library's language order (lang.c). */
    val polyseed: List<Polyseed.Language> by lazy {
        Polyseed.SPECS.map { Polyseed.Language(it, readList(it.file)) }
    }

    val englishLegacy: LegacyMnemonic.Language
        get() = legacy.first { it.spec.englishName == "English" }

    /** Load every list now (Polyseed, legacy and the kit's BIP39 lists). Call off Main. */
    fun prewarm() {
        legacy
        polyseed
        Bip39Words.languages
    }
}
