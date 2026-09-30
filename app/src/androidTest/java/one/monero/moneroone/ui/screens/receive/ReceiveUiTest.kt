package one.monero.moneroone.ui.screens.receive

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.monerokit.data.Subaddress
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.ReceiveAddressLogic
import one.monero.moneroone.ui.components.FocusableQrPlate
import one.monero.moneroone.ui.components.QrFocusContainer
import one.monero.moneroone.ui.components.QrFocusItem
import one.monero.moneroone.ui.theme.MoneroOneTheme
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiveUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun editRenamesInsteadOfSelectingAndDisablesCreation() {
        val rows = ReceiveAddressLogic.rows(listOf(Subaddress(0, 0, "main-address", ""), Subaddress(0, 2, "sub-address", "")), emptyList(), emptyMap())
        var selected: Int? = null
        var renamed: Pair<Int, String>? = null
        var creations = 0
        rule.setContent { MoneroOneTheme {
            AddressPickerContent(rows, 0, true, false, {}, { selected = it.index },
                { row, label -> renamed = row.index to label }, { creations++ })
        } }
        rule.onNodeWithText(tr("Subaddress #%s", 2)).performClick()
        assertEquals(2, selected)
        selected = null
        rule.onNodeWithText(tr("Edit")).performClick()
        rule.onNodeWithText(tr("New Address")).assertIsNotEnabled()
        rule.onNodeWithText(tr("Subaddress #%s", 2)).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("🎁 Gifts")
        rule.onNodeWithText(tr("Save")).performClick()
        assertEquals(2 to "🎁 Gifts", renamed)
        assertNull(selected)
        assertEquals(0, creations)
    }

    @Test fun creationStaysDisabledAtTheLimit() {
        val rows = ReceiveAddressLogic.rows(listOf(Subaddress(0, 190, "unused-address", "")), emptyList(), emptyMap())
        rule.setContent { MoneroOneTheme { AddressPickerContent(rows, 190, false, false, {}, {}, { _, _ -> }, {}) } }
        rule.onNodeWithText(tr("New Address")).assertIsNotEnabled()
        rule.onNodeWithText(ReceiveAddressLogic.creationWarning(rows)!!).assertExists()
    }

    @Test fun qrFocusHasOneAccessibleCodeAndReturnsToItsOriginalPlace() {
        val content = "monero:888tNkZrPN6JsEgekjMnABU4TBzc2Dt29EPAvkRxbANsAnjyPbb3iQ1YBRk1UXcdRsiKc9dhwMVgN5S9cQUiyoogDavup3H?tx_amount=0.5"
        rule.setContent { MoneroOneTheme {
            QrFocusContainer { focus ->
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Spacer(Modifier.height(80.dp))
                    FocusableQrPlate(QrFocusItem(content, "Subaddress #1", BigDecimal("0.5")), 180.dp, focus,
                        label = tr("QR code for receiving Monero"))
                }
            }
        } }
        val source = tr("QR code for receiving Monero")
        val original = rule.onNodeWithContentDescription(source).fetchSemanticsNode().boundsInRoot
        rule.onNodeWithContentDescription(source).performClick()
        rule.waitForIdle()
        // One code for TalkBack while focus mode is open: the large copy, with what it points at.
        rule.onAllNodesWithContentDescription(source).assertCountEquals(0)
        val large = rule.onNodeWithTag("qr-focus")
            .assertContentDescriptionEquals(tr("QR code for Monero address"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Subaddress #1, 0.5000 XMR"))
        assertTrue(large.fetchSemanticsNode().boundsInRoot.width > original.width)
        rule.onNodeWithText("0.5000 XMR").assertDoesNotExist()
        rule.onNodeWithTag("qr-focus").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("qr-focus").assertDoesNotExist()
        assertEquals(original, rule.onNodeWithContentDescription(source).fetchSemanticsNode().boundsInRoot)
    }
}
