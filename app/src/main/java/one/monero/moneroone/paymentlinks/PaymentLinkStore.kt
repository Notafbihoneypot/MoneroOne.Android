package one.monero.moneroone.paymentlinks

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import one.monero.moneroone.core.wallet.SecurePrefs
import timber.log.Timber

/**
 * Sent and received links, in the app's encrypted store (SecurePrefs) only:
 * the words in them are cash. Writes are synchronous (commit) so a record is
 * on disk before the funding broadcast starts.
 */
class PaymentLinkStore internal constructor(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _sent = MutableStateFlow(read(KEY_SENT, SentLink.serializer()))
    val sent: StateFlow<List<SentLink>> = _sent.asStateFlow()

    private val _received = MutableStateFlow(read(KEY_RECEIVED, ReceivedLink.serializer()))
    val received: StateFlow<List<ReceivedLink>> = _received.asStateFlow()

    fun sent(id: String): SentLink? = _sent.value.firstOrNull { it.id == id }

    fun received(id: String): ReceivedLink? = _received.value.firstOrNull { it.id == id }

    @Synchronized
    fun putSent(link: SentLink): Boolean {
        val next = _sent.value.filterNot { it.id == link.id } + link
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

    @Synchronized
    fun putReceived(link: ReceivedLink): Boolean {
        val next = _received.value.filterNot { it.id == link.id } + link
        return write(KEY_RECEIVED, next, ReceivedLink.serializer()).also { if (it) _received.value = next }
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

    /** A received link waiting to open its claim sheet (after unlock, or after the first wallet exists). */
    var pendingOpen: String?
        get() = prefs.getString(KEY_PENDING_OPEN, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_PENDING_OPEN) else putString(KEY_PENDING_OPEN, value) }.commit()
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
        private const val KEY_SENT = "paymentlinks.sent.v1"
        private const val KEY_RECEIVED = "paymentlinks.received.v1"
        private const val KEY_PENDING_OPEN = "paymentlinks.pending_open"

        @Volatile
        private var instance: PaymentLinkStore? = null

        fun get(context: Context): PaymentLinkStore =
            instance ?: synchronized(this) {
                instance ?: PaymentLinkStore(SecurePrefs.open(context)).also { instance = it }
            }
    }
}
