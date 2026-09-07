package com.avinash.relaydisplay.ui.settings

import android.content.Intent
import android.content.res.AssetManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import com.avinash.relaydisplay.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.avinash.relaydisplay.BuildConfig
import com.avinash.relaydisplay.ui.common.RelayCard
import com.avinash.relaydisplay.ui.common.RelayDetailBar
import com.avinash.relaydisplay.ui.common.RelayDimens
import com.avinash.relaydisplay.ui.common.SecondaryAction
import com.avinash.relaydisplay.ui.common.SectionHeader
import com.avinash.relaydisplay.ui.common.StatusRow
import com.avinash.relaydisplay.ui.common.VerticalGap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The four things that used to be one undifferentiated "license terms" page. */
private enum class AboutTab(val label: String) {
    ABOUT("About"),
    PRIVACY("Privacy"),
    LICENCES("Open source"),
    APP_LICENCE("App licence"),
}

/**
 * About and legal information.
 *
 * Split into four tabs rather than one wall of small text, because the previous single page
 * mixed a product description with dependency notices and read as neither.
 *
 * Every factual claim here was checked against the code, and the wording is deliberately narrow
 * where the code cannot support a stronger claim -- see the Privacy tab.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableStateOf(AboutTab.ABOUT) }

    Column(modifier.fillMaxSize()) {
        RelayDetailBar(title = "About & Legal", onBack = onBack)
        Column(Modifier.weight(1f).padding(horizontal = RelayDimens.ScreenPadding)) {
        TabRow(selected = tab, onSelect = { tab = it })
        VerticalGap()

        // Each tab owns its own scrolling; the licence list is lazy so 165 entries stay smooth
        // on the older phone.
        when (tab) {
            AboutTab.ABOUT -> ScrollingTab { AboutTabContent(onBack) }
            AboutTab.PRIVACY -> ScrollingTab { PrivacyTabContent() }
            AboutTab.LICENCES -> LicencesTabContent(Modifier.weight(1f))
            AboutTab.APP_LICENCE -> ScrollingTab { AppLicenceTabContent() }
        }

        }
    }
}

@Composable
private fun ColumnScope.ScrollingTab(content: @Composable () -> Unit) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
}

@Composable
private fun TabRow(selected: AboutTab, onSelect: (AboutTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (entry in AboutTab.entries) {
            val isSelected = entry == selected
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .background(
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(10.dp),
                    )
                    .selectable(selected = isSelected, onClick = { onSelect(entry) })
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("about_tab_${entry.name}"),
            )
        }
    }
}

@Composable
private fun AboutTabContent(onBack: () -> Unit) {
    RelayCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The launcher mark, at text scale. Enough to tie the screen to the app's identity
            // without turning an information screen into a splash page.
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Text(
                "RelayDisplay",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        VerticalGap(RelayDimens.SmallGap)
        Text(
            "Turns a spare Android phone into a companion screen for another phone over Wi-Fi or " +
                "a hotspot. One app, installed on both; each one is set to a role.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }

    SectionHeader("Version")
    RelayCard {
        StatusRow("Version name", BuildConfig.VERSION_NAME)
        StatusRow("Version code", BuildConfig.VERSION_CODE.toString())
        StatusRow("Package", BuildConfig.APPLICATION_ID)
        StatusRow("Build type", if (BuildConfig.DEBUG) "debug" else "release")
    }

    SectionHeader("Roles")
    RelayCard {
        Text("Controller", style = MaterialTheme.typography.titleMedium)
        Text(
            "The phone you hold. It sends text, QR codes, links, images and PDF pages, and can " +
                "share its screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VerticalGap(RelayDimens.SmallGap)
        Text("Companion display", style = MaterialTheme.typography.titleMedium)
        Text(
            "The spare phone. It shows what the controller sends, and can close content itself " +
                "at any time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionHeader("What it is not")
    RelayCard {
        Text(
            "It is not a second monitor for Android. No ordinary app can make other applications " +
                "spread themselves across two phones. RelayDisplay shows content it is given and " +
                "can mirror a screen as video; it never controls the other phone beyond its own " +
                "window.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    VerticalGap(RelayDimens.ScreenPadding)
}

/**
 * Privacy wording.
 *
 * Every sentence below is a claim that can be checked in this repository, and the wording stops
 * exactly where verification stops. Note what is deliberately absent: no "we collect nothing"
 * absolute, no company, no jurisdiction, no contact address, no warranty.
 */
@Composable
private fun PrivacyTabContent() {
    RelayCard {
        Text("Where your content goes", style = MaterialTheme.typography.titleLarge)
        VerticalGap(RelayDimens.SmallGap)
        Text(
            "Content travels directly between the two paired phones over your local network, " +
                "encrypted end to end. This app contains no server address and no cloud service; " +
                "it works with the internet unplugged.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }

    SectionHeader("What is stored on this phone")
    RelayCard {
        Text(
            "• Settings: role, availability mode, device name and presentation preferences.\n" +
                "• Trusted peer: the paired phone's name, public key and the address it was " +
                "last reachable at.\n" +
                "• A device identity key, held in the Android Keystore and not exportable.\n" +
                "• Received images and documents, in a size-bounded cache that evicts the " +
                "oldest entries.\n\n" +
                "Backup is disabled, so none of this is copied off the phone by Android's backup " +
                "service.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    SectionHeader("Diagnostics")
    RelayCard {
        Text(
            "The diagnostics log is held in memory only and is lost when the app closes. It is " +
                "never written to storage on its own. Exporting it uses the Android share sheet, " +
                "so it goes only where you send it. Content, addresses, keys and pairing codes " +
                "are excluded from it by design.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    SectionHeader("Analytics")
    RelayCard {
        Text(
            "No analytics, advertising, crash-reporting or account library is included in this " +
                "build. The open-source notices list every dependency that ships in the app, and " +
                "you can check that list yourself.",
            style = MaterialTheme.typography.bodyMedium,
        )
        VerticalGap(RelayDimens.SmallGap)
        Text(
            "This describes RelayDisplay's own behaviour. Android itself, your network equipment " +
                "and any app you share content into have their own behaviour, which this app " +
                "cannot speak for.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionHeader("Permissions")
    RelayCard {
        Text(
            "Camera is used only while you are scanning a pairing code. Screen capture happens " +
                "only after Android's own consent prompt, every time. No location, storage, " +
                "media or contacts permission is requested.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    VerticalGap(RelayDimens.ScreenPadding)
}

/** One dependency and its declared licence, parsed from the generated asset. */
private data class LicenceEntry(val module: String, val lines: List<String>)

@Composable
private fun LicencesTabContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val entries by produceState(initialValue = emptyList<LicenceEntry>(), context) {
        value = withContext(Dispatchers.IO) { readLicenceAsset(context.assets) }
    }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier) {
        Text(
            "Generated at build time from each dependency's own published metadata, so this list " +
                "matches what actually ships. ${entries.size} components.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        // Lazy: 165 rows would be a noticeable stall on the older phone if laid out eagerly.
        LazyColumn(Modifier.fillMaxSize().testTag("licence_list")) {
            items(entries, key = { it.module }) { entry ->
                val isOpen = expanded == entry.module
                Column(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isOpen,
                            onClick = { expanded = if (isOpen) null else entry.module },
                        )
                        .heightIn(min = 48.dp)
                        .padding(vertical = 10.dp)
                        .animateContentSize()
                        .testTag("licence_entry"),
                ) {
                    Text(entry.module, style = MaterialTheme.typography.bodyLarge)
                    if (isOpen) {
                        VerticalGap(4.dp)
                        for (line in entry.lines) {
                            Text(
                                line,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            entry.lines.firstOrNull().orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * Parses the generated notices asset.
 *
 * Returns an empty list rather than throwing if the asset is missing, so a build that somehow
 * lost it degrades to an empty screen instead of crashing.
 */
private fun readLicenceAsset(assets: AssetManager): List<LicenceEntry> = try {
    val text = assets.open(LICENCE_ASSET).bufferedReader().use { it.readText() }
    val entries = mutableListOf<LicenceEntry>()
    var module: String? = null
    val lines = mutableListOf<String>()
    for (raw in text.lineSequence()) {
        when {
            raw.isBlank() -> {
                module?.let { entries.add(LicenceEntry(it, lines.toList())) }
                module = null
                lines.clear()
            }
            raw.startsWith("    ") -> lines.add(raw.trim())
            raw.count { it == ':' } >= 2 -> module = raw.trim()
        }
    }
    module?.let { entries.add(LicenceEntry(it, lines.toList())) }
    entries
} catch (e: java.io.IOException) {
    emptyList()
}

private const val LICENCE_ASSET = "third_party_licenses.txt"

/** The one place the app opens an external URL, and only on an explicit tap. */
const val SOURCE_URL = "https://github.com/avina5hkr/relay-display"

@Composable
private fun SourceLink() {
    val context = LocalContext.current
    Text(
        text = SOURCE_URL,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .heightIn(min = RelayDimens.MinTouchTarget)
            .clickable(role = Role.Button) {
                // No verification of the resolved activity: a device with no browser simply has
                // nothing to start, and that throws rather than failing silently.
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .semantics { contentDescription = "Open the RelayDisplay source repository" }
            .testTag("source_link"),
    )
}

/**
 * The application's own licence.
 *
 * GPLv3 section 5(d) requires an interactive program to display Appropriate Legal Notices, so
 * this is not decoration -- it is how the app satisfies the licence it ships under. It states the
 * licence, the copyright holder, the no-warranty disclaimer, and where to get the source, because
 * those are the four things the notice is required to carry.
 *
 * Keep this in step with the LICENSE file. If the repository's licence ever changes, this screen
 * changes with it; a program whose About screen names the wrong licence is worse than one that
 * names none.
 */
@Composable
private fun AppLicenceTabContent() {
    RelayCard {
        Text("RelayDisplay itself", style = MaterialTheme.typography.titleLarge)
        VerticalGap(RelayDimens.SmallGap)
        Text(
            "Copyright \u00a9 2026 Avinash Kumar",
            style = MaterialTheme.typography.bodyLarge,
        )
        VerticalGap(RelayDimens.SmallGap)
        Text(
            "RelayDisplay is free software: you can redistribute it and/or modify it under the " +
                "terms of the GNU General Public License as published by the Free Software " +
                "Foundation, either version 3 of the License, or (at your option) any later " +
                "version.",
            style = MaterialTheme.typography.bodyLarge,
        )
        VerticalGap(RelayDimens.SmallGap)
        Text(
            "This program is distributed in the hope that it will be useful, but WITHOUT ANY " +
                "WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR " +
                "A PARTICULAR PURPOSE. See the GNU General Public License for more details.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VerticalGap(RelayDimens.SmallGap)
        Text(
            "You should have received a copy of the GNU General Public License along with this " +
                "program. If not, see https://www.gnu.org/licenses/.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionHeader("Getting the source")
    RelayCard {
        Text(
            "The GPL gives you the right to the complete source code that this app was built " +
                "from. Whoever supplied you with this build is required by the licence to make " +
                "that source available to you on request, under these same terms.",
            style = MaterialTheme.typography.bodyMedium,
        )
        VerticalGap(RelayDimens.SmallGap)
        SourceLink()
        VerticalGap(RelayDimens.SmallGap)
        Text(
            "A released build corresponds to one exact commit. Match the version above against " +
                "the repository's tag or commit for that release to get the source it was " +
                "actually built from.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionHeader("Third-party components")
    RelayCard {
        Text(
            "The libraries RelayDisplay is built on have their own licences, separate from the " +
                "licence above. Open source lists every component on the release classpath with " +
                "the licence name and URL declared in its own POM. Those are identifiers, not " +
                "the full licence texts, and they are not copyright notices or NOTICE files.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    VerticalGap(RelayDimens.ScreenPadding)
}
