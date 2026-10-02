package one.monero.moneroone.core.wallet

/**
 * What Send asks the kit to send. [amount] is in atomic units.
 *
 * The kit saves [memo] as a local wallet2 note on the first transaction ID
 * and stores the wallet at once, as iOS does. The note stays on this device:
 * it never goes on chain, and a restore from the seed does not bring it back.
 */
data class SendRequest(
    val address: String,
    val amount: Long,
    val memo: String?,
    val sweepAll: Boolean,
) {
    /** Calls the kit's send with each value of this request. */
    fun sendWith(kit: KitSend) = kit.send(amount, address, memo, sweepAll)

    companion object {
        /** [memo] is the memo field as typed. An empty field sends no memo, as on iOS. */
        fun of(address: String, amount: Long, memo: String, sweepAll: Boolean) =
            SendRequest(address, amount, memo.ifEmpty { null }, sweepAll)
    }
}

/** The kit's send, which also saves the memo. Tests pass their own. */
fun interface KitSend {
    fun send(amount: Long, address: String, memo: String?, sweepAll: Boolean)
}
