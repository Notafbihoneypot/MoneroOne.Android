package one.monero.moneroone.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingRoutesTest {

    @Test
    fun noWalletStartsAtWelcome() {
        assertEquals(Screen.Welcome.route, OnboardingRoutes.start(hasWallet = false, onboarding = true))
        assertEquals(Screen.Welcome.route, OnboardingRoutes.start(hasWallet = false, onboarding = false))
    }

    @Test
    fun aWalletSetUpBeforeStartsAtMain() {
        assertEquals(Screen.Main.route, OnboardingRoutes.start(hasWallet = true, onboarding = false))
    }

    @Test
    fun thePinSaveKeepsTheStartScreenUntilOnboardingEnds() {
        // The PIN save makes hasWallet true; a switch to Main here would
        // replace the back stack and close Biometrics at once.
        assertEquals(Screen.Welcome.route, OnboardingRoutes.start(hasWallet = true, onboarding = true))
    }

    @Test
    fun biometricsFollowThePinWhenTheDeviceHasThem() {
        assertEquals(Screen.SetupBiometrics.route, OnboardingRoutes.afterPin(biometricsAvailable = true))
    }

    @Test
    fun withoutBiometricsOnboardingEndsAfterThePin() {
        assertNull(OnboardingRoutes.afterPin(biometricsAvailable = false))
    }

    @Test
    fun onboardingGoesPinThenBiometricsThenMain() {
        // Create or Restore: no wallet yet.
        assertEquals(Screen.Welcome.route, OnboardingRoutes.start(hasWallet = false, onboarding = true))
        // PIN saved: hasWallet turns true, the start screen holds, and Biometrics comes next.
        assertEquals(Screen.Welcome.route, OnboardingRoutes.start(hasWallet = true, onboarding = true))
        assertEquals(Screen.SetupBiometrics.route, OnboardingRoutes.afterPin(biometricsAvailable = true))
        // Biometrics done: the start screen changes to Main.
        assertEquals(Screen.Main.route, OnboardingRoutes.start(hasWallet = true, onboarding = false))
    }
}
