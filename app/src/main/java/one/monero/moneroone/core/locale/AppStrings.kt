package one.monero.moneroone.core.locale

import android.content.Context
import androidx.core.content.ContextCompat

object AppStrings {
    private var application: Context? = null

    fun initialize(context: Context) { application = context.applicationContext }

    fun text(source: String, vararg args: Any?): String {
        val context = application?.let(ContextCompat::getContextForLanguage)
        val translated = localizedStrings[source]?.let { context?.getString(it) } ?: source
        return formatTranslation(translated, args)
    }

    fun quantity(source: String, count: Int): String {
        val context = application?.let(ContextCompat::getContextForLanguage)
        val translated = localizedPlurals[source]?.let { context?.resources?.getQuantityString(it, count) }
            ?: if (count == 1) englishSingular[source] ?: source else source
        return formatTranslation(translated, arrayOf(count))
    }
}

/** Localize UI copy only. Call at display time, never when persisting user data. */
fun tr(source: String, vararg args: Any?): String = AppStrings.text(source, *args)
fun pluralTr(source: String, count: Int): String = AppStrings.quantity(source, count)

/** One substitution pass: values containing percent signs are never reinterpreted. */
internal fun formatTranslation(template: String, args: Array<out Any?>): String {
    var next = 0
    return Regex("%(?:(\\d+)\\$)?s|%%").replace(template) { match ->
        if (match.value == "%%") "%" else {
            val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: next++
            args.getOrNull(index)?.toString() ?: match.value
        }
    }
}

private val englishSingular = mapOf(
    "%s transactions" to "%s transaction",
    "%s pending transactions" to "%s pending transaction",
    "%s received transactions" to "%s received transaction",
    "%s sent transactions" to "%s sent transaction",
    "%s locations" to "%s location",
    "%s words" to "%s word",
    "%s payments" to "%s payment"
)
