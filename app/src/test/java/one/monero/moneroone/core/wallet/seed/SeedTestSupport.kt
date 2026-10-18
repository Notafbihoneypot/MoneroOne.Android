package one.monero.moneroone.core.wallet.seed

import java.io.File

/** Points [SeedWordLists] at the bundled assets (unit tests run without an Android context). */
object SeedTestSupport {
    val assetDir: File by lazy {
        listOf("src/main/assets/seedwords", "app/src/main/assets/seedwords")
            .map(::File)
            .firstOrNull { File(it, "manifest.json").isFile }
            ?: error("seedwords assets not found from ${File(".").absolutePath}")
    }

    fun init() = SeedWordLists.useDirectory(assetDir)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
