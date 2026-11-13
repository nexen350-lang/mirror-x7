package com.scrcpy.remote

import com.scrcpy.remote.data.DeviceShortcuts
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.TouchCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceShortcutsTest {

    private fun device(name: String, favorite: Boolean = false, host: String = "10.0.0.1") =
        SavedDevice(id = name, name = name, host = host, port = 5555, isFavorite = favorite)

    @Test fun favouritesComeFirstBecauseThatIsWhatMarkingOneMeans() {
        val devices = listOf(
            device("Plain A"),
            device("Starred A", favorite = true),
            device("Plain B"),
            device("Starred B", favorite = true),
        )
        assertEquals(
            listOf("Starred A", "Starred B", "Plain A", "Plain B"),
            DeviceShortcuts.select(devices).map { it.name },
        )
    }

    @Test fun equalDevicesKeepTheOrderTheUserPutThemIn() {
        // An unstable sort would let shortcuts swap places between launches for no visible
        // reason, which reads as the app being broken.
        val devices = (1..6).map { device("Device $it") }
        assertEquals(
            listOf("Device 1", "Device 2", "Device 3", "Device 4"),
            DeviceShortcuts.select(devices).map { it.name },
        )
    }

    @Test fun onlyAsManyAsALauncherWillActuallyShow() {
        val devices = (1..20).map { device("Device $it", favorite = it % 2 == 0) }
        val selected = DeviceShortcuts.select(devices)
        assertEquals(DeviceShortcuts.MAX, selected.size)
        assertTrue("Favourites fill the list first", selected.all { it.isFavorite })
    }

    @Test fun fewerDevicesThanTheLimitAreAllOffered() {
        val devices = listOf(device("Only one"))
        assertEquals(listOf("Only one"), DeviceShortcuts.select(devices).map { it.name })
        assertEquals(emptyList<SavedDevice>(), DeviceShortcuts.select(emptyList()))
    }

    @Test fun aDeviceSavedWithoutANameStillGetsAReadableLabel() {
        // The name is optional in the editor, so the address is the fallback rather than a blank
        // shortcut the user cannot tell apart from any other.
        val unnamed = SavedDevice(name = "   ", host = "192.168.1.20", port = 5555)
        assertEquals("192.168.1.20", DeviceShortcuts.shortLabel(unnamed))
        assertTrue(DeviceShortcuts.longLabel(unnamed).contains("192.168.1.20:5555"))
    }

    @Test fun theLongLabelDistinguishesTwoDevicesSharingAName() {
        val first = device("Phone", host = "10.0.0.1")
        val second = device("Phone", host = "10.0.0.2")
        assertEquals("Phone", DeviceShortcuts.shortLabel(first))
        assertTrue(DeviceShortcuts.longLabel(first) != DeviceShortcuts.longLabel(second))
    }

    @Test fun calibrationChangesDoNotCountAsAShortcutChange() {
        // Touch calibration is written onto the saved device during a session, so the device list
        // emits repeatedly while the user nudges an offset. None of that is visible in the
        // launcher, and republishing each time would spend its rate limit on identical shortcuts.
        val before = listOf(device("Phone", favorite = true), device("Tablet"))
        val after = listOf(
            before[0].copy(portraitCalibration = TouchCalibration(7, -3)),
            before[1].copy(landscapeCalibration = TouchCalibration(0, 12)),
        )
        assertEquals(DeviceShortcuts.signature(before), DeviceShortcuts.signature(after))
    }

    @Test fun theThingsALauncherShowsDoCountAsAChange() {
        val base = listOf(device("Phone"), device("Tablet"))
        for (changed in listOf(
            listOf(base[0].copy(name = "Renamed"), base[1]),
            listOf(base[0].copy(host = "10.9.9.9"), base[1]),
            listOf(base[0].copy(port = 5556), base[1]),
            listOf(base[0], base[1].copy(isFavorite = true)),
            listOf(base[0]),
        )) {
            assertTrue(
                changed.toString(),
                DeviceShortcuts.signature(base) != DeviceShortcuts.signature(changed),
            )
        }
    }
}
