package one.monero.moneroone.data.util

import java.math.BigDecimal
import java.math.RoundingMode

/** XMR amounts: at least 4 decimals, up to 12, rounded down, no grouping. */
object XmrFormat {

    private val ATOMIC_UNITS_PER_XMR = BigDecimal(1_000_000_000_000L)

    fun format(atomicUnits: Long): String {
        val xmr = BigDecimal(atomicUnits).divide(ATOMIC_UNITS_PER_XMR)
        val full = xmr.setScale(12, RoundingMode.DOWN).stripTrailingZeros()
        // Always show at least 4 decimal places
        return if (full.scale() < 4) full.setScale(4).toPlainString() else full.toPlainString()
    }
}
