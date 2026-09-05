package com.avinash.relaydisplay.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import com.avinash.relaydisplay.network.session.ConnectionState

/**
 * The honest connection state, shown identically on both dashboards.
 *
 * Marked as a live region so a screen reader announces a state change without the user having
 * to hunt for it.
 */
@Composable
fun ConnectionStatusCard(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val headline = ConnectionStatusText.headline(state)
    val detail = ConnectionStatusText.detail(state)

    RelayCard(
        modifier = modifier
            .testTag("connection_status")
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = listOfNotNull(headline, detail).joinToString(". ")
            },
    ) {
        Row(Modifier.fillMaxWidth()) {
            StatusRow(
                label = "Status",
                value = headline,
                indicatorColor = ConnectionStatusText.indicatorColor(state),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Only show motion while something is genuinely in flight; a spinner on an idle screen
        // is both a lie and, on the older phone, a measurable battery cost.
        if (state.isActive && !state.isConnected) {
            VerticalGap(RelayDimens.SmallGap)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (trailing != null) {
            VerticalGap(RelayDimens.SmallGap)
            trailing()
        }
    }
}
