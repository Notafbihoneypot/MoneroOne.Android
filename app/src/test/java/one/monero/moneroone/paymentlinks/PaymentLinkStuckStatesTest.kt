package one.monero.moneroone.paymentlinks

import io.horizontalsystems.monerokit.model.TransactionInfo
import io.horizontalsystems.monerokit.model.Transfer
import one.monero.moneroone.core.wallet.WalletInfo
import one.monero.moneroone.core.wallet.WalletSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No stuck or invisible money (spec 2026-10-17): a funding found by its signed txid, links of a wallet that is
 * gone, a sweep saved before its broadcast, two devices on one link, and a settle grace counted in blocks.
 * The numbers match iOS: 30 blocks of grace, adoption by txid with no time limit.
 */
class PaymentLinkStuckStatesTest {

    private val secret = "000102030405060708090a0b0c0d0e0f"
    private val now = 1_800_000_000L
    private val envelope = "4envelopeAddress"
    private val signedTxid = "aa11"

    /** A link whose funding was signed (its txid saved) and whose broadcast then timed out. */
    private fun attempt(
        id: String = "0123456789abcdef",
        walletId: String = "w",
        state: SentState = SentState.NOT_FUNDED,
        check: EnvelopeSnapshot? = null,
        createdAt: Long = 1
    ) = SentLink(
        id = id, height = 100, amount = 20_000_000_000, expiry = now + 86_400, createdAtMillis = createdAt, walletId = walletId,
        envelopeAddress = envelope, feeReserve = 300_000_000, state = state, attemptTxids = listOf(signedTxid),
        lastCheck = check, secret = secret, number = 4
    )

    private fun funded(id: String, walletId: String, check: EnvelopeSnapshot? = null) = SentLink(
        id = id, height = 100, amount = 20_000_000_000, expiry = now + 86_400, createdAtMillis = 5, walletId = walletId,
        envelopeAddress = "4$id", feeReserve = 1, state = SentState.FUNDED, fundingTxid = "f-$id", lastCheck = check, secret = secret
    )

    private fun scan(
        at: Long = 1,
        height: Long = 0,
        balance: Long = 7,
        unlocked: Long = balance,
        mined: List<String> = emptyList(),
        failed: List<String> = emptyList(),
        spends: List<String> = mined,
        fundings: List<String> = listOf("fund"),
        seen: Boolean = true
    ) = EnvelopeSnapshot(
        checkedAt = at, fundingSeen = seen, fundingMined = seen, confirmations = if (seen) 40 else 0, balance = balance,
        unlocked = unlocked, spendSeen = spends.isNotEmpty(), spendTxids = spends, fundingTxids = fundings,
        minedSpendTxids = mined, failedSpendTxids = failed, scannedHeight = height
    )

    /** An outgoing transaction of the open wallet as wallet2 lists one it learned of from the chain: no destinations. */
    private fun outgoing(hash: String, to: String? = null) = TransactionInfo(
        1, false, false, 20_300_000_000, 40_000_000, 120, hash, now - 10, "", 0, 0, 3, 0, "",
        to?.let { listOf(Transfer(20_300_000_000, it)) } ?: emptyList()
    )

    private val none = WalletHistory()

    // ------------------------------------------------------------ 1: adoption by the pre-commit txid

    @Test
    fun `a funding whose broadcast timed out stays hidden until its txid shows, then waits`() {
        val link = attempt()
        // Signed, not known: no Share, no count, until a history or scan shows the txid.
        assertTrue(LinkStatusRules.isHidden(link))
        assertEquals(emptyMap<String, String>(), LinkStatusRules.fundingsToAdopt(listOf(link), setOf("other")) { null })
        // The mined funding comes back through wallet2's outgoing scan with no destinations: the address cannot match.
        val history = listOf(outgoing(signedTxid))
        val found = LinkStatusRules.fundingsToAdopt(listOf(link), history.mapNotNull { it.hash }.toSet()) { null }
        assertEquals(mapOf(link.id to signedTxid), found)
        val adopted = link.copy(fundingTxid = signedTxid, state = SentState.FUNDED)
        assertFalse(LinkStatusRules.isHidden(adopted))
        assertEquals(LinkStatus.PENDING, LinkStatusRules.baseStatus(adopted))
        assertEquals(listOf(SentLinkAction.SHARE_AGAIN, SentLinkAction.RECLAIM), LinkStatusRules.sentActions(LinkStatus.PENDING))
    }

    @Test
    fun `adoption by txid has no time limit and ignores the address and the wallet`() {
        // Long past the hidden scan's day of blocks, from another wallet on this device (a seed restore).
        val old = attempt(walletId = "deleted", check = scan(seen = false, height = 100_000, fundings = emptyList()), createdAt = 1)
        assertFalse(LinkStatusRules.hiddenScanDue(old, now * 1000))
        assertEquals(mapOf(old.id to signedTxid), LinkStatusRules.fundingsToAdopt(listOf(old), setOf(signedTxid)) { null })
        // A record with a signed txid is never adopted by address: only its own txid counts.
        assertEquals(emptyMap<String, String>(), LinkStatusRules.fundingsToAdopt(listOf(old), setOf("zz")) { "zz" })
        // A record saved before attempts had txids still adopts by address.
        val legacy = old.copy(attemptTxids = emptyList())
        assertEquals(mapOf(legacy.id to "zz"), LinkStatusRules.fundingsToAdopt(listOf(legacy), emptySet()) { "zz" })
    }

    @Test
    fun `a known funding txid is not adopted again, so a failed one stays hidden`() {
        val failed = attempt().copy(fundingTxid = signedTxid, fundingFailed = true)
        assertEquals(emptyMap<String, String>(), LinkStatusRules.fundingsToAdopt(listOf(failed), setOf(signedTxid)) { null })
        assertTrue(LinkStatusRules.isHidden(failed))
        // Live again in the history: shown again.
        assertEquals(mapOf(failed.id to false), LinkStatusRules.fundingFailures(listOf(failed), emptySet(), setOf(signedTxid)))
    }

    @Test
    fun `the envelope scan adopts the signed txid it sees`() {
        val link = attempt()
        val seen = LinkStatusRules.withCheck(link, scan(fundings = listOf("earlier", signedTxid)))
        assertEquals(signedTxid, seen.fundingTxid)
        assertEquals(SentState.FUNDED, seen.state)
        assertEquals(LinkStatus.READY, LinkStatusRules.baseStatus(seen))
    }

    @Test
    fun `an attempt whose txid was saved keeps its hidden scan`() {
        // The send failed after the txid was saved: it may have gone out, so it is never "before broadcast".
        val link = attempt().copy(failedBeforeBroadcast = false)
        assertTrue(LinkStatusRules.hiddenScanDue(link, now * 1000))
    }

    // ------------------------------------------------------------ 2: orphaned links

    @Test
    fun `a link whose funding wallet is gone belongs to the open wallet`() {
        val walletIds = setOf("open", "other")
        val orphan = funded("orphan", walletId = "deleted")
        val others = funded("others", walletId = "other")
        val mine = funded("mine", walletId = "open")
        assertTrue(OpenLinkRules.belongsTo(orphan, "open", walletIds))
        assertTrue(OpenLinkRules.belongsTo(mine, "open", walletIds))
        assertFalse(OpenLinkRules.belongsTo(others, "open", walletIds))
        // The wallet list not known yet: only the funding wallet counts.
        assertFalse(OpenLinkRules.belongsTo(orphan, "open", emptySet()))
        val open = OpenLinkRules.openLinks(listOf(orphan, others, mine), emptyList(), "open", now, walletIds)
        assertEquals(setOf("orphan", "mine"), open.sent.map { it.id }.toSet())
        assertEquals(40_000_000_000, open.sentAmount)
    }

    @Test
    fun `an open link with no history row still shows under Open`() {
        val orphan = funded("orphan", walletId = "deleted")
        val index = PaymentLinkIndex(listOf(orphan), emptyList())
        val sections = LinkListRules.sections(emptyList(), index, emptyList(), now, openSent = listOf(orphan))
        assertEquals(listOf("orphan"), sections.open.map { it.linkId })
        assertEquals(orphan, sections.open.single().sent)
        assertEquals(1, sections.linkCount)
        val row = PaymentLinkActivityRules.sentRow(orphan, LinkStatus.PENDING, "Oct 16", fiatMode = false, fiatValue = null)
        assertEquals("Waiting", PaymentLinkActivityRules.stateLabel(row.state!!))
        // Its funding row in this wallet's history: the row shows once.
        val tx = TransactionInfo(1, false, false, 1, 1, 50, "f-orphan", now - 10, "", 0, 0, 3, 0, "", emptyList())
        val withRow = LinkListRules.sections(listOf(tx), index, emptyList(), now, openSent = listOf(orphan))
        assertEquals(listOf("f-orphan"), withRow.open.map { it.key })
    }

    @Test
    fun `an orphaned link reclaims into the open wallet`() {
        val main = WalletInfo(id = "open", name = "Main", source = WalletSource.BIP39, cachedPrimaryAddress = null, derivedWalletId = "c")
        val eligible = ClaimDestinations.eligible(listOf(main), "open", "4" + "A".repeat(94)) { true }
        assertEquals("open", ClaimDestinations.default(eligible, reclaim = true, fundingWalletId = "deleted")?.walletId)
    }

    // ------------------------------------------------------------ 3: money left after a sweep (Android keeps checking)

    @Test
    fun `a sweep that left locked money keeps checking until it unlocks`() {
        val reclaimed = LinkStatusRules.reclaimSigned(funded("l", "w", scan()), "ours", at = 10, height = 500)
        // Our reclaim in a block, a second funding still locked.
        val locked = scan(at = 20, height = 520, balance = 5, unlocked = 0, mined = listOf("ours"))
        val kept = LinkStatusRules.withCheck(reclaimed, locked)
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(kept))
        assertTrue(LinkStatusRules.settleDue(kept, none))
        // It unlocks: the link opens again.
        val open = LinkStatusRules.withCheck(kept, scan(at = 30, height = 530, balance = 5, unlocked = 5, mined = listOf("ours")))
        assertEquals(LinkStatus.READY, LinkStatusRules.baseStatus(open))
    }

    // ------------------------------------------------------------ 4, 5, 7: sweeps saved before their broadcast

    @Test
    fun `a reclaim saved before its broadcast reads Reclaimed after an app kill`() {
        val before = funded("l", "w", scan(spends = listOf("old"), mined = listOf("old")))
        // Killed right after the save: no outcome was ever recorded.
        val saved = LinkStatusRules.reclaimSigned(before, "ours", at = 10, height = 500)
        assertEquals("ours", saved.reclaimTxid)
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(saved))
        assertEquals(listOf("old"), saved.spendsBeforeReclaim)
        // The envelope shows our spend: ours, never a claim that beat it, and final once mined.
        val mined = LinkStatusRules.withCheck(saved, scan(height = 505, balance = 0, mined = listOf("old", "ours")))
        assertFalse(mined.reclaimLostRace)
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(mined))
        assertFalse(LinkStatusRules.settleUnconfirmed(mined))
        // The node refused it: the record goes back.
        assertEquals(before, LinkStatusRules.reclaimRefused(saved, before))
    }

    @Test
    fun `a claim saved before its broadcast is ours, and never an empty txid`() {
        val before = ReceivedLink(
            id = "r", words = null, height = 100, amount = 0, expiry = 0, receivedAtMillis = 1, lastCheck = scan(), secret = secret
        )
        val saved = before.claimSigned("ours", amount = 6, to = "Main", at = 10, height = 500)
        assertEquals(ReceivedState.CLAIMED, saved.state)
        assertEquals(listOf("ours"), saved.settleTxids)
        assertTrue(saved.claimedHere)
        assertEquals(500L, saved.settledHeight)
        assertEquals(before, saved.claimRefused(before))
    }

    @Test
    fun `a sweep saved and never broadcast reopens after its grace when the money is still there`() {
        val saved = LinkStatusRules.reclaimSigned(funded("l", "w", scan()), "ours", at = 10, height = 500)
        val within = LinkStatusRules.withCheck(saved, scan(height = 530))
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(within))
        val past = LinkStatusRules.withCheck(within, scan(height = 531))
        assertEquals(LinkStatus.READY, LinkStatusRules.baseStatus(past))
    }

    // ------------------------------------------------------------ 9: two devices claim one link

    @Test
    fun `the device that lost the claim race settles Claimed and stops checking`() {
        val opened = ReceivedLink(
            id = "r", words = null, height = 100, amount = 0, expiry = 0, receivedAtMillis = 1, lastCheck = scan(), secret = secret
        )
        val ours = opened.claimSigned("ours", amount = 6, to = "Main", at = 10, height = 500)
        // Ours dropped from the pool; the other device's claim is in a block; nothing left.
        val lost = scan(at = 20, height = 503, balance = 0, mined = listOf("theirs"), failed = listOf("ours"), spends = listOf("theirs"))
        val settled = ours.noting(lost)
        assertTrue(settled.claimLostRace)
        assertEquals(ReceivedState.CLAIMED, settled.state)
        assertFalse(settled.claimedHere)
        assertEquals(emptyList<String>(), settled.settleTxids)
        assertFalse(settled.settleDue(none))
        // Checked again: nothing changes.
        assertEquals(settled, settled.noting(lost.copy(checkedAt = 99)))
    }

    @Test
    fun `a claim race is not lost while the other spend waits in the pool, our own is live, or money is left`() {
        val ours = ReceivedLink(
            id = "r", words = null, height = 100, amount = 0, expiry = 0, receivedAtMillis = 1, lastCheck = scan(), secret = secret
        ).claimSigned("ours", amount = 6, to = "Main", at = 10, height = 500)
        assertFalse(LinkStatusRules.claimLost("ours", emptyList(), scan(balance = 0, spends = listOf("theirs"))))
        assertFalse(LinkStatusRules.claimLost("ours", emptyList(), scan(balance = 0, mined = listOf("theirs"), spends = listOf("theirs", "ours"))))
        assertFalse(LinkStatusRules.claimLost("ours", emptyList(), scan(balance = 3, mined = listOf("theirs"))))
        // An earlier settle of ours is not a claim that beat this one.
        assertFalse(LinkStatusRules.claimLost("ours", listOf("earlier"), scan(balance = 0, mined = listOf("earlier"))))
        assertFalse(ours.noting(scan(balance = 0, spends = listOf("theirs"))).claimLostRace)
    }

    // ------------------------------------------------------------ 10: grace by blocks

    @Test
    fun `a settle reads dropped 30 blocks past its height, whatever the clock says`() {
        val txids = listOf("ours")
        val sentAt = 1_000_000L
        val hour = LinkStatusRules.SETTLE_GRACE_MS
        assertEquals(30L, LinkStatusRules.SETTLE_GRACE_BLOCKS)
        // A clock that ran far ahead does not drop it within 30 blocks.
        assertEquals(emptyList<String>(), LinkStatusRules.droppedSettles(txids, sentAt, scan(at = sentAt + 10 * hour, height = 530), none, settledHeight = 500))
        // A clock that ran behind does not keep it past 30 blocks.
        assertEquals(txids, LinkStatusRules.droppedSettles(txids, sentAt, scan(at = sentAt, height = 531), none, settledHeight = 500))
        // No height known (older records, or a scan without one): the wall clock.
        assertEquals(emptyList<String>(), LinkStatusRules.droppedSettles(txids, sentAt, scan(at = sentAt + hour, height = 0), none, settledHeight = 500))
        assertEquals(txids, LinkStatusRules.droppedSettles(txids, sentAt, scan(at = sentAt + hour + 1, height = 900), none, settledHeight = null))
        // The other conditions do not change: in the pool in the open wallet, it is never dropped.
        assertEquals(
            emptyList<String>(),
            LinkStatusRules.droppedSettles(txids, sentAt, scan(height = 900), WalletHistory(pending = setOf("ours")), settledHeight = 500)
        )
    }

    @Test
    fun `received claims count their grace in blocks too`() {
        val ours = ReceivedLink(
            id = "r", words = null, height = 100, amount = 0, expiry = 0, receivedAtMillis = 1, lastCheck = scan(), secret = secret
        ).claimSigned("ours", amount = 6, to = "Main", at = 10, height = 500)
        // Seen nowhere, money still there.
        assertEquals(ReceivedState.CLAIMED, ours.noting(scan(at = 10 * LinkStatusRules.SETTLE_GRACE_MS, height = 530)).state)
        assertEquals(ReceivedState.PENDING, ours.noting(scan(at = 11, height = 531)).state)
    }

    @Test
    fun `records saved before the new fields read as before`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = """{"id":"a","height":1,"amount":2,"expiry":3,"createdAtMillis":4,"walletId":"w","envelopeAddress":"4","feeReserve":5,"state":"FUNDED"}"""
        val link = json.decodeFromString(SentLink.serializer(), old)
        assertEquals(emptyList<String>(), link.attemptTxids)
        assertNull(link.reclaimSentHeight)
        val received = json.decodeFromString(ReceivedLink.serializer(), """{"id":"r","words":null,"height":1,"amount":0,"expiry":0,"receivedAtMillis":1}""")
        assertFalse(received.claimLostRace)
        assertNull(received.claimSentHeight)
    }
}
