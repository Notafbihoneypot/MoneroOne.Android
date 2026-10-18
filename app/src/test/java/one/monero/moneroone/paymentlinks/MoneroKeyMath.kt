package one.monero.moneroone.paymentlinks

import java.math.BigInteger

/**
 * Test-only Monero key math in plain Kotlin (slow, not constant time): the
 * original Keccak-256, ed25519 base-point multiplication and Monero's block
 * Base58 address. It lets the JVM tests check the spec's vector addresses
 * without the native kit; the instrumented test checks them through wallet2.
 */
object MoneroKeyMath {

    // Keccak-256 (original padding 0x01, as Monero's cn_fast_hash).

    private val ROUND_CONSTANTS = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, -0x7fffffffffff7f76L, -0x7fffffff7fff8000L,
        0x000000000000808BL, 0x0000000080000001L, -0x7fffffff7fff7f7fL, -0x7fffffffffff7ff7L,
        0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
        0x000000008000808BL, -0x7fffffffffffff75L, -0x7fffffffffff7f77L, -0x7fffffffffff7ffdL,
        -0x7fffffffffff7ffeL, -0x7fffffffffffff80L, 0x000000000000800AL, -0x7fffffff7ffffff6L,
        -0x7fffffff7fff7f7fL, -0x7fffffffffff7f80L, 0x0000000080000001L, -0x7fffffff7fff7ff8L
    )

    /** Rotation offsets by lane index x + 5y. */
    private val ROTATIONS = intArrayOf(0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14)

    private const val RATE = 136

    fun keccak256(input: ByteArray): ByteArray {
        val padded = ByteArray(((input.size / RATE) + 1) * RATE)
        input.copyInto(padded)
        padded[input.size] = (padded[input.size].toInt() xor 0x01).toByte()
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() xor 0x80).toByte()
        val state = LongArray(25)
        for (block in 0 until padded.size / RATE) {
            for (i in 0 until RATE / 8) {
                var lane = 0L
                for (b in 0 until 8) lane = lane or ((padded[block * RATE + i * 8 + b].toLong() and 0xff) shl (8 * b))
                state[i] = state[i] xor lane
            }
            permute(state)
        }
        val out = ByteArray(32)
        for (i in 0 until 32) out[i] = (state[i / 8] ushr (8 * (i % 8))).toByte()
        return out
    }

    private fun permute(a: LongArray) {
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

    // ed25519.

    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    private data class Point(val x: BigInteger, val y: BigInteger)

    private val BASE: Point = run {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val xx = y.multiply(y).subtract(BigInteger.ONE).multiply(D.multiply(y).multiply(y).add(BigInteger.ONE).modInverse(P)).mod(P)
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(xx).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.testBit(0)) x = P.subtract(x)
        Point(x, y)
    }

    private fun add(p: Point, q: Point): Point {
        val t = D.multiply(p.x).multiply(q.x).multiply(p.y).multiply(q.y).mod(P)
        val x = p.x.multiply(q.y).add(p.y.multiply(q.x)).multiply(BigInteger.ONE.add(t).modInverse(P)).mod(P)
        val y = p.y.multiply(q.y).add(p.x.multiply(q.x)).multiply(BigInteger.ONE.subtract(t).mod(P).modInverse(P)).mod(P)
        return Point(x, y)
    }

    /** The compressed point scalar·B; the scalar is 32 little-endian bytes. */
    fun publicKey(scalar: ByteArray): ByteArray {
        val n = Ed25519Scalar.fromLittleEndian(scalar)
        var result = Point(BigInteger.ZERO, BigInteger.ONE)
        var addend = BASE
        for (bit in 0 until n.bitLength()) {
            if (n.testBit(bit)) result = add(result, addend)
            addend = add(addend, addend)
        }
        val out = Ed25519Scalar.toLittleEndian32(result.y)
        if (result.x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** wallet2's view key: `sc_reduce32(keccak256(spend))`. */
    fun viewKey(spend: ByteArray): ByteArray = Ed25519Scalar.reduce32(keccak256(spend))

    /** A mainnet standard address (prefix 18). */
    fun address(publicSpend: ByteArray, publicView: ByteArray): String {
        val body = byteArrayOf(0x12) + publicSpend + publicView
        return moneroBase58(body + keccak256(body).copyOf(4))
    }

    private val BLOCK_SIZES = intArrayOf(0, 2, 3, 5, 6, 7, 9, 10, 11)

    private fun moneroBase58(data: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i < data.size) {
            val block = data.copyOfRange(i, minOf(i + 8, data.size))
            var value = BigInteger(1, block)
            val chars = CharArray(BLOCK_SIZES[block.size]) { '1' }
            var j = chars.size - 1
            while (value.signum() > 0) {
                val (q, r) = value.divideAndRemainder(BigInteger.valueOf(58))
                chars[j--] = Base58Integer.ALPHABET[r.toInt()]
                value = q
            }
            out.append(chars)
            i += 8
        }
        return out.toString()
    }
}
