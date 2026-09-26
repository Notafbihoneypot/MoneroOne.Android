package one.monero.moneroone.data.util

import one.monero.moneroone.data.model.Currency
import java.text.NumberFormat
import java.util.Locale

/** Fiat amounts on the charts, in the currency's home locale. */
object MoneyFormat {

    fun locale(currency: Currency): Locale = when (currency) {
        Currency.USD -> Locale.US
        Currency.EUR -> Locale.GERMANY
        Currency.GBP -> Locale.UK
        Currency.CAD -> Locale.CANADA
        Currency.AUD -> Locale("en", "AU")
        Currency.JPY -> Locale.JAPAN
        Currency.CNY -> Locale.CHINA
        Currency.TRY -> Locale("tr", "TR")
        Currency.RUB -> Locale("ru", "RU")
        else -> Locale(currency.code, currency.code.uppercase())
    }

    /** [fractionDigits] null keeps the currency's own digits (JPY has none). */
    fun format(amount: Double, currency: Currency, fractionDigits: Int? = null): String {
        val format = NumberFormat.getCurrencyInstance(locale(currency))
        try {
            format.currency = java.util.Currency.getInstance(currency.code.uppercase())
        } catch (e: IllegalArgumentException) {
            // Unknown ISO code: keep the locale's currency.
        }
        if (fractionDigits != null) {
            format.minimumFractionDigits = fractionDigits
            format.maximumFractionDigits = fractionDigits
        }
        return format.format(amount)
    }
}
