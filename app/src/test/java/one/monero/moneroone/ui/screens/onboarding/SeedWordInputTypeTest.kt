package one.monero.moneroone.ui.screens.onboarding

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyboard input type of a seed word cell. */
class SeedWordInputTypeTest {
    private val textField = InputType.TYPE_CLASS_TEXT
    private val visiblePassword = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

    @Test
    fun `a Latin keyboard gets a visible password field`() {
        assertEquals(visiblePassword, seedWordInputType(textField, keepTextType = false))
    }

    @Test
    fun `the visible password type replaces the text field's other flags`() {
        val withCaps = textField or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        assertEquals(visiblePassword, seedWordInputType(withCaps, keepTextType = false))
    }

    @Test
    fun `a kept text type only adds no suggestions`() {
        assertEquals(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            seedWordInputType(textField, keepTextType = true)
        )
    }

    @Test
    fun `Latin and Cyrillic keyboards and languages hide the keyboard strip`() {
        assertFalse(keepsTextInputType(asciiCapable = true, languages = listOf("en-US", "en")))
        assertFalse(keepsTextInputType(asciiCapable = null, languages = emptyList()))
        assertFalse(keepsTextInputType(asciiCapable = null, languages = listOf("es", "fr", "ru")))
    }

    @Test
    fun `a keyboard that cannot type ASCII keeps the text type`() {
        assertTrue(keepsTextInputType(asciiCapable = false, languages = listOf("en")))
    }

    @Test
    fun `Japanese, Chinese and Korean keep the text type`() {
        assertTrue(keepsTextInputType(asciiCapable = true, languages = listOf("ja_JP")))
        assertTrue(keepsTextInputType(asciiCapable = null, languages = listOf("en", "zh-Hant-TW")))
        assertTrue(keepsTextInputType(asciiCapable = null, languages = listOf("en", "ko")))
    }
}
