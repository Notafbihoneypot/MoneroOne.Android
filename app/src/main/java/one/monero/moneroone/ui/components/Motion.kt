package one.monero.moneroone.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Motion tokens shared with iOS (tokens.json motion.springs):
 * `.snappy(duration: 0.3)` for the wallet-row reorder and ring,
 * `.snappy(duration: 0.35)` for slide-swaps such as the wallet switcher,
 * `easeInOut 0.2` for rolling digits.
 */
object Motion {
    /** iOS `.snappy(duration: 0.3)`: settles in roughly a third of a second with a soft overshoot. */
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 438.6f)

    /** iOS `.snappy(duration: 0.35)` (tokens.json slideSwap): same feel, a touch slower, for larger content swaps. */
    fun <T> snappySlow(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 322.3f)

    /** Rolling digits and other small value changes. */
    const val DIGIT_MS = 200

    /** iOS `.easeInOut`, the curve of rolling digits. */
    val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
}
