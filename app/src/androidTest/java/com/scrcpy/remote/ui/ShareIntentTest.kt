package com.scrcpy.remote.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.session.ShareCache
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The share target accepts every MIME type, because a file of any type can be sent. That also
 * puts this app in the share sheet for plain text and links, which carry no file at all.
 */
@RunWith(AndroidJUnit4::class)
class ShareIntentTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun send() = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)

    private fun awaitText(id: Int) {
        val text = context.getString(id)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun sharedTextIsDeclinedOutLoudRatherThanSilentlyIgnored() {
        val text = send().setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com/page")
        ActivityScenario.launch<MainActivity>(text).use {
            awaitText(R.string.share_no_file_message)
        }
    }

    @Test fun aFileSharedOverAnEarlierFailedShareReplacesItsMessage() {
        // A list already on screen takes a new share in onNewIntent. The earlier share's "no file"
        // message stayed over the new file's destination picker, saying the share had failed.
        val text = send().setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com/page")
        val copy = cachedCopy()
        try {
            ActivityScenario.launch<MainActivity>(text).use { scenario ->
                awaitText(R.string.share_no_file_message)
                shareAgain(scenario, copy, "photo.jpg")
                awaitText(R.string.share_pick_device_help)
                assertGone(R.string.share_no_file_message)
            }
        } finally {
            copy.delete()
        }
    }

    @Test fun aFileSharedOverAnUnreadableShareReplacesItsMessage() {
        val uri = "content://com.scrcpy.remote.test.unavailable/unreadable.bin".toUri()
        val unreadable = send().setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri)
        val copy = cachedCopy()
        try {
            ActivityScenario.launch<MainActivity>(unreadable).use { scenario ->
                awaitText(R.string.send_file_unreadable)
                shareAgain(scenario, copy, "photo.jpg")
                awaitText(R.string.share_pick_device_help)
                assertGone(R.string.send_file_unreadable)
            }
        } finally {
            copy.delete()
        }
    }

    @Test fun aSharedAppSaysItWillAskBeforeInstalling() {
        val text = send().setType("text/plain").putExtra(Intent.EXTRA_TEXT, "not a file")
        val copy = cachedCopy()
        try {
            ActivityScenario.launch<MainActivity>(text).use { scenario ->
                awaitText(R.string.share_no_file_message)
                shareAgain(scenario, copy, "tool.apk")
                awaitText(R.string.share_pick_device_help_app)
                assertGone(R.string.share_pick_device_help)
            }
        } finally {
            copy.delete()
        }
    }

    private fun cachedCopy(): File =
        File(File(context.cacheDir, "shared-uploads").apply { mkdirs() }, UUID.randomUUID().toString())
            .apply { writeBytes(byteArrayOf(4, 2)) }

    /** Delivered as the running list receives a second share; the scenario keeps its launch intent. */
    private fun shareAgain(scenario: ActivityScenario<MainActivity>, copy: File, name: String) {
        scenario.onActivity { activity ->
            ViewModelProvider(activity)[MainViewModel::class.java].shareCopier = {
                ShareCache.Copied(copy.absolutePath, name)
            }
            val launch = activity.intent
            MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).run {
                isAccessible = true
                invoke(activity, Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_STREAM, "content://com.scrcpy.remote.test.fixture/$name".toUri()))
            }
            activity.intent = launch
        }
    }

    private fun assertGone(id: Int) {
        compose.waitForIdle()
        assertTrue(context.getString(id),
            compose.onAllNodes(hasText(context.getString(id))).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aFileNamedOnlyInClipDataIsStillTaken() {
        val uri = "content://com.scrcpy.remote.test.unavailable/clip-only.bin".toUri()
        val share = send().setType("application/octet-stream").apply { clipData = ClipData.newRawUri("", uri) }
        ActivityScenario.launch<MainActivity>(share).use {
            // No such provider exists, so taking the URI ends in the unreadable-file notice; the
            // point is that the share was taken at all instead of being dropped.
            awaitText(R.string.send_file_unreadable)
        }
    }
}
