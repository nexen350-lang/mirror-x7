package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.BackupHistoryEntry
import com.scrcpy.remote.data.BackupHistoryKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupHistoryDialogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val complete = BackupHistoryEntry("complete", "Before import", 1_700_000_000_000, 1,
        listOf("Saved phone"), "Complete snapshot details", BackupHistoryKind.SNAPSHOT)
    private var state by mutableStateOf(BackupHistoryState(visible = true))
    private var restores = 0
    private var exports = 0
    private var deletions = 0

    private fun show(entries: List<BackupHistoryEntry> = listOf(complete)) {
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    BackupHistoryDialogs(state, entries,
                        onDismiss = { state = state.copy(visible = false) },
                        onRefresh = {},
                        onSelect = { state = state.copy(selectedId = it) },
                        onRestore = { restores++ },
                        onAskExport = { state = state.copy(exportId = it) },
                        onConfirmExport = { exports++ },
                        onAskDelete = { state = state.copy(deleteId = it) },
                        onConfirmDelete = { deletions++ },
                    )
                }
            }
        }
    }

    @Test fun completeEntryShowsDetailsAndExportRequiresPrivacyConsent() {
        show()
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.history_count, 1, 1)).assertIsDisplayed()
        compose.onNodeWithTag("history-entry-complete").performClick()
        compose.onNodeWithText(complete.details).assertIsDisplayed()
        scrollTo(R.string.history_export)
        compose.onNodeWithText(text(R.string.history_export)).performClick()
        compose.onNodeWithTag("history-export-warning").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.profile_tools_export_help)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, exports); assertEquals(0, restores); assertEquals(0, deletions) }
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(0, exports) }
        scrollTo(R.string.history_export)
        compose.onNodeWithText(text(R.string.history_export)).performClick()
        compose.onNodeWithText(text(R.string.profile_tools_export_confirm)).performClick()
        compose.runOnIdle { assertEquals(1, exports) }
    }

    @Test fun deletingARecordRequiresASeparateExplicitConfirmation() {
        show()
        compose.onNodeWithTag("history-entry-complete").performClick()
        scrollTo(R.string.history_delete)
        compose.onNodeWithText(text(R.string.history_delete)).performClick()
        compose.onNodeWithTag("history-confirm-delete").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, deletions) }
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(0, deletions) }
        scrollTo(R.string.history_delete)
        compose.onNodeWithText(text(R.string.history_delete)).performClick()
        compose.onNodeWithTag("history-confirm-delete")
            .assertIsDisplayed()
        compose.onNodeWithTag("history-delete-confirm-action").performClick()
        compose.runOnIdle { assertEquals(1, deletions) }
    }

    @Test fun partialHistoryIsExplicitAndCannotBeRestoredOrExportedAsACompleteSnapshot() {
        val partial = complete.copy(id = "partial", name = "Legacy profiles", createdAtMillis = null,
            details = "Partial profiles only; original data is retained.", kind = BackupHistoryKind.LEGACY_PARTIAL)
        show(listOf(partial))
        compose.onNodeWithText(text(R.string.history_date_unknown)).assertIsDisplayed()
        compose.onNodeWithTag("history-entry-partial").performClick()
        scrollTo(R.string.history_restore)
        compose.onNodeWithText(text(R.string.history_restore)).assertIsNotEnabled()
        scrollTo(R.string.history_export)
        compose.onNodeWithText(text(R.string.history_export)).assertIsNotEnabled()
        scrollTo(R.string.history_delete)
        compose.onNodeWithText(text(R.string.history_delete)).performClick()
        compose.onNodeWithText(text(R.string.history_delete_recovery_warning)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, restores); assertEquals(0, exports); assertEquals(0, deletions) }
    }

    private fun scrollTo(id: Int) =
        compose.onNodeWithTag("history-details").performScrollToNode(hasText(text(id)))

    private fun text(id: Int) = app.getString(id)
}
