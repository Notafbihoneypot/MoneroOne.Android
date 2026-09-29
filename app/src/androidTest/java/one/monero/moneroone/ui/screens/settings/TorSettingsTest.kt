package one.monero.moneroone.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.network.TorConfig
import one.monero.moneroone.ui.theme.MoneroOneTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TorSettingsTest {
    @get:Rule val rule = createComposeRule()

    private fun torToggle() = rule.onNode(isToggleable())
    // A finger lands on the switch at the end of the row.
    private fun tapSwitch() = torToggle().performTouchInput { click(centerRight - Offset(12f, 0f)) }
    // TalkBack's double tap runs the row's click action.
    private fun talkBackActivate() = torToggle().performSemanticsAction(SemanticsActions.OnClick)

    @Test fun talkBackReadsTheToggleAsOneLabelledSwitch() {
        rule.setContent { MoneroOneTheme { Column { TorProxySection(TorConfig()) {} } } }
        rule.onAllNodes(isToggleable()).assertCountEquals(1)
        torToggle()
            .assert(hasText(tr("Use Tor Proxy")))
            .assert(hasText(tr("Route traffic through SOCKS5 proxy")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }
    @Test fun enablingExplainsExternalTorAndCancelKeepsItOff() {
        var changed: TorConfig? = null
        rule.setContent { MoneroOneTheme { Column { TorProxySection(TorConfig()) { changed = it } } } }
        tapSwitch()
        rule.onNodeWithText(tr("Tor Proxy Required")).assertExists()
        rule.onNodeWithText(tr("Cancel")).performClick()
        assertNull(changed)
        talkBackActivate()
        rule.onNodeWithText(tr("Turn On")).performClick()
        assertEquals(TorConfig(true), changed)
    }
    @Test fun proxyEditorRejectsInvalidAddressAndSavesValidOne() {
        var changed: TorConfig? = null
        rule.setContent { MoneroOneTheme { Column { TorProxySection(TorConfig(true)) { changed = it } } } }
        rule.onNodeWithContentDescription(tr("Edit Proxy")).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("http://wrong:9050")
        rule.onNodeWithText(tr("Save")).assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextReplacement("127.0.0.1:9150")
        rule.onNodeWithText(tr("Save")).performClick()
        assertEquals(TorConfig(true, "127.0.0.1:9150"), changed)
    }
    @Test fun turningOffNeedsNoConfirmation() {
        var changed: TorConfig? = null
        rule.setContent { MoneroOneTheme { Column { TorProxySection(TorConfig(true, "127.0.0.1:9150")) { changed = it } } } }
        tapSwitch()
        rule.onNodeWithText(tr("Tor Proxy Required")).assertDoesNotExist()
        assertEquals(TorConfig(false, "127.0.0.1:9150"), changed)
    }
}
