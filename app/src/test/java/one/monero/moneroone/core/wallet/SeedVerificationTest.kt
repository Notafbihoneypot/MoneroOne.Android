package one.monero.moneroone.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class SeedVerificationTest {
    @Test fun `a seed-derived address before file open is not backup verification`() {
        assertEquals(SeedVerification.UNKNOWN, SeedVerification.afterRead(null, complete = false, mismatch = false))
    }

    @Test fun `a complete matching local file verifies even without a network connection`() {
        assertEquals(SeedVerification.VERIFIED, SeedVerification.afterRead(null, complete = true, mismatch = false))
    }

    @Test fun `a later mismatch revokes an earlier successful verification`() {
        assertEquals(SeedVerification.MISMATCH,
            SeedVerification.afterRead(SeedVerification.VERIFIED, complete = true, mismatch = true))
    }

    @Test fun `seed-only fallback cannot clear a diagnosed mismatch`() {
        assertEquals(SeedVerification.MISMATCH,
            SeedVerification.afterRead(SeedVerification.MISMATCH, complete = false, mismatch = false))
    }

    @Test fun `closing a formerly verified file makes a new seed-only read unknown`() {
        assertEquals(SeedVerification.UNKNOWN,
            SeedVerification.afterRead(SeedVerification.VERIFIED, complete = false, mismatch = false))
    }
}
