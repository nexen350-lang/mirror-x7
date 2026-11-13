package com.scrcpy.remote.ui

import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.canonicalHost
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.CameraFacing
import com.scrcpy.remote.scrcpy.CameraSize
import com.scrcpy.remote.scrcpy.DeviceCamera
import com.scrcpy.remote.session.DeviceCamerasState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MirrorTargetUiStateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val first = MirrorTarget("PHONE.local.", 5555, "profile-a", null)
    private val camera = DeviceCamera(
        id = "front", facing = CameraFacing.FRONT, width = 1920, height = 1080,
        frameRates = listOf(30, 60), sizes = listOf(CameraSize(1920, 1080), CameraSize(1280, 720)),
    )
    private val resolvedCamera = CameraCapture(id = camera.id, size = "1920x1080", fps = 30)

    @Test fun currentTargetCameraAndCalibrationDraftsSurviveRestoration() {
        val target = mutableStateOf(first)
        val saved = mutableListOf<CameraCapture?>()
        val restoration = render(target) { saved += it }
        calibration().performTextReplacement("17")
        openCamera()
        choice("1280x720").performSemanticsAction(SemanticsActions.OnClick)
        choice(text(R.string.cameras_fps_value, 60)).performSemanticsAction(SemanticsActions.OnClick)

        restoration.emulateSavedInstanceStateRestore()

        choice("1280x720").assertIsSelected()
        choice(text(R.string.cameras_fps_value, 60)).assertIsSelected()
        compose.runOnIdle { assertEquals(0, saved.size) }
        compose.onNodeWithTag("camera-save").performSemanticsAction(SemanticsActions.OnClick)
        calibration().assertTextEquals("17")
        compose.runOnIdle {
            assertEquals(listOf(CameraCapture(id = camera.id, size = "1280x720", fps = 60)), saved)
        }
    }

    @Test fun changingTargetDiscardsOldDialogsAndDraftsEvenWhenSwitchingBack() {
        val target = mutableStateOf(first)
        var saves = 0
        val restoration = render(target) { saves++ }
        calibration().performTextReplacement("17")
        openCamera()
        choice("1280x720").performSemanticsAction(SemanticsActions.OnClick)

        compose.runOnIdle { target.value = first.copy(host = "other.local", port = 5556) }
        compose.onNodeWithTag("camera-save").assertDoesNotExist()
        calibration().assertTextEquals("2")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("camera-save").assertDoesNotExist()
        calibration().assertTextEquals("2")

        compose.runOnIdle { target.value = first }
        compose.onNodeWithTag("camera-save").assertDoesNotExist()
        calibration().assertTextEquals("1")
        openCamera()
        choice("1920x1080").assertIsSelected()
        compose.runOnIdle { assertEquals("No discarded draft may be applied to either target", 0, saves) }
    }

    @Test fun equivalentEndpointAndEmptyOptionsKeepTheCurrentDraft() {
        val target = mutableStateOf(first)
        val restoration = render(target)
        calibration().performTextReplacement("17")
        openCamera()
        choice("1280x720").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { target.value = first.copy(host = "phone.local", linkOptions = "") }
        choice("1280x720").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        choice("1280x720").assertIsSelected()
        compose.onNodeWithTag("camera-save").performSemanticsAction(SemanticsActions.OnClick)
        calibration().assertTextEquals("17")
    }

    private fun render(
        target: MutableState<MirrorTarget>,
        onSave: (CameraCapture?) -> Unit = {},
    ): StateRestorationTester {
        compose.runOnUiThread {
            compose.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
        }
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                ScrcpyRemoteTheme {
                    val current = target.value
                    TargetScopedMirrorUi(current) {
                        var cameraOpen by rememberSaveable { mutableStateOf(false) }
                        var calibration by rememberSaveable {
                            mutableStateOf(if (canonicalHost(current.host) == canonicalHost("phone.local")) "1" else "2")
                        }
                        Column {
                            OutlinedTextField(
                                value = calibration,
                                onValueChange = { calibration = it },
                                modifier = Modifier.testTag("scoped-calibration"),
                            )
                            Button(onClick = { cameraOpen = true }) { Text("Open camera") }
                        }
                        if (cameraOpen) {
                            DeviceCamerasDialog(
                                state = DeviceCamerasState(cameras = listOf(camera), loaded = true),
                                selected = resolvedCamera,
                                onSelect = { onSave(it); cameraOpen = false },
                                createsProfile = false,
                                sessionOnly = true,
                                onRetry = {},
                                onDismiss = { cameraOpen = false },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun calibration() = compose.onNodeWithTag("scoped-calibration")
    private fun openCamera() = compose.onNodeWithText("Open camera").performSemanticsAction(SemanticsActions.OnClick)
    private fun choice(label: String) =
        compose.onNodeWithTag("device-camera-list").performScrollToNode(hasText(label)).let {
            compose.onNodeWithText(label)
        }
    private fun text(id: Int, vararg arguments: Any) = compose.activity.getString(id, *arguments)
}
