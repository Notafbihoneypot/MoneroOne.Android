package one.monero.moneroone.core.wallet

import io.horizontalsystems.monerokit.data.Subaddress
import io.horizontalsystems.monerokit.model.TransactionInfo
import one.monero.moneroone.core.locale.pluralTr
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.data.util.XmrFormat
import java.math.BigInteger
import java.text.Normalizer

data class AddressUsage(val payments: Int = 0, val received: BigInteger = BigInteger.ZERO)
data class ReceiveAddressRow(val address: Subaddress, val name: String, val labeled: Boolean, val usage: AddressUsage) {
    val index get() = address.addressIndex
    val description: String get() = if (usage.payments == 0) tr("Unused") else
        "${XmrFormat.format(usage.received)} XMR · ${pluralTr("%s payments", usage.payments)}"
}

/** Incoming payments, including the pool but excluding failed transactions. */
object ReceiveAddressLogic {
    const val WARNING_THRESHOLD = 150
    const val STOP_THRESHOLD = 190
    /** Select Address shows a search field from this many subaddresses (iOS searchThreshold). */
    const val SEARCH_THRESHOLD = 8

    /**
     * Select Address search, as on iOS: "#12" or "12" finds subaddress 12, and
     * other text matches the name or the address, ignoring case and accents.
     */
    fun matchesSearch(row: ReceiveAddressRow, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.startsWith("#")) trimmed.drop(1).toIntOrNull()?.let { return row.index == it }
        val folded = fold(trimmed)
        return trimmed.toIntOrNull() == row.index || fold(row.name).contains(folded) || fold(row.address.address).contains(folded)
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    /** An address's usage for TalkBack: "received 1.5000 XMR, 3 payments", or "unused" (iOS spokenUsage). */
    fun spokenUsage(usage: AddressUsage): String =
        if (usage.payments == 0) tr("unused")
        else listOf(tr("received %s XMR", XmrFormat.compact(usage.received)), pluralTr("%s payments", usage.payments)).joinToString(", ")

    /** A row for TalkBack: "Gifts, subaddress 12, received 1.5000 XMR, 3 payments" (iOS spokenRow). */
    fun spokenRow(row: ReceiveAddressRow): String {
        val name = if (row.labeled && row.index != 0) tr("%s, subaddress %s", row.name, row.index) else row.name
        return listOf(name, spokenUsage(row.usage)).joinToString(", ")
    }

    /**
     * A label's leading emoji and the rest, as iOS splitSubaddressLabel: the
     * label is one string, so the icon is packed in front as "<emoji> <name>".
     */
    fun splitLabel(raw: String): Pair<String, String> {
        if (raw.isEmpty() || !isLeadingEmoji(raw.codePointAt(0))) return "" to raw
        val end = emojiEnd(raw)
        return raw.substring(0, end) to raw.substring(end).trim()
    }

    /** Packs an emoji and a name back into one label (iOS joinSubaddressLabel). */
    fun joinLabel(emoji: String, name: String): String {
        val trimmed = name.trim()
        return when {
            emoji.isEmpty() -> trimmed
            trimmed.isEmpty() -> emoji
            else -> "$emoji $trimmed"
        }
    }

    /** Emoji iOS accepts as a label icon: ⌚ ⌛ and the emoji blocks above U+238C. */
    private fun isLeadingEmoji(cp: Int): Boolean =
        cp == 0x231A || cp == 0x231B || cp in 0x23CF..0x23FA || cp == 0x24C2 || cp in 0x25AA..0x25FE ||
            cp in 0x2600..0x27BF || cp in 0x2934..0x2935 || cp in 0x2B05..0x2B55 ||
            cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299 || cp in 0x1F000..0x1FAFF

    /** The end of the first emoji: its modifiers, variation selectors, keycap, tags, flag pair and ZWJ parts. */
    private fun emojiEnd(text: String): Int {
        val first = text.codePointAt(0)
        var end = Character.charCount(first)
        val regional = first in 0x1F1E6..0x1F1FF
        var pairedFlag = false
        while (end < text.length) {
            val cp = text.codePointAt(end)
            when {
                cp == 0xFE0E || cp == 0xFE0F || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F ->
                    end += Character.charCount(cp)
                cp == 0x200D && end + 1 < text.length -> {
                    end += 1
                    end += Character.charCount(text.codePointAt(end))
                }
                regional && !pairedFlag && cp in 0x1F1E6..0x1F1FF -> {
                    end += Character.charCount(cp)
                    pairedFlag = true
                }
                else -> return end
            }
        }
        return end
    }

    fun usage(transactions: List<TransactionInfo>): Map<Int, AddressUsage> {
        val usage = mutableMapOf<Int, AddressUsage>()
        for (tx in transactions.distinctBy { it.hash }) {
            if (tx.accountIndex != 0 || tx.direction != TransactionInfo.Direction.Direction_In || tx.isFailed) continue
            val old = usage[tx.addressIndex] ?: AddressUsage()
            usage[tx.addressIndex] = AddressUsage(old.payments + 1, old.received + BigInteger.valueOf(tx.amount))
        }
        return usage
    }

    fun name(index: Int, label: String = ""): String = when {
        index == 0 -> tr("Main Address")
        label.isNotBlank() && !Subaddress.DEFAULT_LABEL_FORMATTER.matcher(label).matches() -> label
        else -> tr("Subaddress #%s", index)
    }

    fun rows(addresses: List<Subaddress>, transactions: List<TransactionInfo>, labels: Map<Int, String>): List<ReceiveAddressRow> {
        val usage = usage(transactions)
        return addresses.filter { it.accountIndex == 0 && it.address.isNotBlank() }.distinctBy { it.addressIndex }
            .sortedByDescending { it.addressIndex }.map {
                val label = labels[it.addressIndex] ?: it.label
                ReceiveAddressRow(it, name(it.addressIndex, label), label.isNotBlank() &&
                    !Subaddress.DEFAULT_LABEL_FORMATTER.matcher(label).matches(), usage[it.addressIndex] ?: AddressUsage())
            }
    }

    fun unusedAfterLastUsed(rows: List<ReceiveAddressRow>): Int =
        ((rows.maxOfOrNull { it.index } ?: 0) - (rows.filter { it.usage.payments > 0 }.maxOfOrNull { it.index } ?: 0)).coerceAtLeast(0)

    fun creationWarning(rows: List<ReceiveAddressRow>): String? = when (val unused = unusedAfterLastUsed(rows)) {
        in STOP_THRESHOLD..Int.MAX_VALUE -> tr("Use one of your unused addresses first. A restore from the seed would miss payments to new ones.")
        in WARNING_THRESHOLD until STOP_THRESHOLD -> pluralTr("%s unused addresses in a row. A restore from the seed finds payments only up to 200 past the last used one.", unused)
        else -> null
    }

    /** A manual choice stays until it receives a new payment. Named addresses are reserved. */
    fun nextIndex(selected: Int, baseline: Int?, rows: List<ReceiveAddressRow>, rotate: Boolean): Int? {
        val current = rows.firstOrNull { it.index == selected }
        if (!rotate) return if (current != null) selected else 0
        if (current != null && baseline != null && current.usage.payments <= baseline) return selected
        val lastReserved = rows.filter { it.usage.payments > 0 || it.labeled }.maxOfOrNull { it.index } ?: 0
        return rows.filter { it.index > lastReserved && it.usage.payments == 0 && !it.labeled }.minOfOrNull { it.index }
    }
}
