package one.monero.moneroone.core.locale

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceivePluralTest {
    @Test fun paymentCountsUseEachLanguagesPluralRules() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun count(language: String, n: Int): String {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val text = context.createConfigurationContext(config).resources.getQuantityString(localizedPlurals.getValue("%s payments"), n)
            return formatTranslation(text, arrayOf(n))
        }
        assertEquals("1 payment", count("en", 1))
        assertEquals("2 payments", count("en", 2))
        assertEquals("2件の支払い", count("ja", 2))
        assertEquals("2 платежа", count("ru", 2))
        assertEquals("5 платежей", count("ru", 5))
    }
}
