package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The prompt before a link connects names everything the link asks for, the newer options included:
 * agreeing to connect must not also mean agreeing to something unseen.
 */
@RunWith(AndroidJUnit4::class)
class LinkSummaryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun thePromptNamesSoundAndDeviceOptionsWithoutAnObsoleteServerChoice() {
        val link = checkNotNull(parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&audio=playback&audioDup=true" +
            "&audioBitrate=192&keepActive=true&server=4.1")) { "The link parses" }
        val summary = checkNotNull(summaryOf(link))
        for (part in listOf(
            text(R.string.link_request_audio_dup_on),
            text(R.string.link_request_audio_bitrate, 192),
            text(R.string.link_request_keep_active_on),
        )) {
            assertTrue("The prompt names \"$part\": $summary", summary.contains(part))
        }
    }

    @Test fun aHistoricalServerTokenAloneHasNoEmptySettingsPrompt() {
        val link = checkNotNull(parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&server=4.1"))
        assertNull(summaryOf(link))
    }

    @Test fun turningThoseOffIsNamedToo() {
        val link = checkNotNull(parseLinkEndpoint(
            "scrcpy://connect?host=10.0.0.2&audio=playback&audioDup=false&keepActive=false")) { "The link parses" }
        val summary = checkNotNull(summaryOf(link))
        assertTrue(summary, summary.contains(text(R.string.link_request_audio_dup_off)))
        assertTrue(summary, summary.contains(text(R.string.link_request_keep_active_off)))
    }

    private fun summaryOf(link: LinkEndpoint): String? {
        var summary: String? = null
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = false) {
                    linkRequestSummary(link).also { summary = it }?.let { Text(it) }
                }
            }
        }
        compose.waitForIdle()
        return compose.runOnIdle { summary }
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}