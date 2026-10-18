package one.monero.moneroone.paymentlinks

import one.monero.moneroone.core.locale.tr

/**
 * A link's name on this phone: its note, else "Link 7". Sent and received
 * links share one counter, so every link here has its own number. The note and
 * the number stay on this phone: neither is in the link, its URL, the share
 * text or a tx memo.
 */
object LinkNames {
    /** The longest note the Send field and the rename dialog take. */
    const val NOTE_LIMIT = 80

    /** The note to save: trimmed, at most [NOTE_LIMIT] characters; null when blank. */
    fun cleanNote(note: String?): String? = note?.trim()?.take(NOTE_LIMIT)?.trim()?.takeIf { it.isNotEmpty() }

    /** The note, else "Link 7"; a record with no number yet reads "Payment link". */
    fun name(note: String?, number: Int?): String =
        cleanNote(note) ?: number?.let { tr("Link %s", it) } ?: tr("Payment link")

    /** The highest number in use: the saved counter, or a record above it. */
    fun highest(sent: List<SentLink>, received: List<ReceivedLink>, counter: Int): Int =
        maxOf(counter, sent.maxOfOrNull { it.number ?: 0 } ?: 0, received.maxOfOrNull { it.number ?: 0 } ?: 0)

    class Numbered(val sent: List<SentLink>, val received: List<ReceivedLink>, val counter: Int)

    /**
     * Records from before numbers get one each, once, in the order they were made
     * (sent and received mixed), after the highest number in use.
     */
    fun numbered(sent: List<SentLink>, received: List<ReceivedLink>, counter: Int): Numbered {
        var next = highest(sent, received, counter)
        val missing = (sent.filter { it.number == null }.map { Triple(it.createdAtMillis, 0, it.id) } +
            received.filter { it.number == null }.map { Triple(it.receivedAtMillis, 1, it.id) })
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
        if (missing.isEmpty()) return Numbered(sent, received, next)
        val sentNumbers = HashMap<String, Int>()
        val receivedNumbers = HashMap<String, Int>()
        for ((_, kind, id) in missing) {
            next += 1
            if (kind == 0) sentNumbers[id] = next else receivedNumbers[id] = next
        }
        return Numbered(
            sent.map { link -> sentNumbers[link.id]?.let { link.copy(number = it) } ?: link },
            received.map { link -> receivedNumbers[link.id]?.let { link.copy(number = it) } ?: link },
            next
        )
    }
}
