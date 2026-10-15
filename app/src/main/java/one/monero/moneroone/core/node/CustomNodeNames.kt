package one.monero.moneroone.core.node

import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The names the user gives custom nodes, as on iOS (MoneroNode.name). The node
 * list stays a JSON list of bare host:port strings under
 * [NodeCredentialStore.KEY_CUSTOM_NODES], the shape migrateInline and older
 * builds read. The names sit beside it in the same plain prefs, as a JSON map
 * of bare host:port to name under [KEY_CUSTOM_NODE_NAMES]. A node added before
 * names existed has no entry, so its row shows the fallback label.
 */
class CustomNodeNames(private val prefs: SharedPreferences) {

    /** Every saved name by bare host:port. Unreadable JSON counts as no names. */
    fun all(): Map<String, String> {
        val stored = prefs.getString(KEY_CUSTOM_NODE_NAMES, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(stored) }.getOrDefault(emptyMap())
    }

    /** The saved name, or null for a node that has none. */
    fun load(uri: String): String? = all()[uri]

    /** The label for the node's row: its saved name, else [fallback]. */
    fun displayName(uri: String, fallback: String): String = load(uri) ?: fallback

    /** Saves the trimmed name for a new node. A blank name removes the entry. */
    fun save(uri: String, name: String) = write(oldUri = uri, newUri = uri, name = name.trim())

    /**
     * Saves an edit. The name moves to [newUri] when the URI changed. A node
     * without a saved name that keeps its [fallback] label stays without one,
     * so the label goes on following the app language.
     */
    fun update(oldUri: String, newUri: String, name: String, fallback: String) {
        val trimmed = name.trim()
        val keepsFallback = load(oldUri) == null && trimmed == fallback
        write(oldUri, newUri, if (keepsFallback) "" else trimmed)
    }

    fun remove(uri: String) {
        if (load(uri) != null) write(oldUri = uri, newUri = uri, name = "")
    }

    private fun write(oldUri: String, newUri: String, name: String) {
        val names = all().toMutableMap()
        names.remove(oldUri)
        if (name.isEmpty()) names.remove(newUri) else names[newUri] = name
        prefs.edit().apply {
            if (names.isEmpty()) remove(KEY_CUSTOM_NODE_NAMES)
            else putString(KEY_CUSTOM_NODE_NAMES, json.encodeToString(names))
        }.apply()
    }

    companion object {
        const val KEY_CUSTOM_NODE_NAMES = "custom_node_names"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
