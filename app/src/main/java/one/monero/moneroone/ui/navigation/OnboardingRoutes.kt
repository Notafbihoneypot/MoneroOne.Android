package one.monero.moneroone.ui.navigation

/**
 * The onboarding order, as on iOS: Create or Restore, then the PIN, then
 * Biometrics when the device has them, then Main. Adding a wallet later
 * returns to Main from Create or Restore and never reaches these steps.
 */
internal object OnboardingRoutes {

    /**
     * The NavHost start screen. A new start screen replaces the whole back
     * stack, and the PIN save makes [hasWallet] true on the way to Biometrics,
     * so the start screen changes to Main only when [onboarding] is over.
     */
    fun start(hasWallet: Boolean, onboarding: Boolean): String =
        if (hasWallet && !onboarding) Screen.Main.route else Screen.Welcome.route

    /**
     * The step after the PIN (iOS proceedAfterPIN): Biometrics when the device
     * can use them, else null, and onboarding ends at Main.
     */
    fun afterPin(biometricsAvailable: Boolean): String? =
        if (biometricsAvailable) Screen.SetupBiometrics.route else null
}
