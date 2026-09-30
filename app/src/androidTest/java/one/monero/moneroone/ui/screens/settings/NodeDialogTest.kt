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
        val draft = NodeDraft("", null)
        rule.setContent {
            MoneroOneTheme {
                NodeDialog(tr("Add Node"), tr("Add"), draft, emptySet(), torEnabled, { _, _ -> }, {})
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

    // A theme change or a rotation recreates the Activity. The draft lives in a
    // ViewModel then, so a new composition must show what the user typed.
    @Test fun whatWasTypedOutlivesARecreatedScreen() {
        val draft = NodeDraft("", null)
        var shown by mutableStateOf(true)
        rule.setContent {
            MoneroOneTheme {
                if (shown) NodeDialog(tr("Add Node"), tr("Add"), draft, emptySet(), false, { _, _ -> }, {})
            }
        }
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("node.example.com:18081")
        rule.onNodeWithContentDescription(tr("Show authentication")).performClick()
        rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("alice")
        rule.onAllNodes(hasSetTextAction())[2].performTextReplacement("secret")

        shown = false
        rule.waitForIdle()
        rule.onNodeWithText(tr("Add Node")).assertDoesNotExist()
        shown = true

        rule.onNodeWithText(tr("Add Node")).assertExists()
        rule.onNodeWithText("node.example.com:18081").assertExists()
        rule.onNodeWithText("alice").assertExists()
        rule.onNodeWithContentDescription(tr("Show password")).performClick()
        rule.onNodeWithText("secret").assertExists()
    }
}
