package one.monero.moneroone.paymentlinks

import android.content.SharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The store's numbers and notes: the same cases as iOS `EnvelopeStore` numbering tests. */
class PaymentLinkStoreNumberTest {

    private val secret = "000102030405060708090a0b0c0d0e0f"

    private fun sent(id: String, created: Long = 1, number: Int? = null, note: String? = null) = SentLink(
        id = id, height = 100, amount = 5, expiry = 9, createdAtMillis = created, walletId = "w",
        envelopeAddress = "4", feeReserve = 1, state = SentState.CREATED, secret = secret, number = number, note = note
    )

    private fun received(id: String, opened: Long = 1) =
        ReceivedLink(id = id, words = null, height = 100, amount = 0, expiry = 0, receivedAtMillis = opened, secret = secret)

    @Test
    fun `new links take the next number, sent and received sharing one counter`() {
        val store = PaymentLinkStore(MemoryPrefs())
        assertEquals("Link 1", store.nextName())
        store.putSent(sent("a", note = "Dinner"))
        store.putReceived(received("b"))
        store.putSent(sent("c"))
        assertEquals(1, store.sent("a")?.number)
        assertEquals("Dinner", store.sent("a")?.name)
        assertEquals(2, store.received("b")?.number)
        assertEquals("Link 3", store.sent("c")?.name)
        assertEquals("Link 4", store.nextName())
    }

    @Test
    fun `a removed link's number is not used again`() {
        val store = PaymentLinkStore(MemoryPrefs())
        store.putSent(sent("a"))
        store.putSent(sent("b"))
        store.removeSent("b")
        assertEquals("Link 3", store.nextName())
    }

    @Test
    fun `an older copy keeps the saved number and note, and rename changes the note`() {
        val store = PaymentLinkStore(MemoryPrefs())
        store.putSent(sent("a"))
        val before = store.sent("a")!!
        store.rename("a", "  Rent  ")
        store.putSent(before.copy(state = SentState.FUNDED))
        assertEquals("Rent", store.sent("a")?.note)
        assertEquals(1, store.sent("a")?.number)
        assertEquals(SentState.FUNDED, store.sent("a")?.state)

        store.rename("a", "   ")
        assertNull(store.sent("a")?.note)
        assertEquals("Link 1", store.sent("a")?.name)
    }

    @Test
    fun `records saved before numbers get them on load, oldest first, once`() {
        val prefs = MemoryPrefs()
        val json = Json { ignoreUnknownKeys = true }
        prefs.edit().putString(
            "paymentlinks.sent.v1",
            json.encodeToString(ListSerializer(SentLink.serializer()), listOf(sent("late", created = 30), sent("early", created = 10)))
        ).putString(
            "paymentlinks.received.v1",
            json.encodeToString(ListSerializer(ReceivedLink.serializer()), listOf(received("mid", opened = 20)))
        ).commit()

        val store = PaymentLinkStore(prefs)
        assertEquals(1, store.sent("early")?.number)
        assertEquals(2, store.received("mid")?.number)
        assertEquals(3, store.sent("late")?.number)

        // A second load keeps them.
        val again = PaymentLinkStore(prefs)
        assertEquals(3, again.sent("late")?.number)
        assertEquals("Link 4", again.nextName())
    }

    @Test
    fun `Remove All starts the numbers again`() {
        val store = PaymentLinkStore(MemoryPrefs())
        store.putSent(sent("a"))
        store.removeAll()
        assertEquals("Link 1", store.nextName())
    }
}

/** SharedPreferences in memory, enough for the store. */
private class MemoryPrefs : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(changes)
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
