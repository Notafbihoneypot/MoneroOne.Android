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
import io.horizontalsystems.monerokit.model.TransactionInfo
import one.monero.moneroone.core.locale.pluralTr
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

    // iOS AddressPickerView: the main address in its own section with its
    // footer, then Subaddresses with New Address first and the newest next.
    @Test fun mainAddressHasItsOwnSectionAndRowsReadAsOnIos() {
        val payment = TransactionInfo(0, false, false, 1_500_000_000_000, 10, 100, "tx-3", 100, "", 0, 3, 5, 0, "", emptyList())
        val rows = ReceiveAddressLogic.rows(
            listOf(Subaddress(0, 0, "main-address", ""), Subaddress(0, 2, "sub-address", ""), Subaddress(0, 3, "gift-address", "")),
            listOf(payment), mapOf(3 to "🎁 Gifts")
        )
        rule.setContent { MoneroOneTheme { AddressPickerContent(rows, 3, true, false, {}, {}, { _, _ -> }, {}) } }
        fun top(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
        val order = listOf(tr("Main Address"), tr("Payments to your main address can be linked together."), tr("Subaddresses"),
            tr("New Address"), "🎁 Gifts", tr("Subaddress #%s", 2))
        assertEquals(order, order.sortedBy { top(it) })
        rule.onNodeWithText("#3").assertExists()
        rule.onNodeWithText(tr("Received")).assertExists()
        rule.onNodeWithText("1.5000 XMR").assertExists()
        // TalkBack reads the row as VoiceOver does, and only the shown one as selected.
        val spoken = listOf(tr("%s, subaddress %s", "🎁 Gifts", 3), tr("received %s XMR", "1.5000"), pluralTr("%s payments", 1)).joinToString(", ")
        rule.onNodeWithContentDescription(spoken)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, tr("Selected")))
        rule.onNodeWithText(tr("Subaddress #%s", 2)).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test fun anEmptyListExplainsSubaddressesAndCannotEdit() {
        val rows = ReceiveAddressLogic.rows(listOf(Subaddress(0, 0, "main-address", "")), emptyList(), emptyMap())
        rule.setContent { MoneroOneTheme { AddressPickerContent(rows, 0, true, false, {}, {}, { _, _ -> }, {}) } }
        rule.onNodeWithText(tr("Create subaddresses for better privacy when receiving payments.")).assertExists()
        rule.onNodeWithText(tr("Edit")).assertIsNotEnabled()
        rule.onNodeWithText(tr("New Address")).assertIsEnabled()
    }

    @Test fun searchAppearsAtEightSubaddressesAndFindsANumber() {
        val rows = ReceiveAddressLogic.rows((0..8).map { Subaddress(0, it, "address-$it", "") }, emptyList(), emptyMap())
        rule.setContent { MoneroOneTheme { AddressPickerContent(rows, 0, true, false, {}, {}, { _, _ -> }, {}) } }
        rule.onNode(hasSetTextAction()).performTextInput("#5")
        rule.onNodeWithText(tr("Subaddress #%s", 5)).assertExists()
        rule.onNodeWithText(tr("Subaddress #%s", 6)).assertDoesNotExist()
        rule.onNodeWithText(tr("Main Address")).assertDoesNotExist()
        rule.onNodeWithText(tr("New Address")).assertDoesNotExist()
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
