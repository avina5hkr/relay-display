package com.avinash.relaydisplay.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.avinash.relaydisplay.R
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.ui.common.PrimaryAction
import com.avinash.relaydisplay.ui.common.RelayCard
import com.avinash.relaydisplay.ui.common.RelayDimens
import com.avinash.relaydisplay.ui.common.VerticalGap
import com.avinash.relaydisplay.ui.theme.RelayDisplayTheme

/**
 * First-launch role selection.
 *
 * Shown only once the settings store has actually loaded, so a device that already has a role
 * never flashes this screen on the way to its dashboard.
 */
@Composable
fun RoleChooserScreen(
    onRoleChosen: (DeviceRole) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableStateOf<DeviceRole?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(RelayDimens.ScreenPadding),
    ) {
        Text(
            stringResource(R.string.role_chooser_title),
            style = MaterialTheme.typography.displaySmall,
        )
        VerticalGap(RelayDimens.SmallGap)
        Text(
            stringResource(R.string.role_chooser_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VerticalGap()

        RoleOption(
            title = stringResource(R.string.role_controller_title),
            body = stringResource(R.string.role_controller_body),
            selected = selected == DeviceRole.CONTROLLER,
            onClick = { selected = DeviceRole.CONTROLLER },
        )
        VerticalGap()
        RoleOption(
            title = stringResource(R.string.role_display_title),
            body = stringResource(R.string.role_display_body),
            selected = selected == DeviceRole.DISPLAY,
            onClick = { selected = DeviceRole.DISPLAY },
        )
        VerticalGap()

        PrimaryAction(
            text = stringResource(R.string.role_confirm),
            enabled = selected != null,
            onClick = { selected?.let(onRoleChosen) },
            modifier = Modifier.testTag("role_confirm"),
        )
    }
}

@Composable
private fun RoleOption(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    RelayCard(onClick = onClick, selected = selected, modifier = Modifier.testTag("role_option_$title")) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        VerticalGap(RelayDimens.SmallGap)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun RoleChooserPreview() {
    RelayDisplayTheme {
        RoleChooserScreen(onRoleChosen = {})
    }
}
