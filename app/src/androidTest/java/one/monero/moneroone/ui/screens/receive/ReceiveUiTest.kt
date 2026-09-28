package one.monero.moneroone.ui.screens.receive

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.monerokit.data.Subaddress
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.ReceiveAddressLogic
import one.monero.moneroone.ui.theme.MoneroOneTheme
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
            AddressPickerContent(rows, 0, false, true, false, {}, { selected = it.index },
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
        rule.setContent { MoneroOneTheme { AddressPickerContent(rows, 190, false, false, false, {}, {}, { _, _ -> }, {}) } }
        rule.onNodeWithText(tr("New Address")).assertIsNotEnabled()
        rule.onNodeWithText(ReceiveAddressLogic.creationWarning(rows)!!).assertExists()
    }

    @Test fun qrFocusHasOneAccessibleCodeAndReturnsToItsOriginalPlace() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        rule.setContent { MoneroOneTheme {
            QrFocusContainer(bitmap, "request") { qrModifier, expand ->
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Spacer(Modifier.height(80.dp))
                    Image(bitmap.asImageBitmap(), tr("QR Code"), Modifier.size(180.dp).then(qrModifier).clickable { expand() })
                }
            }
        } }
        val original = rule.onNodeWithContentDescription(tr("QR Code")).fetchSemanticsNode().boundsInRoot
        rule.onNodeWithContentDescription(tr("QR Code")).performClick()
        rule.onAllNodesWithContentDescription(tr("QR Code")).assertCountEquals(1)
        val expanded = rule.onNodeWithTag("qr-focus").fetchSemanticsNode().boundsInRoot
        assertTrue(expanded.width > original.width)
        rule.onNodeWithTag("qr-focus").performClick()
        rule.onNodeWithTag("qr-focus").assertDoesNotExist()
        assertEquals(original, rule.onNodeWithContentDescription(tr("QR Code")).fetchSemanticsNode().boundsInRoot)
    }
}
