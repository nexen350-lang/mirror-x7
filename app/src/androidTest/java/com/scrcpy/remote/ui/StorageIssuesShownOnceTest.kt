package com.scrcpy.remote.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.PersistenceIssue
import com.scrcpy.remote.data.SavedDevice
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A storage problem is the user's to see once.
 *
 * The repository keeps its list for as long as the process lives, and a screen opened later showed
 * the same old problem again: every mirroring session started with it, and a device list opened
 * afresh asked about it again.
 */
@RunWith(AndroidJUnit4::class)
class StorageIssuesShownOnceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = DeviceRepository.get(context)

    @Test fun aProblemClosedOnTheDeviceListIsNotAskedAboutByTheNextOne() {
        val issue = reportIssue()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(issue.message, substring = true).assertIsDisplayed()
            compose.onNode(hasText(context.getString(R.string.close)) and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { issue !in repository.errors.value }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            compose.onNodeWithText(issue.message, substring = true).assertDoesNotExist()
        }
    }

    @Test fun aProblemShownDuringOneSessionDoesNotOpenTheNext() {
        grantNotifications()
        val issue = reportIssue()
        ActivityScenario.launch<MirrorActivity>(MirrorActivity.newIntent(context, HOST, INVALID_PORT)).use {
            compose.waitUntil(5_000) { compose.onAllNodesWithText(issue.message).fetchSemanticsNodes().isNotEmpty() }
            // Seen once its message has had its time on screen.
            compose.waitUntil(15_000) { issue !in repository.errors.value }
        }
        ActivityScenario.launch<MirrorActivity>(MirrorActivity.newIntent(context, HOST, INVALID_PORT)).use {
            compose.waitForIdle()
            compose.onNodeWithText(issue.message).assertDoesNotExist()
        }
    }

    @Test fun eachProblemWaitingWhenASessionStartsIsShownThereBeforeItCounts() {
        // Taking every waiting problem off the list after showing the newest meant the others were
        // never shown anywhere, by this screen or by the device list.
        grantNotifications()
        repository.acknowledge(repository.errors.value)
        assertFalse(repository.deleteBackupHistory("storage-issue-test-${UUID.randomUUID()}"))
        assertFalse(repository.saveDevice(SavedDevice(name = "Unsaveable", host = "host/path")))
        val (older, newer) = repository.errors.value.takeLast(2)
        ActivityScenario.launch<MirrorActivity>(MirrorActivity.newIntent(context, HOST, INVALID_PORT)).use {
            compose.waitUntil(5_000) { compose.onAllNodesWithText(newer.message).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15_000) { compose.onAllNodesWithText(older.message).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15_000) { older !in repository.errors.value && newer !in repository.errors.value }
        }
    }

    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Something harmless to have gone wrong, reported newest of all: a backup that is not there. */
    private fun reportIssue(): PersistenceIssue {
        repository.acknowledge(repository.errors.value)
        assertFalse(repository.deleteBackupHistory("storage-issue-test-${UUID.randomUUID()}"))
        return repository.errors.value.last()
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val INVALID_PORT = 0
    }
}
