package one.monero.moneroone.ui.screens.settings

import io.horizontalsystems.monerokit.MoneroKit
import io.horizontalsystems.monerokit.SyncState
import one.monero.moneroone.data.model.AlertCondition
import one.monero.moneroone.data.model.PriceAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The values the Settings rows show, worded as on iOS SettingsView. */
class SettingsValuesTest {

    @Test
    fun `the sync row shows the status, or the percent while it syncs`() {
        assertEquals("Synced", syncStatusText(SyncState.Synced))
        assertEquals("42%", syncStatusText(SyncState.Syncing(progress = 0.427)))
        assertEquals("0%", syncStatusText(SyncState.Syncing(progress = null)))
        assertEquals("Connecting", syncStatusText(SyncState.Connecting(waiting = true)))
        assertEquals("Connecting", syncStatusText(SyncState.Connecting(waiting = false)))
        assertEquals("Idle", syncStatusText(SyncState.NotSynced(MoneroKit.SyncError.NotStarted)))
        assertEquals("Error", syncStatusText(SyncState.NotSynced(MoneroKit.SyncError.InvalidNode("Invalid node"))))
    }

    @Test
    fun `the price alerts row counts the active alerts once any alert exists`() {
        fun alert(id: String, enabled: Boolean) =
            PriceAlert(id, AlertCondition.ABOVE, targetPrice = 200.0, currencyCode = "usd", isEnabled = enabled)
        assertNull(priceAlertsValue(emptyList()))
        assertEquals("0", priceAlertsValue(listOf(alert("a", enabled = false))))
        assertEquals("2", priceAlertsValue(listOf(alert("a", true), alert("b", false), alert("c", true))))
    }

    @Test
    fun `the reset sync message names the wallet and tells what Android does with the keys`() {
        assertEquals(
            "“Savings” scans again from its restore height. Transaction keys for its past sends are no longer available.",
            resetSyncMessage("Savings")
        )
        assertEquals(
            "This wallet scans again from its restore height. Transaction keys for its past sends are no longer available.",
            resetSyncMessage(null)
        )
    }
}
