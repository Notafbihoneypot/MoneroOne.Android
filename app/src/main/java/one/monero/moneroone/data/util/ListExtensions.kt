package one.monero.moneroone.data.util

import kotlin.math.abs

/**
 * The index of the element whose timestamp is nearest [target], by binary
 * search over a list sorted by [timestamp]. A tie goes to the later element.
 * -1 for an empty list.
 */
inline fun <T> List<T>.nearestIndexByTimestamp(target: Long, timestamp: (T) -> Long): Int {
    if (isEmpty()) return -1

    var low = 0
    var high = lastIndex
    while (low < high) {
        val mid = (low + high) ushr 1
        if (timestamp(this[mid]) < target) low = mid + 1 else high = mid
    }
    if (low == 0) return 0

    val previous = abs(timestamp(this[low - 1]) - target)
    val current = abs(timestamp(this[low]) - target)
    return if (previous < current) low - 1 else low
}

/** The element [nearestIndexByTimestamp] finds; null for an empty list. */
inline fun <T> List<T>.nearestByTimestamp(target: Long, timestamp: (T) -> Long): T? =
    getOrNull(nearestIndexByTimestamp(target, timestamp))
