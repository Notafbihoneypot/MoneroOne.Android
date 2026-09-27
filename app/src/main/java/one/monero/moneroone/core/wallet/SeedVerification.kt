package one.monero.moneroone.core.wallet

/** A seed-derived address alone cannot verify the keys held in an existing wallet file. */
enum class SeedVerification {
    UNKNOWN, VERIFIED, MISMATCH;

    companion object {
        fun afterRead(previous: SeedVerification?, complete: Boolean, mismatch: Boolean): SeedVerification = when {
            mismatch -> MISMATCH
            complete -> VERIFIED
            previous == MISMATCH -> MISMATCH
            else -> UNKNOWN
        }
    }
}
