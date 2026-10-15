package one.monero.moneroone.core.node

import one.monero.moneroone.core.wallet.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomNodeNamesTest {

    private val plain = FakeSharedPreferences()
    private val names = CustomNodeNames(plain)

    private val fallback = "Custom Node"

    @Test
    fun `add saves the trimmed name under the bare uri`() {
        names.save("node.example.com:18081", "  Home Node ")

        assertEquals("Home Node", names.load("node.example.com:18081"))
        assertEquals(mapOf("node.example.com:18081" to "Home Node"), names.all())
        assertEquals("""{"node.example.com:18081":"Home Node"}""", plain.getString("custom_node_names", null))
        assertNull(names.load("other.example.com:18081"))
    }

    @Test
    fun `names never touch the custom node list older builds read`() {
        plain.edit().putString("custom_nodes", """["node.example.com:18081"]""").apply()

        names.save("node.example.com:18081", "Home Node")
        names.update("node.example.com:18081", "node.example.com:18089", "Home", fallback)
        names.remove("node.example.com:18089")

        assertEquals("""["node.example.com:18081"]""", plain.getString("custom_nodes", null))
    }

    @Test
    fun `edit with a uri change moves the name to the new uri`() {
        names.save("node.example.com:18081", "Home Node")
        names.save("lan-node.local:18089", "Basement")

        names.update("node.example.com:18081", "node.example.com:18089", "Home Node", fallback)

        assertNull(names.load("node.example.com:18081"))
        assertEquals("Home Node", names.load("node.example.com:18089"))
        assertEquals("Basement", names.load("lan-node.local:18089"))
        assertEquals(2, names.all().size)
    }

    @Test
    fun `edit renames in place and moves a new name with a new uri`() {
        names.save("node.example.com:18081", "Home Node")

        names.update("node.example.com:18081", "node.example.com:18081", " Office ", fallback)
        assertEquals(mapOf("node.example.com:18081" to "Office"), names.all())

        names.update("node.example.com:18081", "node.example.com:443", "Office TLS", fallback)
        assertEquals(mapOf("node.example.com:443" to "Office TLS"), names.all())
    }

    @Test
    fun `delete removes only that node's name`() {
        names.save("node.example.com:18081", "Home Node")
        names.save("lan-node.local:18089", "Basement")

        names.remove("node.example.com:18081")
        assertNull(names.load("node.example.com:18081"))
        assertEquals(mapOf("lan-node.local:18089" to "Basement"), names.all())

        names.remove("lan-node.local:18089")
        assertTrue(names.all().isEmpty())
        assertFalse(plain.contains("custom_node_names"))

        // Removing a node that never had a name writes nothing.
        names.remove("never-named.example.com:18081")
        assertTrue(plain.all.isEmpty())
    }

    @Test
    fun `a node from before names shows the fallback`() {
        plain.edit().putString("custom_nodes", """["node.example.com:18081"]""").apply()
        names.save("lan-node.local:18089", "Basement")

        assertNull(names.load("node.example.com:18081"))
        assertEquals(fallback, names.displayName("node.example.com:18081", fallback))
        assertEquals("Basement", names.displayName("lan-node.local:18089", fallback))
    }

    @Test
    fun `editing a node from before names keeps it unnamed until the user types a name`() {
        // Saved with the prefilled fallback (a URI or login change only): no
        // entry, so the label goes on following the app language.
        names.update("node.example.com:18081", "node.example.com:18089", fallback, fallback)
        assertTrue(names.all().isEmpty())
        assertEquals(fallback, names.displayName("node.example.com:18089", fallback))

        names.update("node.example.com:18089", "node.example.com:18089", "Home Node", fallback)
        assertEquals(mapOf("node.example.com:18089" to "Home Node"), names.all())

        // Once named, the fallback text is an ordinary name like any other.
        names.update("node.example.com:18089", "node.example.com:18089", fallback, fallback)
        assertEquals(mapOf("node.example.com:18089" to fallback), names.all())
    }

    @Test
    fun `a blank name stores nothing`() {
        names.save("node.example.com:18081", "   ")
        assertTrue(names.all().isEmpty())
        assertFalse(plain.contains("custom_node_names"))
    }

    @Test
    fun `bad json reads as no names`() {
        for (bad in listOf("not json", """["node.example.com:18081"]""", """{"node.example.com:18081":1}""", """{"a":null}""", "")) {
            plain.edit().putString("custom_node_names", bad).apply()
            assertTrue("decoding <$bad>", names.all().isEmpty())
            assertEquals(fallback, names.displayName("node.example.com:18081", fallback))
        }
    }

    @Test
    fun `a write after bad json leaves a clean map`() {
        plain.edit().putString("custom_node_names", "{broken").apply()

        names.save("node.example.com:18081", "Home Node")

        assertEquals(mapOf("node.example.com:18081" to "Home Node"), names.all())
        assertEquals("""{"node.example.com:18081":"Home Node"}""", plain.getString("custom_node_names", null))
    }
}
