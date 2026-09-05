package com.avinash.relaydisplay.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The header on a role dashboard.
 *
 * Layout follows the platform convention rather than mirroring it: the title occupies the start,
 * and utility actions sit at the end. The previous build had the gear on the left, which is the
 * slot a navigation icon owns; moving it to the end means the same corner means the same thing
 * on every screen in the app.
 *
 * Settings used to be a full-width button at the bottom of a long scroll. It is pinned here now,
 * so it never moves and never needs scrolling to reach.
 */
@Composable
fun RelayTopBar(
    title: String,
    subtitle: String?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // Only the cutout. The Scaffold in RelayAppRoot already consumes the status bar, and
            // applying it again here produced a visible band of dead space above the title.
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(start = RelayDimens.ScreenPadding, end = 8.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .size(RelayDimens.MinTouchTarget)
                .testTag("open_settings")
                .semantics { contentDescription = "Settings" },
        ) {
            RelayGlyph(RelayIcon.SETTINGS, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The header on a screen you can back out of.
 *
 * Back takes the start slot, which is exactly why the dashboard's gear had to move to the end:
 * one corner, one meaning, across the whole app.
 */
@Composable
fun RelayDetailBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // See RelayTopBar: the Scaffold already handles the status bar.
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(RelayDimens.MinTouchTarget)
                .testTag("nav_back")
                .semantics { contentDescription = "Back" },
        ) {
            RelayGlyph(RelayIcon.BACK, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** A small circular status dot used in headers and cards. */
@Composable
fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 10.dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier
            .size(size)
            .background(color, CircleShape),
    )
}
