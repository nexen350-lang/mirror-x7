package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpandedNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun navigationStaysAboveScrolledToolsAtSmallWidthAndLargeText() {
        val dimensions = mutableStateOf(240 to 360)
        val density = compose.activity.resources.displayMetrics.density
        var back = 0
        var home = 0
        var recents = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                        Box(Modifier.requiredSize(dimensions.value.first.dp, dimensions.value.second.dp)) {
                            FloatingControlsOverlay(
                                MirrorActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { true }, onBackspace = {}, onEnter = {}, onDisconnect = {})
                                    .copy(onBack = { back++ }, onHome = { home++ }, onRecents = { recents++ }),
                                false, Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(text(R.string.control_show)).performClick()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        for (size in listOf(240 to 360, 320 to 240)) {
            compose.runOnIdle { dimensions.value = size }
            val tool = compose.onNodeWithContentDescription(text(R.string.calibrate)).performScrollTo().assertIsDisplayed()
            val panel = compose.onNodeWithTag("expanded-controls").fetchSemanticsNode().boundsInRoot
            val nav = compose.onNodeWithTag("expanded-navigation").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val toolBounds = tool.fetchSemanticsNode().boundsInRoot
            assertTrue("Navigation $nav must stay above visible scrolled tool $toolBounds for $size",
                nav.bottom <= toolBounds.top + 1)
            assertTrue("Navigation stays at the top of the panel", nav.top - panel.top <= 9 * density)
            for (id in listOf(R.string.control_back, R.string.control_home, R.string.control_recents)) {
                val button = compose.onNodeWithContentDescription(text(id))
                    .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                val bounds = button.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= nav.left - 1 && bounds.right <= nav.right + 1)
                assertTrue(bounds.top >= nav.top - 1 && bounds.bottom <= nav.bottom + 1)
                button.performClick()
            }
            compose.onNodeWithText(text(R.string.exit)).assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(2, back); assertEquals(2, home); assertEquals(2, recents) }
    }

    private fun text(id: Int) = compose.activity.getString(id)
}
