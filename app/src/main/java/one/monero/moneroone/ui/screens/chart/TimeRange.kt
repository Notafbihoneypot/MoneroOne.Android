package one.monero.moneroone.ui.screens.chart

/**
 * A chart range: the picker label, the price API's range parameter, how
 * much time it keeps, how long a fetch stays fresh, and its time axis.
 */
enum class TimeRange(val label: String, val apiRange: String) {
    DAY("24H", "1D"),
    WEEK("1W", "7D"),
    MONTH("1M", "1M"),
    YEAR("1Y", "1Y"),
    ALL("All", "All");

    /** Samples older than this are cut; null keeps the whole history. */
    val spanMs: Long?
        get() = when (this) {
            DAY -> DAY_MS
            WEEK -> 7 * DAY_MS
            MONTH -> 30 * DAY_MS
            YEAR -> 365 * DAY_MS
            ALL -> null
        }

    /** How long a fetched range is used before it is fetched again. */
    val cacheTtlMs: Long
        get() = when (this) {
            DAY -> 5 * MINUTE_MS
            WEEK -> 15 * MINUTE_MS
            else -> 60 * MINUTE_MS
        }

    val axis: ChartTimeAxis
        get() = when (this) {
            DAY -> ChartTimeAxis.DAY
            WEEK -> ChartTimeAxis.WEEK
            MONTH -> ChartTimeAxis.MONTH
            YEAR -> ChartTimeAxis.YEAR
            ALL -> ChartTimeAxis.ALL
        }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 60 * MINUTE_MS
    }
}
