package com.scrcpy.remote.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPipPolicyTest {
    @Test fun aLiveCameraWithNoPendingWorkIsEligible() {
        assertNull(blocked())
    }

    @Test fun devicesWithoutTheFeatureAreDenied() {
        assertEquals(CameraPipPolicy.Blocked.UNAVAILABLE, blocked(supported = false))
    }

    @Test fun physicalAndVirtualScreenSessionsAreNotCameraSessions() {
        for ((width, height) in listOf(1080 to 2400, 1920 to 1080)) {
            assertEquals(CameraPipPolicy.Blocked.NOT_CAMERA,
                blocked(camera = false, width = width, height = height))
        }
    }

    @Test fun connectingFailedAndDisconnectedCamerasAreDenied() {
        assertEquals(CameraPipPolicy.Blocked.NOT_LIVE, blocked(connected = false))
    }

    @Test fun missingSurfaceAndUninitializedVideoAreDenied() {
        assertEquals(CameraPipPolicy.Blocked.NOT_LIVE, blocked(surfaceReady = false))
        for ((width, height) in listOf(0 to 0, 0 to 720, 720 to 0, -1 to 720, 720 to -1)) {
            assertEquals(CameraPipPolicy.Blocked.NOT_LIVE, blocked(width = width, height = height))
        }
    }

    @Test fun pendingArtifactsAndDialogWorkBlockEntry() {
        assertEquals(CameraPipPolicy.Blocked.BUSY, blocked(busy = true))
    }

    @Test fun pausedAndStoppedActivitiesCannotRequestEntry() {
        assertEquals(CameraPipPolicy.Blocked.NOT_FOREGROUND, blocked(foreground = false))
    }

    @Test fun ordinaryCameraAspectsArePreservedAndReduced() {
        assertEquals(CameraPipPolicy.AspectRatio(16, 9), CameraPipPolicy.aspectRatio(1920, 1080))
        assertEquals(CameraPipPolicy.AspectRatio(9, 16), CameraPipPolicy.aspectRatio(1080, 1920))
        assertEquals(CameraPipPolicy.AspectRatio(4, 3), CameraPipPolicy.aspectRatio(640, 480))
        assertEquals(CameraPipPolicy.AspectRatio(1, 1), CameraPipPolicy.aspectRatio(640, 640))
    }

    @Test fun exactPlatformLimitsRemainValid() {
        assertEquals(CameraPipPolicy.AspectRatio(239, 100), CameraPipPolicy.aspectRatio(2390, 1000))
        assertEquals(CameraPipPolicy.AspectRatio(100, 239), CameraPipPolicy.aspectRatio(1000, 2390))
    }

    @Test fun extremelyWideOrTallCameraFramesClampWithoutOverflow() {
        assertEquals(CameraPipPolicy.AspectRatio(239, 100),
            CameraPipPolicy.aspectRatio(Int.MAX_VALUE, 1))
        assertEquals(CameraPipPolicy.AspectRatio(100, 239),
            CameraPipPolicy.aspectRatio(1, Int.MAX_VALUE))
        assertEquals(CameraPipPolicy.AspectRatio(1, 1),
            CameraPipPolicy.aspectRatio(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun malformedDimensionsNeverProduceAnAndroidRational() {
        for ((width, height) in listOf(0 to 0, -1 to 4, 4 to -1, Int.MIN_VALUE to 1, 1 to 0)) {
            assertNull(CameraPipPolicy.aspectRatio(width, height))
        }
    }

    @Test fun allPositiveIntDimensionsProduceRatiosInsideTheNativeRange() {
        val sizes = listOf(1, 2, 9, 16, 100, 239, 480, 720, 1920, 8192, Int.MAX_VALUE)
        for (width in sizes) for (height in sizes) {
            val aspect = requireNotNull(CameraPipPolicy.aspectRatio(width, height))
            assertTrue(aspect.numerator > 0 && aspect.denominator > 0)
            assertTrue(aspect.numerator.toLong() * 100 <= aspect.denominator.toLong() * 239)
            assertTrue(aspect.numerator.toLong() * 239 >= aspect.denominator.toLong() * 100)
        }
    }

    @Test fun onlyTheMirrorActivityAdvertisesPipWithoutAddingAPermission() {
        val manifest = File("src").resolve("main").resolve("AndroidManifest.xml").readText()
        val mirror = manifest.substringAfter("android:name=\".ui.MirrorActivity\"").substringBefore("/>")
        assertTrue(mirror.contains("android:supportsPictureInPicture=\"true\""))
        assertTrue(mirror.contains("android:resizeableActivity=\"true\""))
        assertTrue(mirror.contains("smallestScreenSize"))
        assertEquals(1, "supportsPictureInPicture".toRegex().findAll(manifest).count())
        assertFalse(manifest.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertFalse(manifest.contains("android.permission.CAMERA"))
    }

    @Test fun homeDoesNotBecomeAnImplicitPipRequest() {
        val source = File("src").resolve("main").resolve("java").resolve("com").resolve("scrcpy")
            .resolve("remote").resolve("ui").resolve("MirrorActivity.kt").readText()
        assertFalse(source.contains("onUserLeaveHint"))
        assertFalse(source.contains("setAutoEnterEnabled(true)"))
        assertTrue(source.contains("setAutoEnterEnabled(false)"))
    }

    @Test fun newRequestsReuseThePipOwnerAndRestoreItToFullscreen() {
        val manifest = File("src").resolve("main").resolve("AndroidManifest.xml").readText()
        val mirror = manifest.substringAfter("android:name=\".ui.MirrorActivity\"").substringBefore("/>")
        assertTrue("Android's PiP playback-owner contract requires singleTask for new requests",
            mirror.contains("android:launchMode=\"singleTask\""))
        assertFalse(mirror.contains("android:launchMode=\"singleTop\""))
    }

    private fun blocked(
        supported: Boolean = true,
        camera: Boolean = true,
        connected: Boolean = true,
        width: Int = 1280,
        height: Int = 720,
        surfaceReady: Boolean = true,
        busy: Boolean = false,
        foreground: Boolean = true,
    ) = CameraPipPolicy.blocked(supported, camera, connected, width, height, surfaceReady, busy, foreground)
}
