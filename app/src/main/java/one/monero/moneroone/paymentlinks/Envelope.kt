package one.monero.moneroone.paymentlinks

import android.content.Context
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.monerokit.Seed
import io.horizontalsystems.monerokit.model.WalletManager as NativeWalletManager
import io.horizontalsystems.monerokit.toElectrum
import one.monero.moneroone.core.node.NodeCredentialStore
import one.monero.moneroone.core.wallet.DefaultNodes
import one.monero.moneroone.core.wallet.SecurePrefs
import one.monero.moneroone.core.wallet.SeedType
import one.monero.moneroone.core.wallet.SeedValidation
import timber.log.Timber
import java.io.File
import java.security.SecureRandom

/**
 * Envelope wallet files: `no_backup/envelopes/<id>/`, outside the kit's
 * `files/wallets` root that the launch sweep cleans, and outside backups.
 */
object EnvelopeFiles {

    fun root(context: Context): File = File(context.noBackupFilesDir, "envelopes")

    fun dir(context: Context, id: String): File {
        require(PaymentLink.isValidId(id)) { "Bad envelope id" }
        return File(root(context), id)
    }

    /**
     * The kit opens wallets by a name under `files/wallets`. An envelope's name is
     * the relative path from there into its own directory, so the kit works on it
     * unchanged and its files stay out of the wallets root.
     */
    fun kitWalletId(context: Context, id: String): String {
        val walletRoot = File(context.filesDir, "wallets").absoluteFile
        val dir = dir(context, id).absoluteFile.apply { mkdirs() }
        return File(dir, "envelope").relativeTo(walletRoot).path
    }

    fun delete(context: Context, id: String) {
        if (!dir(context, id).deleteRecursively()) Timber.w("PaymentLinks: could not delete envelope files")
    }

    /** Deletes the files of every envelope not in [keep] (settled or removed links). */
    fun sweep(context: Context, keep: Set<String>) {
        root(context).listFiles()?.forEach { file ->
            if (file.name !in keep) file.deleteRecursively()
        }
    }
}

/** The node and proxy the main wallet uses, for an envelope kit. */
internal object EnvelopeNode {
    fun kitNodeString(context: Context): String {
        val prefs = context.getSharedPreferences("monero_wallet", Context.MODE_PRIVATE)
        val uri = prefs.getString("selected_node", null) ?: DefaultNodes.initial(context)
        return NodeCredentialStore(SecurePrefs.open(context)).kitNodeString(uri)
    }
}

class EnvelopeMismatchException : IllegalStateException("The envelope did not restore to its address")

/** A new envelope before funding: the link and the address the funding goes to. */
class EnvelopeDraft(val link: PaymentLink, val address: String) {
    override fun toString(): String = "EnvelopeDraft(n=${link.id})"
}

object EnvelopeFactory {

    /** The spec's default expiry: 30 days. */
    const val EXPIRY_SECONDS = 30L * 24 * 60 * 60

    /** The restore height sits this many blocks under the sender's daemon height. */
    const val HEIGHT_MARGIN = 10L

    private val random = SecureRandom()

    fun newId(): String {
        val bytes = ByteArray(8).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun restoreHeight(daemonHeight: Long): Long = (daemonHeight - HEIGHT_MARGIN).coerceAtLeast(0)

    /**
     * Makes a random envelope: a BIP39 phrase as the new-wallet path makes one,
     * then the kit's legacy 25 words for it. The words are restored through
     * wallet2's legacy restore, and the address it gives must equal the address
     * derived from the words, or nothing may be funded. Native code: call off Main.
     */
    fun create(context: Context, amount: Long, daemonHeight: Long, nowSeconds: Long): EnvelopeDraft {
        val bip39 = Mnemonic().generate(Mnemonic.EntropyStrength.VeryHigh)
        val words = Seed.Bip39(bip39, "").toElectrum().mnemonic.map { it.lowercase() }
        val validated = SeedValidation.validate(words, SeedType.ELECTRUM_25)
        val id = newId()
        val height = restoreHeight(daemonHeight)
        val restored = restoredAddress(context, id, words, height)
        if (restored != validated.primaryAddress) {
            EnvelopeFiles.delete(context, id)
            throw EnvelopeMismatchException()
        }
        val link = PaymentLink(words, height, amount, nowSeconds + EXPIRY_SECONDS, id)
        return EnvelopeDraft(link, validated.primaryAddress)
    }

    /** The address wallet2's legacy restore gives for [words]. Leaves no files behind. */
    fun restoredAddress(context: Context, id: String, words: List<String>, height: Long): String? {
        val dir = EnvelopeFiles.dir(context, id).apply { mkdirs() }
        val file = File(dir, "restore-check")
        return try {
            val wallet = NativeWalletManager.getInstance()
                .recoveryWallet(file, "", words.joinToString(" "), "", height)
            try {
                if (wallet.status.isOk) wallet.address else null
            } finally {
                wallet.close()
            }
        } finally {
            dir.listFiles()?.filter { it.name.startsWith("restore-check") }?.forEach { it.delete() }
        }
    }

    /** The address the words derive to (for an own-link check). Native code: call off Main. */
    fun address(words: List<String>): String? = runCatching {
        SeedValidation.validate(words, SeedType.ELECTRUM_25).primaryAddress
    }.getOrNull()
}
