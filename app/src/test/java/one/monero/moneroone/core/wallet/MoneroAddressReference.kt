package one.monero.moneroone.core.wallet

import java.math.BigInteger

/**
 * Test-only reference for the primary address wallet2 derives from a
 * 25-word seed's recovery key (account_base::generate in monero
 * src/cryptonote_basic/account.cpp): spend = sc_reduce32(key), view =
 * sc_reduce32(keccak256(spend)) (the hash of the REDUCED spend key), address
 * = base58(0x12 | spend*G | view*G | first 4 bytes of keccak256 of those).
 *
 * Plain BigInteger math: slow and not constant-time. Unit tests only, where
 * the native kit cannot load.
 */
object MoneroAddressReference {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D = BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555")
    private val BASE = Point(
        BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
        BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")
    )
    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)

    private const val MAINNET_ADDRESS_TAG: Byte = 0x12
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val ENCODED_BLOCK_SIZES = intArrayOf(0, 2, 3, 5, 6, 7, 9, 10, 11)
    private val FIFTY_EIGHT: BigInteger = BigInteger.valueOf(58)

    private class Point(val x: BigInteger, val y: BigInteger)

    fun primaryAddress(recoveryKey: ByteArray): String {
        val spend = scReduce32(recoveryKey)
        val view = scReduce32(keccak256(spend))
        val data = byteArrayOf(MAINNET_ADDRESS_TAG) + publicKey(spend) + publicKey(view)
        return base58(data + keccak256(data).copyOf(4))
    }

    fun scReduce32(bytes: ByteArray): ByteArray = littleEndian32(BigInteger(1, bytes.reversedArray()).mod(L))

    /** scalar * G (ge_scalarmult_base), compressed: y little-endian, the sign of x in the top bit. */
    fun publicKey(scalar: ByteArray): ByteArray {
        val k = BigInteger(1, scalar.reversedArray())
        var result = IDENTITY
        var addend = BASE
        for (bit in 0 until k.bitLength()) {
            if (k.testBit(bit)) result = add(result, addend)
            addend = add(addend, addend)
        }
        val out = littleEndian32(result.y)
        if (result.x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** Twisted Edwards addition, a = -1 (complete for ed25519). */
    private fun add(p: Point, q: Point): Point {
        val t = D.multiply(p.x).multiply(q.x).multiply(p.y).multiply(q.y).mod(P)
        val x = p.x.multiply(q.y).add(p.y.multiply(q.x)).multiply(BigInteger.ONE.add(t).modInverse(P)).mod(P)
        val y = p.y.multiply(q.y).add(p.x.multiply(q.x)).multiply(BigInteger.ONE.subtract(t).mod(P).modInverse(P)).mod(P)
        return Point(x, y)
    }

    private fun littleEndian32(n: BigInteger): ByteArray {
        val bigEndian = n.toByteArray()
        val out = ByteArray(32)
        for (i in bigEndian.indices) {
            val position = bigEndian.size - 1 - i
            if (position < 32) out[position] = bigEndian[i]
        }
        return out
    }

    /** Monero base58: 8-byte blocks, each a fixed number of characters. */
    fun base58(data: ByteArray): String {
        val out = StringBuilder()
        for (start in data.indices step 8) {
            val block = data.copyOfRange(start, minOf(start + 8, data.size))
            val chars = CharArray(ENCODED_BLOCK_SIZES[block.size]) { ALPHABET[0] }
            var num = BigInteger(1, block)
            var i = chars.size - 1
            while (num.signum() > 0) {
                val (quotient, remainder) = num.divideAndRemainder(FIFTY_EIGHT)
                chars[i--] = ALPHABET[remainder.toInt()]
                num = quotient
            }
            out.append(chars)
        }
        return out.toString()
    }

    private val ROUND_CONSTANTS: LongArray = listOf(
        0x0000000000000001uL, 0x0000000000008082uL, 0x800000000000808AuL, 0x8000000080008000uL,
        0x000000000000808BuL, 0x0000000080000001uL, 0x8000000080008081uL, 0x8000000000008009uL,
        0x000000000000008AuL, 0x0000000000000088uL, 0x0000000080008009uL, 0x000000008000000AuL,
        0x000000008000808BuL, 0x800000000000008BuL, 0x8000000000008089uL, 0x8000000000008003uL,
        0x8000000000008002uL, 0x8000000000000080uL, 0x000000000000800AuL, 0x800000008000000AuL,
        0x8000000080008081uL, 0x8000000000008080uL, 0x0000000080000001uL, 0x8000000080008008uL,
    ).map { it.toLong() }.toLongArray()

    /** Rotation offsets, lane x + 5y. */
    private val ROTATIONS = intArrayOf(
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14,
    )

    /** Keccak-256 as Monero uses it (cn_fast_hash): the original padding (0x01), not SHA3. */
    fun keccak256(input: ByteArray): ByteArray {
        val rate = 136
        val padded = ByteArray((input.size / rate + 1) * rate)
        input.copyInto(padded)
        padded[input.size] = (padded[input.size].toInt() xor 0x01).toByte()
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() xor 0x80).toByte()
        val state = LongArray(25)
        for (offset in padded.indices step rate) {
            for (lane in 0 until rate / 8) state[lane] = state[lane] xor readLane(padded, offset + lane * 8)
            keccakF(state)
        }
        return ByteArray(32) { (state[it / 8] ushr (8 * (it % 8))).toByte() }
    }

    private fun readLane(bytes: ByteArray, offset: Int): Long {
        var lane = 0L
        for (i in 7 downTo 0) lane = (lane shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return lane
    }

    private fun keccakF(a: LongArray) {
        val c = LongArray(5)
        val b = LongArray(25)
        for (round in 0 until 24) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
                for (y in 0 until 5) a[x + 5 * y] = a[x + 5 * y] xor d
            }
            for (x in 0 until 5) for (y in 0 until 5) {
                b[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(a[x + 5 * y], ROTATIONS[x + 5 * y])
            }
            for (x in 0 until 5) for (y in 0 until 5) {
                a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
            }
            a[0] = a[0] xor ROUND_CONSTANTS[round]
        }
    }
}
