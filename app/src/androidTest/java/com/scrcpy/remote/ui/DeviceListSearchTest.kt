package com.scrcpy.remote.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.SavedDevice
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The device list's search field. Android 8 gives a new window's first text field focus even in
 * touch mode, so the list opened, and came back from every session, with the search field active
 * as though the user had tapped it. Android 9 and later give no initial focus in touch mode, so
 * only an Android 8 run can fail this.
 */
@RunWith(AndroidJUnit4::class)
class DeviceListSearchTest {
    @get:Rule(order = 0) val touchMode = TouchModeRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = DeviceRepository.get(context)

    @Test fun theSearchFieldWaitsForATapInsteadOfTakingFocusWhenTheListOpens() {
        val device = SavedDevice(id = "search-focus-${UUID.randomUUID()}", name = "Search focus fixture",
            host = "192.0.2.41")
        assertTrue(repository.saveDevice(device))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(5_000) {
                    var focused = false
                    scenario.onActivity { focused = it.hasWindowFocus() }
                    focused
                }
                var opened: MainActivity? = null
                scenario.onActivity { opened = it }
                assertInTouchMode(checkNotNull(opened))
                val search = compose.onNodeWithText(context.getString(R.string.device_list_search))
                search.assertIsDisplayed()
                compose.waitForIdle()
                search.assertIsNotFocused()
                // A tap still focuses it.
                search.performClick()
                search.assertIsFocused()
            }
        } finally {
            repository.deleteDevice(device.id)
        }
    }
}
