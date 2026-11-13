package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.DeviceApp
import com.scrcpy.remote.session.DeviceAppPages
import com.scrcpy.remote.session.DeviceAppsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceAppsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val apps = listOf(
        DeviceApp("Calendar", "com.android.calendar", system = true),
        DeviceApp("Notes", "com.example.notes", system = false),
        DeviceApp("Photos", "com.example.photos", system = false),
    )

    @Test fun listingProgressExplainsWhyItTakesTimeAndOffersNoStaleList() {
        render(DeviceAppsState(loading = true))
        compose.onNodeWithText(text(R.string.apps_loading)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.apps_loading_help)).assertIsDisplayed()
        // A half-loaded list would invite launching something that is not really there.
        compose.onNodeWithText(text(R.string.apps_search)).assertDoesNotExist()
        // Already asking, so there is nothing to refresh.
        compose.onNodeWithContentDescription(text(R.string.apps_refresh)).assertDoesNotExist()
    }

    @Test fun aLoadedListCanBeAskedForAgainForChangesMadeOnTheDevice() {
        // An app installed on the device itself, from its store, appeared only after a reconnect.
        var refreshes = 0
        render(DeviceAppsState(apps = apps, loaded = true), onRetry = { refreshes++ }, fontScale = 2f)
        compose.onNodeWithContentDescription(text(R.string.apps_refresh))
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun aFailedListingIsRetryableWithoutLeavingTheSession() {
        var retries = 0
        render(DeviceAppsState(error = text(R.string.apps_list_failed)), onRetry = { retries++ })
        compose.onNodeWithText(text(R.string.apps_list_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.apps_retry)).assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun searchingFiltersByNameAndPackageAndLaunchesTheChosenApp() {
        var launched: DeviceApp? = null
        var forced: Boolean? = null
        render(DeviceAppsState(apps = apps, loaded = true), onLaunch = { app, force ->
            launched = app; forced = force
        })

        compose.onNodeWithTag("device-app-list").performScrollToNode(hasText("com.example.notes"))
        compose.onNodeWithText("com.example.notes").assertIsDisplayed()

        compose.onNodeWithText(text(R.string.apps_search)).performTextReplacement("photo")
        compose.onNodeWithText("com.example.notes").assertDoesNotExist()
        compose.onNodeWithText("com.example.photos").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("com.example.photos", launched?.packageName)
            assertEquals(false, forced)
        }
    }

    @Test fun eachRowCanForceStopTheAppBeforeReopeningIt() {
        // Restarting a frozen app is the reason to be in this dialog at all, and it must not be
        // confusable with an ordinary launch.
        var launched: DeviceApp? = null
        var forced: Boolean? = null
        render(DeviceAppsState(apps = apps, loaded = true), onLaunch = { app, force ->
            launched = app; forced = force
        })
        compose.onNodeWithText(text(R.string.apps_search)).performTextReplacement("notes")
        menuFor("Notes")
        compose.onNodeWithText(text(R.string.apps_restart)).assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals("com.example.notes", launched?.packageName)
            assertEquals(true, forced)
        }
    }

    @Test fun theMenuOpensTheDevicesOwnPagesAndOffersUninstallOnlyForAppsTheUserAdded() {
        val pages = mutableListOf<Pair<String, DeviceAppPages.Page>>()
        render(DeviceAppsState(apps = apps, loaded = true), onAppPage = { app, page -> pages += app.packageName to page })
        menuFor("Calendar")
        compose.onNodeWithText(text(R.string.apps_restart)).assertIsDisplayed()
        // It came with the device, and the device would ask about removing it all the same.
        compose.onNodeWithText(text(R.string.apps_uninstall)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.apps_app_info)).performClick()
        compose.onNodeWithText(text(R.string.apps_app_info)).assertDoesNotExist()
        menuFor("Notes")
        compose.onNodeWithText(text(R.string.apps_uninstall)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("com.android.calendar" to DeviceAppPages.Page.APP_INFO,
                "com.example.notes" to DeviceAppPages.Page.UNINSTALL), pages)
        }
    }

    @Test fun aSessionThatCannotShowTheDevicesPagesStillOffersRestart() {
        var forced: Boolean? = null
        render(DeviceAppsState(apps = apps, loaded = true), onLaunch = { _, force -> forced = force }, onAppPage = null)
        menuFor("Notes")
        compose.onNodeWithText(text(R.string.apps_app_info)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.apps_uninstall)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.apps_restart)).performClick()
        compose.runOnIdle { assertEquals(true, forced) }
    }

    @Test fun aRowKeepsTheWholeNameAndSaysSystemApartFromItAtDoubleText() {
        val long = DeviceApp("Calendar and agenda planner for the whole family",
            "com.example.calendar.family.planner", system = true)
        var launched: DeviceApp? = null
        render(DeviceAppsState(apps = listOf(long), loaded = true), onLaunch = { app, _ -> launched = app },
            fontScale = 2f)
        // The name is its own text, not one with "System" appended and then cut short.
        val name = compose.onNodeWithText(long.name, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.apps_system), useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        name.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertTrue("A long name wraps rather than stopping at one line", layouts.single().lineCount >= 2)
        compose.onNodeWithContentDescription(text(R.string.apps_more_actions, long.name))
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        // The package has the row's whole width, and wraps after a dot rather than mid-word.
        val packageLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(long.packageName, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(packageLayouts)
        val packageLayout = packageLayouts.single()
        val firstLine = packageLayout.layoutInput.text.text.substring(0, packageLayout.getLineEnd(0))
        assertTrue("Wrapped as '$firstLine'", packageLayout.lineCount >= 2 && firstLine.trimEnd('\u200B').endsWith("."))
        compose.onNodeWithText(long.packageName, substring = true).assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(long, launched) }
    }

    @Test fun theSystemTagSitsBesideAShortNameAndThePackageStartsUnderIt() {
        render(DeviceAppsState(apps = apps, loaded = true))
        val name = compose.onNodeWithText("Calendar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val tag = compose.onNodeWithText(text(R.string.apps_system), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val packageName = compose.onNodeWithText("com.android.calendar", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Beside the name: $tag, $name", tag.center.y in name.top..name.bottom && tag.left >= name.right)
        assertTrue("Under the name, not after the tag: $packageName, $name",
            abs(packageName.left - name.left) < 1f && packageName.top >= name.bottom - 1f)
    }

    @Test fun aSearchMatchingNothingSaysSoInsteadOfShowingAnEmptyBox() {
        var launched: DeviceApp? = null
        render(DeviceAppsState(apps = apps, loaded = true), onLaunch = { app, _ -> launched = app })
        compose.onNodeWithText(text(R.string.apps_search)).performTextReplacement("zzzz")
        compose.onNodeWithText(text(R.string.apps_empty)).assertIsDisplayed()
        compose.runOnIdle { assertNull(launched) }
    }

    @Test fun aDeviceReportingNoAppsIsNotBlamedOnTheSearchBox() {
        // "No apps matched that search" with an empty search box would be nonsense.
        render(DeviceAppsState(apps = emptyList(), loaded = true))
        compose.onNodeWithText(text(R.string.apps_none)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.apps_empty)).assertDoesNotExist()
    }

    private fun render(
        state: DeviceAppsState,
        onLaunch: (DeviceApp, Boolean) -> Unit = { _, _ -> },
        onRetry: () -> Unit = {},
        onAppPage: ((DeviceApp, DeviceAppPages.Page) -> Unit)? = { _, _ -> },
        fontScale: Float = 1f,
    ) {
        val current = mutableStateOf(state)
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                        DeviceAppsDialog(current.value, onLaunch, onRetry, onDismiss = {}, onAppPage = onAppPage)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun menuFor(name: String) {
        compose.onNodeWithContentDescription(text(R.string.apps_more_actions, name))
            .assertHasClickAction().performClick()
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}
