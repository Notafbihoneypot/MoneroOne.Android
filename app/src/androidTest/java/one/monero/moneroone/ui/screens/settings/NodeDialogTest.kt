package one.monero.moneroone.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.DefaultNodes
import one.monero.moneroone.ui.theme.MoneroOneTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodeDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun onionNodeReadsAsTorNeverAsUnencryptedHttp() {
        var torEnabled by mutableStateOf(false)
        rule.setContent {
            MoneroOneTheme {
                NodeDialog(tr("Add Custom Node"), tr("Add"), "", null, emptySet(), torEnabled, { _, _ -> }, {})
            }
        }
        val uriField = rule.onNode(hasSetTextAction())
        val unencrypted = tr("Connection will be unencrypted (HTTP)")

        uriField.performTextReplacement(DefaultNodes.TOR.last().uri)
        rule.onNodeWithText(tr("Requires Tor")).assertExists()
        rule.onNodeWithText(unencrypted).assertDoesNotExist()

        torEnabled = true
        rule.onNodeWithText(tr("Tor")).assertExists()
        rule.onNodeWithText(tr("Requires Tor")).assertDoesNotExist()
        rule.onNodeWithText(unencrypted).assertDoesNotExist()

        // A clearnet node on a plain port still reads as unencrypted.
        uriField.performTextReplacement("node.example.com:18081")
        rule.onNodeWithText(unencrypted).assertExists()
    }
}
