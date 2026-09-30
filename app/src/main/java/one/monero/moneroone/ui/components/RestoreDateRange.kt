package one.monero.moneroone.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The dates a wallet birthday picker offers, as on iOS: the Monero genesis
 * day (2014-04-18) through today, never a future date. Material's picker
 * hands each day over as UTC midnight, so today is the local date at UTC
 * midnight.
 */
@OptIn(ExperimentalMaterial3Api::class)
object RestoreDateRange : SelectableDates {
    /** Monero mainnet genesis (iOS `genesisDate`, 1397818193). */
    const val GENESIS_MILLIS = 1_397_818_193_000L
    private const val DAY_MILLIS = 86_400_000L
    private const val GENESIS_DAY_MILLIS = GENESIS_MILLIS - GENESIS_MILLIS % DAY_MILLIS

    /** Today in the picker's terms: the local date at UTC midnight. */
    fun todayMillis(): Long = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The years the picker lists. */
    fun years(): IntRange = 2014..LocalDate.now().year

    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        utcTimeMillis in GENESIS_DAY_MILLIS..todayMillis()

    override fun isSelectableYear(year: Int): Boolean = year in years()
}
