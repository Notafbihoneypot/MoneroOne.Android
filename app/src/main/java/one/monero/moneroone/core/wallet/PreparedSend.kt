package one.monero.moneroone.core.wallet

import io.horizontalsystems.monerokit.SignedTransaction

/**
 * A transfer the Send review built and signed, not broadcast yet. Its [fee] is the one it pays for every input
 * it spends (wallet2's fee estimate counts one input). Send broadcasts this same transaction, for the [address],
 * [amount] and [sweepAll] the review showed.
 */
data class PreparedSend(
    val address: String,
    /** The amount the user asked to send; a sweep sends [SignedTransaction.amount]. Atomic units. */
    val amount: Long,
    val sweepAll: Boolean,
    val signed: SignedTransaction
) {
    val fee: Long get() = signed.fee

    /** What the recipient gets: the amount, or for a sweep the unlocked balance less the fee. */
    val sentAmount: Long get() = if (sweepAll) signed.amount else amount

    /** What leaves the wallet: what the recipient gets plus the fee. */
    val total: Long get() = sentAmount + fee

    /** This is the transfer for [address], [amount] (ignored for a sweep) and [sweepAll]. */
    fun matches(address: String, amount: Long, sweepAll: Boolean): Boolean =
        this.address == address && this.sweepAll == sweepAll && (sweepAll || this.amount == amount)
}

/** What building the Send review's transfer gave. */
sealed interface ReviewFeeResult {
    /** The transfer is built and signed: its fee is the real one. Send broadcasts [prepared]. */
    data class Ready(val prepared: PreparedSend) : ReviewFeeResult

    /**
     * Hardware/watch-only review: wallet2 can estimate the fee but cannot sign on the phone.
     * Keystone shows the final fee before the user approves the offline signature.
     */
    data class Estimated(val fee: Long) : ReviewFeeResult

    /** The amount plus the fee is more than the unlocked balance. [fee]: wallet2's estimate. Nothing was built. */
    data class OverBalance(val fee: Long) : ReviewFeeResult

    /** The build failed with [message] (wallet2's error). Nothing was built. */
    data class Failed(val message: String?) : ReviewFeeResult
}

/** The fee state the Send review shows. [prepared] is the transfer Send broadcasts; null until it is built. */
data class ReviewFeeState(
    val fee: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
    val prepared: PreparedSend? = null,
    /** The amount plus the fee is more than the unlocked balance, so nothing was built. */
    val overBalance: Boolean = false
) {
    val ready: Boolean get() = !loading && error == null && fee > 0
}

object ReviewFee {
    /**
     * Builds and signs the transfer the review shows, so its fee is the one the transfer pays. A transfer first
     * checks wallet2's [estimate] against [unlocked]: the estimate counts one input, so an amount it already puts
     * over the balance cannot go out, and nothing is built. Then [prepare] builds the transfer; when its real
     * inputs put it over the balance, wallet2 says "not enough money" and the result is [ReviewFeeResult.OverBalance].
     * A sweep is built at once. Blocking (both calls reach wallet2): call off Main.
     */
    fun build(
        address: String,
        amount: Long,
        sweepAll: Boolean,
        unlocked: Long,
        estimate: (amount: Long, sweepAll: Boolean) -> Long,
        prepare: (amount: Long, sweepAll: Boolean) -> SignedTransaction
    ): ReviewFeeResult {
        var estimated = 0L
        if (!sweepAll) {
            estimated = estimate(amount, false)
            if (estimated > 0 && amount + estimated > unlocked) return ReviewFeeResult.OverBalance(estimated)
        }
        val signed = try {
            prepare(amount, sweepAll)
        } catch (e: Exception) {
            if (!sweepAll && isNotEnoughMoney(e.message)) return ReviewFeeResult.OverBalance(estimated)
            return ReviewFeeResult.Failed(e.message)
        }
        if (signed.fee <= 0) return ReviewFeeResult.Failed(null)
        return ReviewFeeResult.Ready(PreparedSend(address, amount, sweepAll, signed))
    }

    /**
     * wallet2 could not cover the transfer: "not enough money to transfer, ..." (wallet API) or
     * "not enough unlocked money". "not enough outputs for specified ring size" is a different fault.
     */
    fun isNotEnoughMoney(message: String?): Boolean {
        val text = message ?: return false
        return text.contains("not enough money", ignoreCase = true) ||
            text.contains("not enough unlocked money", ignoreCase = true)
    }
}
