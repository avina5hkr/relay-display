package com.avinash.relaydisplay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avinash.relaydisplay.content.IncomingBatch
import com.avinash.relaydisplay.content.IncomingFile
import com.avinash.relaydisplay.content.ReceivedFile
import com.avinash.relaydisplay.ui.display.IncomingBatchDialog
import com.avinash.relaydisplay.ui.display.ReceivedFilesSection
import com.avinash.relaydisplay.ui.settings.AboutScreen
import com.avinash.relaydisplay.ui.theme.RelayDisplayTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The file-transfer surfaces, on a real device.
 *
 * The About screen is in here for a reason: it rendered `R.mipmap.ic_launcher`, which on API 26+
 * resolves to `mipmap-anydpi-v26/ic_launcher.xml` -- an `<adaptive-icon>`, which is not a drawable
 * `painterResource` can inflate. Opening About therefore threw on every modern phone, and no unit
 * test could see it because the failure is in resource inflation on a device. That is exactly the
 * kind of defect that has to be pinned here rather than on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class FileTransferUiTest {

    @get:Rule val compose = createComposeRule()

    // -- the About screen regression ---------------------------------------------------------

    @Test
    fun aboutScreenRendersItsMark() {
        // If the mark is not a plain vector, this composition throws during resource inflation
        // and the test fails here rather than in a user's hands.
        compose.setContent { RelayDisplayTheme { AboutScreen(onBack = {}) } }
        compose.onNodeWithText("RelayDisplay").assertIsDisplayed()
    }

    // -- the incoming confirmation -----------------------------------------------------------

    private fun batch(vararg files: IncomingFile) =
        IncomingBatch(UUID.randomUUID(), "Avi's controller", files.toList())

    @Test
    fun incomingBatchDialogNamesEverySenderFileAndSize() {
        // Three sizes that all format differently, so each assertion below can only match the
        // line it is about. With a 512-byte second file the total rounded to "2.3 MB" as well and
        // the per-file assertion matched two nodes.
        val offered = batch(
            IncomingFile("quarterly.pdf", "application/pdf", 2_400_000),
            IncomingFile("notes.txt", "text/plain", 900_000),
        )
        compose.setContent {
            RelayDisplayTheme { IncomingBatchDialog(offered, onAccept = {}, onReject = {}) }
        }

        // Who is asking, how many, and every individual name and size. A prompt that says only
        // "accept 2 files" tells the user nothing about what they are accepting.
        compose.onNodeWithText("Avi's controller", substring = true).assertIsDisplayed()
        compose.onNodeWithText("quarterly.pdf", substring = true).assertIsDisplayed()
        compose.onNodeWithText("notes.txt", substring = true).assertIsDisplayed()
        // The per-file size and the batch total, each shown once.
        compose.onNodeWithText("2.3 MB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("878 KB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("3.1 MB", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("batch_accept").assertIsDisplayed()
        compose.onNodeWithTag("batch_reject").assertIsDisplayed()
    }

    @Test
    fun acceptAndRejectReportExactlyOnce() {
        var accepted = 0
        var rejected = 0
        compose.setContent {
            RelayDisplayTheme {
                IncomingBatchDialog(
                    batch(IncomingFile("a.pdf", "application/pdf", 10)),
                    onAccept = { accepted++ },
                    onReject = { rejected++ },
                )
            }
        }
        compose.onNodeWithTag("batch_accept").performClick()
        assertEquals(1, accepted)
        assertEquals(0, rejected)
    }

    @Test
    fun rejectingReportsARejection() {
        var rejected = 0
        compose.setContent {
            RelayDisplayTheme {
                IncomingBatchDialog(
                    batch(IncomingFile("a.pdf", "application/pdf", 10)),
                    onAccept = {},
                    onReject = { rejected++ },
                )
            }
        }
        compose.onNodeWithTag("batch_reject").performClick()
        assertEquals(1, rejected)
    }

    @Test
    fun anExecutableInTheBatchIsCalledOut() {
        compose.setContent {
            RelayDisplayTheme {
                IncomingBatchDialog(
                    batch(IncomingFile("game.apk", "application/vnd.android.package-archive", 900_000)),
                    onAccept = {},
                    onReject = {},
                )
            }
        }
        compose.onNodeWithText("never installs", substring = true).assertIsDisplayed()
    }

    // -- the received-files list -------------------------------------------------------------

    private fun received(name: String, mime: String, bytes: Int = 32): ReceivedFile {
        val id = UUID.randomUUID()
        val file = File.createTempFile(id.toString(), null)
        file.writeBytes(ByteArray(bytes))
        return ReceivedFile(id, name, mime, bytes.toLong(), file, System.currentTimeMillis())
    }

    @Test
    fun anEmptyListExplainsItselfRatherThanShowingNothing() {
        compose.setContent { RelayDisplayTheme { ReceivedFilesSection(emptyList(), onDelete = {}) } }
        compose.onNodeWithText("Received files").assertIsDisplayed()
        compose.onNodeWithText("appear here", substring = true).assertIsDisplayed()
    }

    @Test
    fun everyReceivedFileOffersAllFourActions() {
        compose.setContent {
            RelayDisplayTheme {
                ReceivedFilesSection(listOf(received("report.pdf", "application/pdf")), onDelete = {})
            }
        }
        compose.onNodeWithText("report.pdf").assertIsDisplayed()
        for (tag in listOf("received_open", "received_save", "received_share", "received_delete")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test
    fun deletingAsksFirstAndOnlyReportsAfterConfirmation() {
        var deleted: UUID? = null
        val file = received("gone.pdf", "application/pdf")
        compose.setContent {
            RelayDisplayTheme { ReceivedFilesSection(listOf(file), onDelete = { deleted = it }) }
        }

        compose.onNodeWithTag("received_delete").performClick()
        // Asked, not done: deletion is not undoable.
        assertEquals(null, deleted)
        compose.onNodeWithText("cannot be undone", substring = true).assertIsDisplayed()

        compose.onNodeWithTag("confirm_delete").performClick()
        assertEquals(file.transferId, deleted)
    }

    @Test
    fun keepingCancelsTheDeletion() {
        var deleted: UUID? = null
        compose.setContent {
            RelayDisplayTheme {
                ReceivedFilesSection(
                    listOf(received("kept.pdf", "application/pdf")),
                    onDelete = { deleted = it },
                )
            }
        }
        compose.onNodeWithTag("received_delete").performClick()
        compose.onNodeWithText("Keep").performClick()
        assertEquals(null, deleted)
    }

    @Test
    fun openingAnApkWarnsBeforeAnythingLeavesTheApp() {
        compose.setContent {
            RelayDisplayTheme {
                ReceivedFilesSection(
                    listOf(received("update.apk", "application/vnd.android.package-archive")),
                    onDelete = {},
                )
            }
        }
        compose.onNodeWithTag("received_open").performClick()
        // No chooser yet: an executable never opens on one tap. The app never installs or runs
        // anything it receives, and this is where that is made explicit to the user.
        compose.onNodeWithTag("open_warning_dialog").assertIsDisplayed()
        compose.onNodeWithText("will not install", substring = true).assertIsDisplayed()
    }

    @Test
    fun openingAnOrdinaryFileDoesNotWarn() {
        compose.setContent {
            RelayDisplayTheme {
                ReceivedFilesSection(listOf(received("notes.txt", "text/plain")), onDelete = {})
            }
        }
        // A chooser may or may not resolve on this device; what matters is that no warning was
        // needed, so the list is still what is on screen.
        compose.onNodeWithTag("received_open").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("open_warning_dialog").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theListShowsTypeAndSizeForEachFile() {
        compose.setContent {
            RelayDisplayTheme {
                ReceivedFilesSection(
                    listOf(received("archive.zip", "application/zip", bytes = 2048)),
                    onDelete = {},
                )
            }
        }
        compose.onNodeWithText("application/zip", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2 KB", substring = true).assertIsDisplayed()
    }

    @Test
    fun severalFilesEachGetTheirOwnRow() {
        compose.setContent {
            RelayDisplayTheme {
                ReceivedFilesSection(
                    listOf(
                        received("one.pdf", "application/pdf"),
                        received("two.txt", "text/plain"),
                        received("three.zip", "application/zip"),
                    ),
                    onDelete = {},
                )
            }
        }
        assertEquals(3, compose.onAllNodesWithTag("received_file").fetchSemanticsNodes().size)
    }
}
