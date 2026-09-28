package one.monero.moneroone.data.util

import java.math.BigDecimal
import java.math.RoundingMode

/** UI keyboards accept either decimal separator; payment requests always use '.'. */
object AmountInput {
    fun normalize(input: String, decimals: Int = 12): String? {
        val normalized = input.replace(',', '.')
        return normalized.takeIf { it.matches(Regex("[0-9]*(?:\\.[0-9]{0,$decimals})?")) }
    }

    fun fiatToXmr(input: String, price: Double): String? {
        if (!price.isFinite() || price <= 0) return null
        val fiat = normalize(input)?.toBigDecimalOrNull() ?: return null
        if (fiat.signum() < 0) return null
        return fiat.divide(BigDecimal.valueOf(price), 12, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
    }
}
