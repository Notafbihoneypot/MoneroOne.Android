package one.monero.moneroone.ui.components

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.monero.moneroone.BuildConfig
import one.monero.moneroone.core.wallet.WalletViewModel
import one.monero.moneroone.ui.screens.settings.BackupSeedScreen
import one.monero.moneroone.ui.screens.wallet.WalletScreen
import one.monero.moneroone.ui.theme.MoneroOneTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Debug test builds switch the release rule on here: debug builds themselves never block capture. */
@RunWith(AndroidJUnit4::class)
class SecureScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun restoreBuildRule() { SecureScreens.enforced = !BuildConfig.DEBUG }

    private fun captureBlocked(): Boolean {
        var flags = 0
        rule.runOnUiThread { flags = rule.activity.window.attributes.flags }
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    private fun walletViewModel(): WalletViewModel {
        lateinit var model: WalletViewModel
        rule.runOnUiThread { model = WalletViewModel(ApplicationProvider.getApplicationContext<Application>()) }
        return model
    }

    @Test fun releaseBlocksCaptureOnTheSeedScreenAndAllowsItOnWalletHome() {
        SecureScreens.enforced = true
        val wallet = walletViewModel()
        var seedScreen by mutableStateOf(true)
        rule.setContent {
            MoneroOneTheme {
                if (seedScreen) BackupSeedScreen(wallet, onBack = {})
                else WalletScreen(wallet, onSendClick = {}, onReceiveClick = {}, onTransactionClick = {}, onSeeAllTransactionsClick = {})
            }
        }
        rule.waitForIdle()
        assertTrue("Seed screen", captureBlocked())
        seedScreen = false
        rule.waitForIdle()
        assertFalse("Wallet home", captureBlocked())
    }

    @Test fun releaseGivesThePinDialogASecureWindow() {
        SecureScreens.enforced = true
        val wallet = walletViewModel()
        rule.setContent { MoneroOneTheme { AuthGateDialog(wallet, "Confirm", "Enter your PIN", onAuthenticated = {}, onCancel = {}) } }
        rule.waitForIdle()
        var flags = 0
        onView(isRoot()).inRoot(isDialog()).check { view, _ ->
            flags = (view.rootView.layoutParams as WindowManager.LayoutParams).flags
        }
        assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun overlappingScreensKeepTheFlagUntilTheLastOneLeaves() {
        SecureScreens.enforced = true
        var screens by mutableIntStateOf(2)
        rule.setContent { repeat(screens) { SecureScreen() } }
        rule.waitForIdle()
        assertTrue(captureBlocked())
        screens = 1
        rule.waitForIdle()
        assertTrue(captureBlocked())
        screens = 0
        rule.waitForIdle()
        assertFalse(captureBlocked())
    }

    @Test fun debugBuildsStayCapturable() {
        SecureScreens.enforced = false
        rule.setContent { SecureScreen() }
        rule.waitForIdle()
        assertFalse(captureBlocked())
    }
}
