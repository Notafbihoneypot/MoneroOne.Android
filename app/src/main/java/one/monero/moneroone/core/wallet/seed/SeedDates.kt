package one.monero.moneroone.core.wallet.seed

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SeedDates {
    /**
     * [unixSeconds] as a month and year in UTC (the Polyseed birthday is a
     * UTC timestamp). The screen passes the locale's best pattern for the
     * skeleton "MMMMyyyy".
     */
    fun monthYear(unixSeconds: Long, pattern: String, locale: Locale): String =
        SimpleDateFormat(pattern, locale)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(unixSeconds * 1000))
}
