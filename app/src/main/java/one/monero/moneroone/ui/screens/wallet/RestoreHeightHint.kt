package one.monero.moneroone.ui.screens.wallet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.ui.theme.WarningYellow
import one.monero.moneroone.ui.theme.formatHeight

/**
 * Shown after a seed restore that synced with no history (iOS
 * RestoreHeightHintBanner): the restore height was probably too recent. It
 * points at Settings › Sync Settings. A caution: yellow tint at 15%, text in
 * the label color. It moves like the offline banner above it.
 */
@Composable
internal fun ColumnScope.RestoreHeightHint(visible: Boolean, restoreHeight: Long, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(BannerMs)) { -it } + fadeIn(tween(BannerMs)) +
            expandVertically(tween(BannerMs), expandFrom = Alignment.Top),
        exit = slideOutVertically(tween(BannerMs)) { -it } + fadeOut(tween(BannerMs)) +
            shrinkVertically(tween(BannerMs), shrinkTowards = Alignment.Top)
    ) {
        val yellow = WarningYellow
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(yellow.copy(alpha = 0.15f))
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Default.History, contentDescription = null, tint = yellow, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    tr("No transactions found since block %s", formatHeight(restoreHeight)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    tr("If this wallet is older, lower the restore height in Settings › Sync Settings and reset the sync data."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // iOS: a Spacer(minLength: 8) between the text and the button.
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Cancel,
                contentDescription = tr("Dismiss"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onDismiss)
            )
        }
    }
}
