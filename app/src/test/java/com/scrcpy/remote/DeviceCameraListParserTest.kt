package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.CameraFacing
import com.scrcpy.remote.scrcpy.DeviceCameraListParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeviceCameraListParserTest {

    /**
     * Captured verbatim from scrcpy 4.1 on an Android 16 device, not written from documentation. The listing code is the same in 5.0.
     * Two earlier fixtures in this project described their format correctly and their bytes
     * incorrectly, and both were found only when something failed on a real device.
     */
    private val captured = """
        [server] INFO: Device: [Google] google sdk_gphone64_x86_64 (Android 16)
        [server] INFO: List of cameras:
            --camera-id=1    (back, 1856x1392, fps={15, 30}, zoom-range=[1, 10])
    """.trimIndent()

    @Test fun theCapturedDeviceListingIsReadExactly() {
        val cameras = DeviceCameraListParser.parseServerOutput(captured)
        assertEquals(1, cameras.size)
        val camera = cameras.single()
        assertEquals("1", camera.id)
        assertEquals(CameraFacing.BACK, camera.facing)
        assertEquals(1856, camera.width)
        assertEquals(1392, camera.height)
        assertEquals(listOf(15, 30), camera.frameRates)
        assertEquals(10f, camera.maxZoom, 0.001f)
        assertTrue(camera.canZoom)
        assertTrue(camera.sizeKnown)
    }

    @Test fun severalCamerasAreReadIndependentlyAndKeepTheirOrder() {
        val output = """
            List of cameras:
                --camera-id=0    (front, 1280x960, fps={30}, zoom-range=[1, 1])
                --camera-id=1    (back, 1856x1392, fps={15, 30, 60}, zoom-range=[1, 8])
                --camera-id=2    (external, 640x480, fps={30}, zoom-range=[1, 1])
        """.trimIndent()
        val cameras = DeviceCameraListParser.parse(output)
        assertEquals(listOf("0", "1", "2"), cameras.map { it.id })
        assertEquals(
            listOf(CameraFacing.FRONT, CameraFacing.BACK, CameraFacing.EXTERNAL),
            cameras.map { it.facing },
        )
        assertEquals(listOf(15, 30, 60), cameras[1].frameRates)
        // A camera that cannot zoom must say so, or the viewfinder offers a control that does
        // nothing and looks broken.
        assertFalse(cameras[0].canZoom)
        assertTrue(cameras[1].canZoom)
    }

    @Test fun aCameraWithFieldsTheDeviceWillNotAnswerForIsStillListed() {
        // The server prints a line per camera even when it could not read its characteristics —
        // its own strings include "Could not get available frame rates for camera". Dropping such
        // a camera would hide one the user can actually select.
        val output = """
            List of cameras:
                --camera-id=7
                --camera-id=8    (front)
                --camera-id=9    (back, 1920x1080)
        """.trimIndent()
        val cameras = DeviceCameraListParser.parse(output)
        assertEquals(3, cameras.size)
        assertEquals(null, cameras[0].facing)
        assertFalse(cameras[0].sizeKnown)
        assertFalse(cameras[0].canZoom)
        assertEquals(CameraFacing.FRONT, cameras[1].facing)
        assertFalse(cameras[1].sizeKnown)
        assertEquals(1920, cameras[2].width)
        assertTrue(cameras[2].frameRates.isEmpty())
    }

    @Test fun nonNumericAndRepeatedIdsAreHandledWithoutLosingRealCameras() {
        // Camera ids are strings on Android, not indices: external and logical cameras use names.
        val output = """
            List of cameras:
                --camera-id=2    (back, 800x600, fps={30}, zoom-range=[1, 2])
                --camera-id=2    (front, 100x100)
                --camera-id=usb-0001    (external, 640x480)
        """.trimIndent()
        val cameras = DeviceCameraListParser.parse(output)
        assertEquals(listOf("2", "usb-0001"), cameras.map { it.id })
        assertEquals("The first description of an id wins", 800, cameras[0].width)
    }

    @Test fun aDeviceTooOldForCameraCaptureSaysSoRatherThanAppearingToHaveNone() {
        // scrcpy refuses camera capture before Android 12. Reporting "no cameras" would send the
        // user hunting for a device fault that does not exist.
        val output = "[server] ERROR: Camera mirroring is not supported before Android 12"
        val failure = assertThrows(IOException::class.java) {
            DeviceCameraListParser.parseServerOutput(output)
        }
        assertTrue(failure.message, failure.message!!.contains("not supported before Android 12"))
    }

    @Test fun outputThatNeverReachedTheListingIsAFailureNotAnEmptyList() {
        val failure = assertThrows(IOException::class.java) {
            DeviceCameraListParser.parseServerOutput("java.lang.RuntimeException: boom")
        }
        assertTrue(failure.message, failure.message!!.contains("did not return its cameras"))
        // A real listing with nothing in it is a success, not a failure.
        assertTrue(DeviceCameraListParser.parseServerOutput("List of cameras:\n    (none)").isEmpty())
    }

    /**
     * Captured verbatim from `list_camera_sizes=true` on the same Android 16 device. The listing
     * reuses the `List of cameras:` header and adds an indented size line per supported size, so
     * a parser that only looks for `--camera-id=` reads this as a camera with no sizes at all.
     */
    private val capturedSizes = """
        [server] INFO: Device: [Google] google sdk_gphone64_x86_64 (Android 16)
        [server] INFO: List of cameras:
            --camera-id=1    (back, 1856x1392, fps={15, 30}, zoom-range=[1, 10])
                - 160x120
                - 320x240
                - 640x480
                - 1280x720
                - 1856x1392
                - 176x144
                - 1024x768
    """.trimIndent()

    @Test fun theCapturedSizeListingIsReadExactly() {
        val camera = DeviceCameraListParser.parseServerOutput(capturedSizes).single()
        assertEquals("1", camera.id)
        assertTrue("sizes must be read from the listing", camera.sizesKnown)
        assertEquals(
            listOf("160x120", "320x240", "640x480", "1280x720", "1856x1392", "176x144", "1024x768"),
            camera.sizes.map { it.serverValue },
        )
        // The device prints them unsorted; the order it gave is kept so a picker can decide.
        assertTrue(camera.highSpeedSizes.isEmpty())
    }

    @Test fun highSpeedSizesAreKeptApartFromOrdinaryOnes() {
        // The server prints a separate section, because a high-speed size is only valid together
        // with camera_high_speed and pairing one with an ordinary frame rate is refused.
        val output = """
            List of cameras:
                --camera-id=0    (back, 4000x3000, fps={30}, zoom-range=[1, 10])
                    - 4000x3000
                    - 1920x1080
                  High speed capture (--camera-high-speed):
                    - 1280x720 (fps={120, 240})
                    - 1920x1080 (fps={60, 120})
                --camera-id=1    (front, 1280x960, fps={30}, zoom-range=[1, 1])
                    - 1280x960
        """.trimIndent()
        val cameras = DeviceCameraListParser.parse(output)
        assertEquals(2, cameras.size)
        assertEquals(listOf("4000x3000", "1920x1080"), cameras[0].sizes.map { it.serverValue })
        assertEquals(
            listOf("1280x720", "1920x1080"),
            cameras[0].highSpeedSizes.map { it.serverValue },
        )
        assertEquals(listOf(120, 240), cameras[0].highSpeedSizes[0].frameRates)
        assertEquals(listOf(60, 120), cameras[0].highSpeedSizes[1].frameRates)
        // The section must not leak into the next camera.
        assertEquals(listOf("1280x960"), cameras[1].sizes.map { it.serverValue })
        assertTrue(cameras[1].highSpeedSizes.isEmpty())
    }

    @Test fun aPlainCameraListingReportsSizesAsUnknownRatherThanAsNone() {
        // list_cameras prints no size lines at all. Treating that as "offers no sizes" would make
        // a picker show an empty list instead of falling back to letting the device choose.
        val camera = DeviceCameraListParser.parseServerOutput(captured).single()
        assertFalse(camera.sizesKnown)
        assertTrue(camera.sizes.isEmpty())
    }
}
