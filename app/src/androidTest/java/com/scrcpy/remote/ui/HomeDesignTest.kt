package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.SavedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** The device list's design: what comes first, what is emphasised, and what each device shows. */
@RunWith(AndroidJUnit4::class)
class HomeDesignTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val devices = listOf(
        SavedDevice(id = "a1", name = "Living room tablet", host = "10.0.2.2", port = 5599),
        SavedDevice(id = "b2", name = "Office phone", host = "192.168.1.20"),
        SavedDevice(id = "c3", name = "Pixel 9", host = "192.168.1.31", port = 41234),
    )

    @Test fun savedDevicesComeFirstAndSetupHelpFollowsThem() {
        list(devices, height = tallest())
        // Nothing but the list's spacing between the search field and the first device, at any
        // screen height.
        val search = bounds(compose.onNodeWithText(text(R.string.device_list_search)).fetchSemanticsNode())
        val first = bounds(compose.onNodeWithText(devices.first().name).fetchSemanticsNode())
        assertTrue("The first device follows the search field: $first vs $search",
            first.top > search.bottom && first.top - search.bottom < 24 * density())
        // Setup help is for adding a device. Once there are some, it follows them.
        val last = bounds(compose.onNodeWithText(devices.last().name).fetchSemanticsNode())
        val setup = bounds(compose.onNodeWithText(text(R.string.wireless_debugging)).fetchSemanticsNode())
        assertTrue("Setup help after the last device: $setup vs $last", setup.top > last.bottom)
    }

    @Test fun theLargeTitleIsThePagesHeading() {
        // The activity's own screen. Moving by headings lands on the title, and only once, though
        // the bar draws it twice to collapse it.
        compose.onAllNodes(isHeading() and hasText(text(R.string.release_devices_title))).assertCountEquals(1)
    }

    @Test fun addingAnotherDeviceSitsInTheBarRatherThanOverTheList() {
        // A floating button rested on the third card's Connect on a phone-sized screen, so a tap
        // meant for Connect opened Add device.
        val repository = DeviceRepository.get(compose.activity)
        val saved = SavedDevice(name = "Bar placement fixture", host = "192.0.2.40")
        assertTrue(repository.saveDevice(saved))
        try {
            compose.waitUntil(5_000) { compose.onAllNodesWithText(saved.name).fetchSemanticsNodes().isNotEmpty() }
            val add = bounds(compose.onNodeWithContentDescription(text(R.string.add_device)).fetchSemanticsNode())
            val list = bounds(compose.onNodeWithTag("saved-device-list").fetchSemanticsNode())
            assertTrue("Add device above the list rather than over it: $add vs $list", add.bottom <= list.top + 1f)
        } finally {
            repository.deleteDevice(saved.id)
        }
    }

    @Test fun anEmptyListOffersToAddOrPairRightWhereItSaysSo() {
        var adds = 0
        var pairs = 0
        list(emptyList(), onAdd = { adds++ }, onPair = { pairs++ })
        compose.onNodeWithText(text(R.string.release_empty_title)).assertIsDisplayed()
        // The same name the bar's + has, which it replaces while the list is empty.
        compose.onNodeWithContentDescription(text(R.string.add_device)).assertIsDisplayed()
            .assertHasClickAction().performClick()
        compose.onNodeWithText(text(R.string.pair_new_device)).assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, adds); assertEquals(1, pairs) }
    }

    @Test fun eachDeviceHasItsOwnAvatarThatIsNotReadAloud() {
        list(devices, height = tallest())
        val avatars = compose.onAllNodesWithTag("device-avatar", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(devices.size, avatars.size)
        for (avatar in avatars) {
            // The name beside it is read; initials would only repeat it badly.
            assertEquals(null, avatar.config.getOrNull(SemanticsProperties.Text))
            assertEquals(null, avatar.config.getOrNull(SemanticsProperties.ContentDescription))
            assertTrue("At least 40 dp: ${avatar.size}", avatar.size.width >= 40 * density())
        }
        // What a screen reader reads for the card: its name and address, without "LR".
        val read = compose.onNodeWithText(devices.first().name).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ")
        assertTrue("Read as: $read", devices.first().name in read && "LR" !in read)
        val fills = (0 until devices.size).map { index ->
            compose.onAllNodesWithTag("device-avatar", useUnmergedTree = true)[index].captureToImage().toPixelMap()
                .let { it[3, it.height / 2] }
        }
        // a1 and c3 pick the same colour from their ids; in one list they still get their own.
        assertEquals("Each device its own colour: $fills", devices.size, fills.toSet().size)
    }

    @Test fun connectIsTheCardsOneFilledActionAndTheSettingsLabelIsNot() {
        var scheme: ColorScheme? = null
        render(width = 360, height = 400) {
            scheme = MaterialTheme.colorScheme
            DeviceRow(devices.first(), {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
        val colors = checkNotNull(scheme)
        // Sampled just inside each control's left edge, at mid-height: inside the fill, clear of
        // its border, icon and text.
        val inset = (4 * density()).toInt()
        val connect = compose.onNodeWithContentDescription(text(R.string.device_list_connect, devices.first().name))
            .captureToImage().toPixelMap()
        assertClose("Connect is filled with the primary colour", colors.primary, connect[inset, connect.height / 2])
        val settings = compose.onNodeWithText(text(R.string.device_settings_global_summary)).captureToImage().toPixelMap()
        assertClose("The settings label sits on the card, unfilled", colors.surfaceContainerLowest,
            settings[inset, settings.height / 2])
    }

    private fun assertClose(message: String, expected: Color, actual: Color) {
        val distance = abs(expected.red - actual.red) + abs(expected.green - actual.green) + abs(expected.blue - actual.blue)
        assertTrue("$message: expected $expected, was $actual", distance < 0.08f)
        assertNotEquals(message, Color.Unspecified, actual)
    }

    private fun list(
        devices: List<SavedDevice>,
        height: Int = 720,
        onAdd: () -> Unit = {},
        onPair: () -> Unit = {},
    ) = render(width = 360, height = height) {
        DeviceList(devices, "", {}, false, {}, PaddingValues(), {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> },
            onPair = onPair, onAdd = onAdd)
    }

    private fun render(width: Int, height: Int, content: @Composable () -> Unit) {
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                        Surface { Box(Modifier.requiredSize(width.dp, height.dp)) { content() } }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** As tall as the window allows: a box taller than the window is clipped, and so are its bounds. */
    private fun tallest(): Int = minOf(880, (compose.activity.window.decorView.height / density()).toInt() - 24)

    /** Unclipped, so a node partly off screen still reports where it is. */
    private fun bounds(node: SemanticsNode): Rect =
        Rect(node.positionInWindow.x, node.positionInWindow.y,
            node.positionInWindow.x + node.size.width, node.positionInWindow.y + node.size.height)

    private fun density() = compose.activity.resources.displayMetrics.density
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}
