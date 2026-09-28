package one.monero.moneroone.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import one.monero.moneroone.R

/**
 * The glass Monero logo (drawable-nodpi/monero_logo by day,
 * drawable-night-nodpi by night), clipped to a circle. Every logo in the app
 * uses it.
 *
 * The art is the circle iOS shows: the iOS hero art scaled 1.15 and clipped
 * to a circle, so the coin keeps its rim, bevel and outline on a thin ring of
 * plate (white by day, black by night). The art is already cropped, so both
 * modes draw it at scale 1.
 */
@Composable
fun MoneroLogo(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
    ) {
        Image(
            painter = painterResource(id = R.drawable.monero_logo),
            contentDescription = "Monero",
            contentScale = ContentScale.Fit,
            modifier = Modifier.matchParentSize()
        )
    }
}

/**
 * Glossy hero art from the iOS app (light and night variants, converted to
 * sRGB). Not in use: Welcome and Add Wallet show the glass [MoneroLogo]. Like
 * iOS, the art is scaled 1.15 and clipped to a circle so only the coin shows,
 * not its plate. The art is 1024px in drawable-nodpi, as on iOS, so a 240dp
 * hero stays sharp and decodes at its own size on every screen density.
 */
@Composable
fun MoneroHeroLogo(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
    ) {
        Image(
            painter = painterResource(id = R.drawable.monero_hero),
            contentDescription = "Monero",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .matchParentSize()
                .scale(1.15f)
        )
    }
}
