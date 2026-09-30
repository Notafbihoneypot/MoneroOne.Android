package one.monero.moneroone.ui.screens.send

import one.monero.moneroone.ui.screens.send.PaymentRequestError.INVALID_ADDRESS
import one.monero.moneroone.ui.screens.send.PaymentRequestError.INVALID_AMOUNT
import one.monero.moneroone.ui.screens.send.PaymentRequestError.NOT_PAYMENT_LINK
import one.monero.moneroone.ui.screens.send.PaymentRequestError.PAYMENT_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentRequestTest {
    private val address = "86AWuSFkMKCNp4e7dWho3CBvFpvAzj8hnZNWM9fedD5LKb2mXVfnmH9XuDD9zYqzzR6LAFxUSsdGTVUDABzcgjMfFVfBHpP"
    private val valid: (String) -> Boolean = { it == address }

    private fun read(input: String) = readPaymentRequest(input, valid)
    private fun error(input: String) = (read(input) as? PaymentRequestResult.Invalid)?.error

    @Test fun readsAddressAndAmount() {
        val result = read("monero:$address?tx_amount=0.25&tx_description=Coffee")
        assertEquals(
            PaymentRequestResult.Valid(MoneroUriData(address, "0.25", description = "Coffee")),
            result
        )
    }

    @Test fun addressErrorsSayTheAddressIsBad() {
        assertEquals(INVALID_ADDRESS, error("monero:${address.dropLast(1)}"))
        assertEquals(INVALID_ADDRESS, error("monero:${address.replaceFirst('8', '9')}"))
        // Right shape, failed checksum or network.
        assertEquals(INVALID_ADDRESS, readPaymentRequest("monero:$address") { false }.let { (it as PaymentRequestResult.Invalid).error })
    }

    @Test fun amountErrorsSayTheAmountIsBad() {
        assertEquals(INVALID_AMOUNT, error("monero:$address?tx_amount=abc"))
        assertEquals(INVALID_AMOUNT, error("monero:$address?tx_amount=0"))
        assertEquals(INVALID_AMOUNT, error("monero:$address?tx_amount=1.1234567890123"))
        assertEquals(INVALID_AMOUNT, error("monero:$address?tx_amount=99999999"))
    }

    @Test fun paymentIdIsRefusedWithItsOwnReason() {
        assertEquals(PAYMENT_ID, error("monero:$address?tx_payment_id=0123456789abcdef"))
        assertEquals(PAYMENT_ID, error("monero:$address?payment_id=0123456789abcdef"))
    }

    @Test fun malformedLinksAreNotPaymentLinks() {
        assertEquals(NOT_PAYMENT_LINK, error("monero:$address?tx_amount"))
        assertEquals(NOT_PAYMENT_LINK, error("monero:$address?tx_amount=1&amount=2"))
        assertEquals(NOT_PAYMENT_LINK, error("monero:$address?req-fee=1"))
        assertEquals(NOT_PAYMENT_LINK, error("monero:$address#fragment"))
    }

    @Test fun parseKeepsItsNullContract() {
        assertEquals(MoneroUriData(address), parsePaymentRequest(address, valid))
        assertNull(parsePaymentRequest("monero:$address?tx_amount=abc", valid))
    }
}
