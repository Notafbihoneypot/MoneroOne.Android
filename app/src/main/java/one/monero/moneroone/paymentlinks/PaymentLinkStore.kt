package one.monero.moneroone.paymentlinks

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import one.monero.moneroone.core.wallet.SecurePrefs
import timber.log.Timber

/**
 * Sent and received links, in the app's encrypted store (SecurePrefs) only:
 * the secrets and words in them are cash. Writes are synchronous (commit) so a record is
 * on disk before the funding broadcast starts.
 */
class PaymentLinkStore internal constructor(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _sent = MutableStateFlow(read(KEY_SENT, SentLink.serializer()))
    val sent: StateFlow<List<SentLink>> = _sent.asStateFlow()

    private val _received = MutableStateFlow(read(KEY_RECEIVED, ReceivedLink.serializer()))
    val received: StateFlow<List<ReceivedLink>> = _received.asStateFlow()

    init {
        numberOldRecords()
    }

    fun sent(id: String): SentLink? = _sent.value.firstOrNull { it.id == id }

    fun received(id: String): ReceivedLink? = _received.value.firstOrNull { it.id == id }

    /**
     * Saves [link]. The number and the note belong to the store: a saved record keeps its own,
     * whatever the caller's copy holds, so a copy taken before a rename cannot undo it. A new
     * record gets the next number and keeps its note. [rename] changes the note.
     */
    @Synchronized
    fun putSent(link: SentLink): Boolean {
        val stored = sent(link.id)
        val kept = if (stored != null) link.copy(number = stored.number ?: link.number, note = stored.note) else link
        val record = if (kept.number == null) kept.copy(number = takeNumber()) else kept
        return saveSent(record)
    }

    private fun saveSent(record: SentLink): Boolean {
        val next = _sent.value.filterNot { it.id == record.id } + record
        return write(KEY_SENT, next, SentLink.serializer()).also { if (it) _sent.value = next }
    }

    @Synchronized
    fun updateSent(id: String, change: (SentLink) -> SentLink): SentLink? {
        val current = sent(id) ?: return null
        val updated = change(current)
        return if (putSent(updated)) updated else null
    }

    @Synchronized
    fun removeSent(id: String) {
        val next = _sent.value.filterNot { it.id == id }
        if (write(KEY_SENT, next, SentLink.serializer())) _sent.value = next
    }

    /** Saves [link], keeping a saved record's number and note, as [putSent]. */
    @Synchronized
    fun putReceived(link: ReceivedLink): Boolean {
        val stored = received(link.id)
        val kept = if (stored != null) link.copy(number = stored.number ?: link.number, note = stored.note) else link
        val record = if (kept.number == null) kept.copy(number = takeNumber()) else kept
        return saveReceived(record)
    }

    private fun saveReceived(record: ReceivedLink): Boolean {
        val next = _received.value.filterNot { it.id == record.id } + record
        return write(KEY_RECEIVED, next, ReceivedLink.serializer()).also { if (it) _received.value = next }
    }

    /** The name the next new link gets ("Link 9"): the Send note field's placeholder. */
    fun nextName(): String = LinkNames.name(null, nextNumber())

    /** Saves this phone's note for a link; a blank note goes back to "Link 7". */
    @Synchronized
    fun rename(id: String, note: String?) {
        val clean = LinkNames.cleanNote(note)
        sent(id)?.let { saveSent(it.copy(note = clean)); return }
        received(id)?.let { saveReceived(it.copy(note = clean)) }
    }

    private fun nextNumber(): Int = LinkNames.highest(_sent.value, _received.value, prefs.getInt(KEY_LAST_NUMBER, 0)) + 1

    /** A new link's number. The counter only goes up: a removed link's number is not used again. */
    private fun takeNumber(): Int = nextNumber().also { prefs.edit().putInt(KEY_LAST_NUMBER, it).commit() }

    /** Records saved before numbers get one each, once, in the order they were made. */
    @Synchronized
    private fun numberOldRecords() {
        val counter = prefs.getInt(KEY_LAST_NUMBER, 0)
        val numbered = LinkNames.numbered(_sent.value, _received.value, counter)
        if (numbered.sent != _sent.value && write(KEY_SENT, numbered.sent, SentLink.serializer())) _sent.value = numbered.sent
        if (numbered.received != _received.value && write(KEY_RECEIVED, numbered.received, ReceivedLink.serializer())) {
            _received.value = numbered.received
        }
        if (numbered.counter != counter) prefs.edit().putInt(KEY_LAST_NUMBER, numbered.counter).commit()
    }

    @Synchronized
    fun updateReceived(id: String, change: (ReceivedLink) -> ReceivedLink): ReceivedLink? {
        val current = received(id) ?: return null
        val updated = change(current)
        return if (putReceived(updated)) updated else null
    }

    @Synchronized
    fun removeReceived(id: String) {
        val next = _received.value.filterNot { it.id == id }
        if (write(KEY_RECEIVED, next, ReceivedLink.serializer())) _received.value = next
    }

    private val _pendingOpen = MutableStateFlow(prefs.getString(KEY_PENDING_OPEN, null))

    /** A link id waiting to open its claim sheet (after unlock, or after the first wallet exists). Kept across restarts. */
    val pendingOpen: StateFlow<String?> = _pendingOpen.asStateFlow()

    fun setPendingOpen(id: String?) {
        prefs.edit().apply { if (id == null) remove(KEY_PENDING_OPEN) else putString(KEY_PENDING_OPEN, id) }.commit()
        _pendingOpen.value = id
    }

    private val _claimWhenReady = MutableStateFlow<String?>(null)

    /**
     * A received link to claim as soon as its claim sheet finds it ready: the
     * user tapped its Claim row in Activity. In memory only; once only.
     */
    val claimWhenReady: StateFlow<String?> = _claimWhenReady.asStateFlow()

    /** Opens the claim sheet of a saved received link; [autoClaim] claims it once its check finds it ready. */
    fun openClaim(id: String, autoClaim: Boolean = false) {
        _claimWhenReady.value = if (autoClaim) id else null
        setPendingOpen(id)
    }

    /** The claim sheet took the auto-claim of [id] (or closed): it does not run again. */
    fun clearClaimWhenReady(id: String) {
        if (_claimWhenReady.value == id) _claimWhenReady.value = null
    }

    private val _intakeProblem = MutableStateFlow<PaymentLinkParse?>(null)

    /** A link that arrived and cannot open (incomplete, or a newer version). Not secret: no words. */
    val intakeProblem: StateFlow<PaymentLinkParse?> = _intakeProblem.asStateFlow()

    fun clearIntakeProblem() {
        _intakeProblem.value = null
    }

    /**
     * A claim link arrived (App Link, paste, scan). A valid one is saved at once,
     * so it survives a lock, a restart or a missing wallet, and its sheet is
     * queued. Returns false when [text] is not a claim link at all.
     */
    @Synchronized
    fun accept(text: String): Boolean {
        when (val parsed = PaymentLinkCodec.parse(text)) {
            is PaymentLinkParse.Valid -> {
                val link = parsed.link
                // A link this phone sent opens from its sent record; it is not a received link.
                val known = received(link.id)
                if (sent(link.id) == null && known == null) {
                    // A v2 link keeps only its secret (the words derive from it); it carries no amount or expiry.
                    putReceived(
                        ReceivedLink(
                            link.id, link.words.takeIf { link.version == 1 }, link.height, link.amount, link.expiry,
                            System.currentTimeMillis(), secret = link.secretHex
                        )
                    )
                } else if (known != null && known.link == null) {
                    // Opened again after its claim: the secret comes back until that claim is mined.
                    updateReceived(link.id) { it.copy(words = link.words.takeIf { link.version == 1 }, secret = link.secretHex) }
                }
                setPendingOpen(link.id)
            }
            PaymentLinkParse.Incomplete, PaymentLinkParse.NeedsUpdate -> _intakeProblem.value = parsed
            PaymentLinkParse.NotALink -> return false
        }
        return true
    }

    /** A sent link that may still hold money: Remove All warns that it will be lost. */
    fun hasUnsettledSentLinks(): Boolean = _sent.value.any { !LinkStatusRules.isSettled(it) }

    /** Remove All (the full wipe): every record and its secret leave the encrypted store. */
    @Synchronized
    fun removeAll() {
        prefs.edit().remove(KEY_SENT).remove(KEY_RECEIVED).remove(KEY_PENDING_OPEN).remove(KEY_LAST_NUMBER).commit()
        _sent.value = emptyList()
        _received.value = emptyList()
        _pendingOpen.value = null
        _claimWhenReady.value = null
        _intakeProblem.value = null
    }

    /** Envelope ids whose files must stay: every link that can still move money. */
    fun liveEnvelopeIds(): Set<String> =
        (_sent.value.filterNot { LinkStatusRules.isSettled(it) }.map { it.id } +
            _received.value.filter { it.state == ReceivedState.PENDING }.map { it.id }).toSet()

    private fun <T> read(slot: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val text = prefs.getString(slot, null) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(serializer), text)
        } catch (e: Exception) {
            // Never log the text: it holds envelope words.
            Timber.e("PaymentLinks: could not read the stored records (%s)", e.javaClass.simpleName)
            emptyList()
        }
    }

    private fun <T> write(slot: String, list: List<T>, serializer: kotlinx.serialization.KSerializer<T>): Boolean {
        val ok = prefs.edit().putString(slot, json.encodeToString(ListSerializer(serializer), list)).commit()
        if (!ok) Timber.e("PaymentLinks: could not save the records")
        return ok
    }

    companion object {
        /** Remove All: closes an open envelope, then deletes every link record, its words and every envelope's files. */
        suspend fun wipe(context: Context) {
            EnvelopeWallet.closeCurrent()
            get(context).removeAll()
            withContext(Dispatchers.IO) { EnvelopeFiles.sweep(context, emptySet()) }
        }

        private const val KEY_SENT = "paymentlinks.sent.v1"
        private const val KEY_RECEIVED = "paymentlinks.received.v1"
        private const val KEY_PENDING_OPEN = "paymentlinks.pending_open"
        private const val KEY_LAST_NUMBER = "paymentlinks.last_number"

        @Volatile
        private var instance: PaymentLinkStore? = null

        fun get(context: Context): PaymentLinkStore =
            instance ?: synchronized(this) {
                instance ?: PaymentLinkStore(SecurePrefs.open(context)).also { instance = it }
            }
    }
}
