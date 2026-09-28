package one.monero.moneroone.ui.screens.send

import org.junit.Assert.assertEquals
import org.junit.Test

/** The review name: only the part before "@" is shortened; "@domain" always stays whole. */
class OpenAliasReviewNameTest {

    /** A line that holds [count] characters. */
    private fun line(count: Int): (String) -> Boolean = { it.length <= count }

    @Test
    fun aNameThatFitsStaysWhole() {
        assertEquals("donate@getmonero.org", shortenReviewName("donate@getmonero.org", line(20)))
    }

    @Test
    fun onlyThePartBeforeTheAtIsShortened() {
        assertEquals("dona…@getmonero.org", shortenReviewName("donate@getmonero.org", line(19)))
        assertEquals("do…@getmonero.org", shortenReviewName("donate@getmonero.org", line(17)))
    }

    @Test
    fun theDomainStaysWholeWhenTheLineIsTooShort() {
        // One letter stays; the text then wraps on screen.
        assertEquals("d…@getmonero.org", shortenReviewName("donate@getmonero.org", line(10)))
    }

    @Test
    fun aNameWithoutAtIsAllDomain() {
        assertEquals("donate.getmonero.org", shortenReviewName("donate.getmonero.org", line(10)))
    }
}
