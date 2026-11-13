package com.scrcpy.remote.ui

import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.CameraFacing
import com.scrcpy.remote.scrcpy.CameraSize
import com.scrcpy.remote.scrcpy.DeviceCamera
import com.scrcpy.remote.session.DeviceCamerasState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceCamerasDialogStateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val camera = DeviceCamera(
        id = "front",
        facing = CameraFacing.FRONT,
        width = 1920,
        height = 1080,
        frameRates = listOf(30, 60),
        sizes = listOf(CameraSize(1920, 1080), CameraSize(1280, 720)),
        highSpeedSizes = listOf(CameraSize(1280, 720, listOf(120, 240))),
    )

    @Test
    fun reselectingTheCurrentCameraPreservesEveryCaptureOption() {
        val original = CameraCapture(
            id = camera.id, size = "1280x720", aspectRatio = "16:9", fps = 120, highSpeed = true,
        )
        var saved: CameraCapture? = null
        render(selected = original) { saved = it }
        choice(text(R.string.cameras_front)).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("camera-save").performClick()
        compose.runOnIdle { assertEquals(original, saved) }
    }

    @Test
    fun reselectingTheCurrentCameraKeepsUnsavedSizeAndRateEdits() {
        var saved: CameraCapture? = null
        render(selected = CameraCapture(id = camera.id, size = "1920x1080", fps = 30)) { saved = it }
        choice("1280x720").performSemanticsAction(SemanticsActions.OnClick)
        choice(text(R.string.cameras_fps_value, 60)).performSemanticsAction(SemanticsActions.OnClick)
        choice(text(R.string.cameras_front)).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("camera-save").performClick()
        compose.runOnIdle {
            assertEquals(CameraCapture(id = camera.id, size = "1280x720", fps = 60), saved)
        }
    }

    @Test
    fun choosingADifferentCameraStillClearsCameraSpecificOptions() {
        val other = camera.copy(id = "back", facing = CameraFacing.BACK)
        var saved: CameraCapture? = null
        render(
            selected = CameraCapture(
                id = camera.id, size = "1280x720", aspectRatio = "16:9", fps = 120, highSpeed = true,
            ),
            cameras = listOf(camera, other),
        ) { saved = it }
        choice(text(R.string.cameras_back)).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("camera-save").performClick()
        compose.runOnIdle { assertEquals(CameraCapture(id = other.id), saved) }
    }

    @Test
    fun unsavedCameraSizeRateAndHighSpeedSurviveStateRestoration() {
        var saved: CameraCapture? = null
        var saves = 0
        val restoration = render(selected = null) { saved = it; saves++ }
        choice(text(R.string.cameras_front)).performSemanticsAction(SemanticsActions.OnClick)
        choice(text(R.string.cameras_high_speed)).performSemanticsAction(SemanticsActions.OnClick)
        choice("1280x720").performSemanticsAction(SemanticsActions.OnClick)
        choice(text(R.string.cameras_fps_value, 120)).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(0, saves) }

        restoration.emulateSavedInstanceStateRestore()

        choice(text(R.string.cameras_front)).assertIsSelected()
        choice(text(R.string.cameras_high_speed)).assertIsOn()
        choice("1280x720").assertIsSelected()
        choice(text(R.string.cameras_fps_value, 120)).assertIsSelected()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithTag("camera-save").performClick()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(CameraCapture(id = camera.id, size = "1280x720", fps = 120, highSpeed = true), saved)
        }
    }

    @Test
    fun switchingBackToScreenSurvivesStateRestorationWithoutRestoringTheOldCamera() {
        val original = CameraCapture(id = camera.id, size = "1920x1080", fps = 30)
        var saved: CameraCapture? = original
        var saves = 0
        val restoration = render(selected = original) { saved = it; saves++ }
        choice(text(R.string.cameras_screen)).performSemanticsAction(SemanticsActions.OnClick)

        restoration.emulateSavedInstanceStateRestore()

        choice(text(R.string.cameras_screen)).assertIsSelected()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithTag("camera-save").performClick()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertNull(saved)
        }
    }

    @Test
    fun restoringTheDraftPreservesFacingAndAspectRatioSettings() {
        val original = CameraCapture(facing = CameraFacing.BACK, aspectRatio = "4:3", fps = 30)
        var saved: CameraCapture? = null
        val restoration = render(selected = original) { saved = it }

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("camera-save").performClick()
        compose.runOnIdle { assertEquals(original, saved) }
    }

    @Test
    fun aCameraInUseThatTheDeviceDidNotListIsShownAsTheChoiceItIs() {
        // Listed or not, the session is running on it; showing nothing selected left Save keeping
        // a camera the user could not see.
        val current = CameraCapture(id = "7", fps = 30)
        var saved: CameraCapture? = null
        render(selected = current) { saved = it }
        choice(text(R.string.cameras_current_unlisted, text(R.string.cameras_numbered, "7"))).assertIsSelected()
        compose.onNodeWithTag("camera-save").performClick()
        compose.runOnIdle { assertEquals(current, saved) }
    }

    @Test
    fun aCameraChosenByWhichWayItFacesIsShownTooAndSwitchingAwayWorks() {
        val current = CameraCapture(facing = CameraFacing.BACK)
        var saved: CameraCapture? = current
        render(selected = current) { saved = it }
        choice(text(R.string.cameras_current_unlisted, text(R.string.cameras_back))).assertIsSelected()
        choice(text(R.string.cameras_screen)).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("camera-save").performClick()
        compose.runOnIdle { assertNull(saved) }
    }

    private fun render(
        selected: CameraCapture?,
        cameras: List<DeviceCamera> = listOf(camera),
        onSelect: (CameraCapture?) -> Unit,
    ): StateRestorationTester {
        // StateRestorationTester owns the content; MainActivity has already installed its launcher UI.
        compose.runOnUiThread {
            compose.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
        }
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                ScrcpyRemoteTheme {
                    DeviceCamerasDialog(
                        state = DeviceCamerasState(cameras = cameras, loaded = true),
                        selected = selected,
                        onSelect = onSelect,
                        createsProfile = false,
                        sessionOnly = true,
                        onRetry = {},
                        onDismiss = {},
                    )
                }
            }
        }
    }

    private fun choice(label: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("device-camera-list").performScrollToNode(hasText(label))
        return compose.onNodeWithText(label)
    }

    private fun text(id: Int, vararg arguments: Any) = compose.activity.getString(id, *arguments)
}
