package com.avinash.relaydisplay.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shared building blocks.
 *
 * Two constraints shape all of these: a minimum 56dp touch target because the controller's
 * screen is cracked and small targets near a crack are unhittable, and no critical control
 * pinned to a single edge.
 */
object RelayDimens {
    val MinTouchTarget = 56.dp
    val ScreenPadding = 20.dp
    val CardPadding = 20.dp
    val Gap = 16.dp
    val SmallGap = 8.dp
}

@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RelayDimens.MinTouchTarget)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(14.dp),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RelayDimens.MinTouchTarget),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun RelayCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    // A card that demands a decision -- comparing the six digit code -- must not look like the
    // five cards around it that are only reporting facts.
    container: Color? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    // Plain cards are outlined on the page background rather than tonally filled. The filled
    // treatment now belongs to exactly one thing -- ConnectionHero -- so the connection state is
    // the only tinted block on the screen instead of being one lavender card among five.
    val border = when {
        selected -> Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
        container == null -> Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
        else -> Modifier
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(border)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container ?: MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(RelayDimens.CardPadding), content = content)
    }
}

/** A labelled status line with a colour-coded dot, used on both dashboards. */
@Composable
fun StatusRow(
    label: String,
    value: String,
    indicatorColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (indicatorColor != null) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(indicatorColor, CircleShape),
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = RelayDimens.Gap, bottom = RelayDimens.SmallGap),
    )
}

@Composable
fun VerticalGap(height: androidx.compose.ui.unit.Dp = RelayDimens.Gap) {
    Spacer(Modifier.height(height))
}
