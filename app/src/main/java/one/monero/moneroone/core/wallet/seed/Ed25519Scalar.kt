package one.monero.moneroone.core.wallet.seed

import java.math.BigInteger

/** Ed25519 scalar helpers (monero crypto-ops.c). */
object Ed25519Scalar {
    /** The group order l = 2^252 + 27742317777372353535851937790883648493. */
    private val L: BigInteger =
        BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))

    /** sc_reduce32: a 32-byte little-endian integer mod l, as 32 little-endian bytes. */
    fun reduce32(input: ByteArray): ByteArray {
        require(input.size == 32) { "sc_reduce32 needs 32 bytes" }
        val bigEndian = ByteArray(32) { input[31 - it] }
        val reduced = BigInteger(1, bigEndian).mod(L).toByteArray()
        bigEndian.fill(0)
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val src = reduced.size - 1 - i
            out[i] = if (src >= 0) reduced[src] else 0
        }
        reduced.fill(0)
        return out
    }

    /** True when the scalar is zero mod l (wallet2 derives no usable key from it). */
    fun isZero(input: ByteArray): Boolean = reduce32(input).all { it == 0.toByte() }
}
