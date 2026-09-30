package one.monero.moneroone

import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import one.monero.moneroone.ui.navigation.MoneroOneNavHost
import one.monero.moneroone.ui.theme.MoneroOneTheme
import one.monero.moneroone.ui.theme.setSystemNightMode

class MainActivity : AppCompatActivity() {
    private val pendingPaymentLink = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptPaymentLink(intent)
    }

    private fun acceptPaymentLink(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme.equals("monero", ignoreCase = true)) {
            pendingPaymentLink.value = intent.dataString
            // Consume the Intent so recreation cannot reopen a completed request.
            intent.data = null
        }
    }


    override fun onSaveInstanceState(outState: Bundle) {
        pendingPaymentLink.value?.let { outState.putString("pending_payment_link", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPaymentLink.value = savedInstanceState?.getString("pending_payment_link")
        acceptPaymentLink(intent)
        // A language change recreates this activity: rename the channels to match.
        (application as MoneroOneApp).createNotificationChannels()

        // Screenshots stay blocked only on screens with a seed or a PIN: see SecureScreen.

        val prefs = getSharedPreferences("monero_wallet", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(themeMode)
        // AppCompat never tells the system, so the splash followed the phone.
        setSystemNightMode(this, themeMode)

        enableEdgeToEdge()

        setContent {
            val darkTheme = when (themeMode) {
                AppCompatDelegate.MODE_NIGHT_YES -> true
                AppCompatDelegate.MODE_NIGHT_NO -> false
                else -> isSystemInDarkTheme()
            }

            MoneroOneTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MoneroOneNavHost(
                        paymentLink = pendingPaymentLink.value,
                        onPaymentLinkConsumed = { pendingPaymentLink.value = null }
                    )
                }
            }
        }
    }
}
