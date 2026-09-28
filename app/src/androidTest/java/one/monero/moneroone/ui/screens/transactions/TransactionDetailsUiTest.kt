package one.monero.moneroone.ui.screens.transactions

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.monerokit.model.TransactionInfo
import io.horizontalsystems.monerokit.model.Transfer
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.ui.theme.MoneroOneTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionDetailsUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun displaysTheSameRecipientsAndFiatValuesThatCopyAllExports() {
        val tx = TransactionInfo(1, false, false, 1000000000000, 10000000, 3000000,
            "test-hash", 1790611200, "", 0, 0, 12, 0, "", listOf(Transfer(1000000000000, "test-recipient")))
        val fields = TransactionHistoryLogic.details(tx, emptyList(), "Sep 28, 2026", "$199.00", "$200.00", null)
        var copied: String? = null
        rule.setContent { MoneroOneTheme {
            TransactionDetailsContent(tx, fields, {}, {}, { copied = TransactionHistoryLogic.copyAll(fields) }, {}, false, false, {})
        } }
        rule.onNodeWithText("$199.00").assertExists()
        rule.onNodeWithText("$200.00").assertExists()
        rule.onNodeWithText("test-recipient").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(tr("Copy All Details")).performScrollTo().performClick()
        assertEquals(TransactionHistoryLogic.copyAll(fields), copied)
    }
}
