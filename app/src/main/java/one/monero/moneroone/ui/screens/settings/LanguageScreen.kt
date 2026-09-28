package one.monero.moneroone.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.monero.moneroone.core.locale.tr

val appLanguages = linkedMapOf(
    "en" to "English", "de" to "Deutsch", "es" to "Español", "fr" to "Français",
    "it" to "Italiano", "nl" to "Nederlands", "pl" to "Polski", "pt-BR" to "Português (Brasil)",
    "ro" to "Română", "tr" to "Türkçe", "ru" to "Русский", "uk" to "Українська",
    "zh-Hans" to "简体中文", "zh-Hant" to "繁體中文", "ja" to "日本語", "ko" to "한국어"
)

fun selectedLanguageName(): String {
    val locale = AppCompatDelegate.getApplicationLocales().get(0) ?: return tr("System")
    return appLanguages[locale.toLanguageTag()]
        ?: appLanguages.entries.firstOrNull { it.key.substringBefore('-') == locale.language }?.value
        ?: locale.getDisplayName(locale)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val selectedCode = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag().orEmpty()
    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Language")) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(listOf("" to tr("System")) + appLanguages.toList()) { (code, name) ->
                val isSelected = selectedCode == code
                Row(
                    Modifier.fillMaxWidth().semantics { selected = isSelected }
                        .clickable {
                            if (!isSelected) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
                        }.padding(horizontal = 24.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name, Modifier.weight(1f))
                    if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
