package com.avinash.relaydisplay.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avinash.relaydisplay.network.session.ConnectionState

/**
 * The one thing a dashboard should say first: what is the connection doing.
 *
 * Previously this was a flat grey card identical to every other card on the page, with a 12dp
 * dot. It is now the visual anchor: the container colour itself carries the state, so the answer
 * is legible before any text is read. That matters more than usual here -- the controller phone
 * has a cracked panel, and colour survives a crack running through a line of text.
 *
 * Colour is used only for meaning, never decoration, and every state also carries text, so the
 * card does not depend on colour vision.
 */
@Composable
fun ConnectionHero(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    detail: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val tone = heroTone(state)
    val headline = ConnectionStatusText.headline(state)
    val body = detail ?: ConnectionStatusText.detail(state)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(tone.container)
            .padding(RelayDimens.CardPadding)
            .testTag("connection_status")
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = listOfNotNull(headline, body).joinToString(". ")
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(tone.accent.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                StatusDot(color = tone.accent, size = 16.dp)
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    text = "Status".uppercase(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tone.onContainer.copy(alpha = 0.65f),
                )
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleLarge,
                    color = tone.onContainer,
                )
            }
        }

        if (body != null) {
            VerticalGap(RelayDimens.SmallGap)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = tone.onContainer.copy(alpha = 0.8f),
            )
        }

        // Motion only while something is genuinely in flight. A spinner on an idle screen is both
        // a lie and, on the older phone, measurable battery.
        if (state.isActive && !state.isConnected) {
            VerticalGap(RelayDimens.SmallGap)
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp)),
            )
        }

        if (action != null) {
            VerticalGap(RelayDimens.Gap)
            action()
        }
    }
}

private data class HeroTone(val container: Color, val onContainer: Color, val accent: Color)

@Composable
private fun heroTone(state: ConnectionState): HeroTone {
    val scheme = MaterialTheme.colorScheme
    return when {
        state.isConnected -> HeroTone(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
        state is ConnectionState.Failed -> HeroTone(scheme.errorContainer, scheme.onErrorContainer, scheme.error)
        state is ConnectionState.Paused -> HeroTone(scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.outline)
        state.isActive -> HeroTone(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary)
        else -> HeroTone(scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.outline)
    }
}

/**
 * One action in a grid of them.
 *
 * The send screen used to be a column of identical full-width buttons; at a glance they were
 * indistinguishable and the page was mostly scrolling. As tiles the choices are scannable by
 * shape, and six of them fit where two buttons used to.
 *
 * Sizing is set by the harder device: 88dp tall with a 30dp glyph stays comfortably tappable on
 * the Lenovo's small screen and on a cracked one.
 */
@Composable
fun ActionTile(
    icon: RelayIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (enabled) scheme.secondaryContainer else scheme.surface
    val content = if (enabled) scheme.onSecondaryContainer else scheme.onSurfaceVariant.copy(alpha = 0.5f)

    Column(
        modifier = modifier
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            // A disabled tile still needs an edge. Without one it dissolves into whatever is
            // behind it and the grid stops reading as a set of choices at all.
            .then(
                if (enabled) {
                    Modifier
                } else {
                    Modifier.border(1.dp, scheme.outlineVariant, RoundedCornerShape(18.dp))
                },
            )
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics { contentDescription = label }
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RelayGlyph(icon, tint = content, size = 30.dp)
        VerticalGap(8.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * A titled group of content.
 *
 * Replaces the loose SectionHeader-then-card pattern: the label and its card are one unit, so
 * the vertical rhythm cannot drift as sections are added.
 */
@Composable
fun RelaySection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = RelayDimens.SmallGap),
        )
        RelayCard(content = content)
    }
}
