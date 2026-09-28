package one.monero.moneroone.data.util

import java.math.BigInteger
import java.math.BigDecimal
import java.math.RoundingMode

/** XMR amounts: at least 4 decimals, up to 12, rounded down, no grouping. */
object XmrFormat {

    private val ATOMIC_UNITS_PER_XMR = BigDecimal(1_000_000_000_000L)

    /** Four decimals, or four significant digits for small amounts; never round up. */
    fun compact(atomicUnits: Long): String {
        val xmr = BigDecimal(atomicUnits).divide(ATOMIC_UNITS_PER_XMR)
        val scale = if (atomicUnits == 0L) 4 else
            (4 - xmr.abs().precision() + xmr.abs().scale()).coerceIn(4, 12)
        return xmr.setScale(scale, RoundingMode.DOWN).stripTrailingZeros()
            .let { if (it.scale() < 4) it.setScale(4) else it }.toPlainString()
    }

    fun format(atomicUnits: Long): String = format(BigInteger.valueOf(atomicUnits))

    fun format(atomicUnits: BigInteger): String {
        val xmr = BigDecimal(atomicUnits).divide(ATOMIC_UNITS_PER_XMR)
        val full = xmr.setScale(12, RoundingMode.DOWN).stripTrailingZeros()
        // Always show at least 4 decimal places
        return if (full.scale() < 4) full.setScale(4).toPlainString() else full.toPlainString()
    }
}
