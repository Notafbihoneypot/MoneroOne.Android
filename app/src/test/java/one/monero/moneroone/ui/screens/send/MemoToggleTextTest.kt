package one.monero.moneroone.ui.screens.send

import one.monero.moneroone.core.locale.localizedStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The memo toggle on the amount step reads as on iOS. */
class MemoToggleTextTest {

    @Test
    fun aClosedMemoFieldReadsAddMemo() {
        assertEquals("Add memo", memoToggleText(showMemo = false))
    }

    @Test
    fun anOpenMemoFieldReadsHideMemo() {
        assertEquals("Hide memo", memoToggleText(showMemo = true))
    }

    @Test
    fun bothTextsHaveTheIosTranslations() {
        assertTrue(listOf("Add memo", "Hide memo").all { it in localizedStrings })
    }
}
