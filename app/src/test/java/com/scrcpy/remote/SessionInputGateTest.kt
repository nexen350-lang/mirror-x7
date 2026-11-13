package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.ControlMessage
import com.scrcpy.remote.session.SessionInputGate
import org.junit.Assert.*
import org.junit.Test

class SessionInputGateTest {
    @Test fun unlockedInputRetainsItsExactMessageOrderAndMetadata() {
        val sent = mutableListOf<ControlMessage>()
        val gate = SessionInputGate { sent += it; true }
        val messages = listOf(
            ControlMessage.InjectKeycode(0, 29, 0, 4096),
            ControlMessage.InjectKeycode(0, 29, 3, 4096),
            ControlMessage.InjectKeycode(1, 29, 0, 4096),
            touch(0, 7), touch(2, 7), touch(1, 7),
            ControlMessage.InjectScrollEvent(5, 8, 640, 360, -1f, 2f),
            ControlMessage.SetClipboard(2, "Composed text", true),
        )
        messages.forEach { assertTrue(gate.send(it)) }
        assertEquals(messages, sent)
    }

    @Test fun everyInteractiveMessageIsBlockedIncludingDirectCallbacksAndUnknownEmptyMessages() {
        val sent = mutableListOf<ControlMessage>()
        val gate = SessionInputGate(initiallyLocked = true) { sent += it; true }
        val blocked = listOf(
            ControlMessage.InjectKeycode(0, 29), ControlMessage.InjectKeycode(1, 29),
            ControlMessage.InjectText("private"), touch(0, 1), touch(2, 1), touch(1, 1),
            ControlMessage.InjectScrollEvent(5, 8, 640, 360, 1f, 1f),
            ControlMessage.BackOrScreenOn(0), ControlMessage.BackOrScreenOn(1),
            ControlMessage.SetClipboard(1, "private", true),
            ControlMessage.GetClipboard(ControlMessage.COPY_KEY_COPY),
            ControlMessage.GetClipboard(ControlMessage.COPY_KEY_CUT),
            ControlMessage.SetDisplayPower(false), ControlMessage.SetDisplayPower(true),
            ControlMessage.StartApp("+com.example.app"), ControlMessage.CameraSetTorch(true),
            ControlMessage.CameraSetTorch(false), ControlMessage.expandNotificationPanel(),
            ControlMessage.expandSettingsPanel(), ControlMessage.collapsePanels(),
            ControlMessage.rotateDevice(), ControlMessage.openHardKeyboardSettings(),
            ControlMessage.cameraZoomIn(), ControlMessage.cameraZoomOut(), ControlMessage.Empty(255),
        )
        blocked.forEach { assertTrue(gate.blocks(it)); assertFalse("$it", gate.send(it)) }
        assertTrue(sent.isEmpty())
    }

    @Test fun streamMaintenanceFileScanAndPassiveClipboardAreSeparateCapabilities() {
        val sent = mutableListOf<ControlMessage>()
        val gate = SessionInputGate(initiallyLocked = true) { sent += it; true }
        val allowed = listOf(
            ControlMessage.resetVideo(), ControlMessage.ResizeDisplay(800, 600),
            ControlMessage.ScanFile("/sdcard/Download/file.txt"),
            ControlMessage.GetClipboard(ControlMessage.COPY_KEY_NONE),
            ControlMessage.SetClipboard(1, "sync without paste", false),
        )
        allowed.forEach { assertFalse(gate.blocks(it)); assertTrue(gate.send(it)) }
        assertEquals(allowed, sent)
        assertTrue(gate.state.locked)
    }

    @Test fun engagingLockReleasesEveryHeldInputOnceBeforeBlockingNewEvents() {
        val sent = mutableListOf<ControlMessage>()
        val gate = SessionInputGate { sent += it; true }
        gate.send(ControlMessage.InjectKeycode(0, 113, 0, 4096))
        gate.send(ControlMessage.InjectKeycode(0, 29, 0, 4096))
        gate.send(touch(0, 7))
        gate.send(touch(0, 19))
        sent.clear()
        gate.setLocked(true, 640, 360)
        assertEquals(listOf(ControlMessage.InjectKeycode(1, 113), ControlMessage.InjectKeycode(1, 29),
            touch(1, 7).copy(pressure = 0f), touch(1, 19).copy(pressure = 0f)), sent)
        gate.setLocked(true, 640, 360)
        gate.releaseKeys()
        gate.releasePointers(640, 360)
        assertEquals(4, sent.size)
        assertTrue(gate.state.locked)
        assertFalse(gate.send(ControlMessage.InjectKeycode(0, 29, 4)))
        assertFalse(gate.send(touch(2, 7)))
    }

    @Test fun lockingWithNoUsableSizeKeepsPointersForALaterRelease() {
        val sent = mutableListOf<ControlMessage>()
        val gate = SessionInputGate { sent += it; true }
        gate.send(touch(0, 7))
        sent.clear()
        gate.setLocked(true, 0, 0)
        assertTrue(sent.isEmpty())
        gate.releasePointers(640, 360)
        assertEquals(listOf(touch(1, 7).copy(pressure = 0f)), sent)
        sent.clear()
        gate.releasePointers(640, 360)
        assertTrue(sent.isEmpty())
    }

    @Test fun unlockingRequiresFreshDownsAndRejectsCallbacksFromEarlierLockGenerations() {
        val sent = mutableListOf<ControlMessage>()
        val gate = SessionInputGate { sent += it; true }
        gate.send(ControlMessage.InjectKeycode(0, 29))
        gate.send(touch(0, 7))
        val before = gate.state.generation
        gate.setLocked(true, 640, 360)
        gate.setLocked(false, 640, 360)
        sent.clear()
        assertEquals(before + 2, gate.state.generation)
        assertFalse(gate.send(ControlMessage.InjectKeycode(0, 29, 2)))
        assertFalse(gate.send(ControlMessage.InjectKeycode(1, 29)))
        assertFalse(gate.send(touch(2, 7)))
        assertFalse(gate.send(touch(1, 7)))
        assertFalse(gate.send(touch(0, 7), before))
        assertFalse(gate.send(ControlMessage.InjectScrollEvent(1, 2, 640, 360, 1f, 0f), before))
        assertTrue(sent.isEmpty())
        assertTrue(gate.send(touch(0, 7), gate.state.generation))
        assertTrue(gate.send(ControlMessage.InjectKeycode(0, 29)))
    }

    @Test fun captureChangesStillReleaseHistoricalPointersWhileLocked() {
        val sent = mutableListOf<ControlMessage>()
        val gate = SessionInputGate { sent += it; true }
        gate.send(touch(0, 3).copy(x = 639, y = 359))
        gate.setLocked(true, 640, 360)
        sent.clear()
        gate.releasePointers(320, 200, includeHistory = true)
        assertEquals(listOf(ControlMessage.InjectTouchEvent(1, 3, 319, 199, 320, 200, 0f)), sent)
        assertTrue(gate.state.locked)
    }

    @Test fun aCaptureChangeLiftsAPointerAtItsPlaceOnThePictureRatherThanAtAnEdge() {
        val sent = mutableListOf<ControlMessage>()
        val gate = SessionInputGate { sent += it; true }
        gate.send(ControlMessage.InjectTouchEvent(0, -2, 540, 1800, 1080, 2400, 1f))
        sent.clear()
        // Rotated: three quarters down the old picture is three quarters down the new one, where
        // the pad's cursor goes too, not the bottom edge that clamping (540, 1079) would give.
        gate.releasePointers(2400, 1080, includeHistory = true)
        assertEquals(listOf(ControlMessage.InjectTouchEvent(1, -2, 1201, 810, 2400, 1080, 0f)), sent)
        // Scaled down, it is the same content, not the far corner (539, 1199).
        sent.clear()
        gate.releasePointers(540, 1200, includeHistory = true)
        assertEquals(listOf(ControlMessage.InjectTouchEvent(1, -2, 270, 900, 540, 1200, 0f)), sent)
    }

    @Test fun failedSendsAreNeverRegisteredAsHeldInput() {
        var accepts = false
        val sent = mutableListOf<ControlMessage>()
        val gate = SessionInputGate { if (accepts) sent += it; accepts }
        assertFalse(gate.send(touch(0, 3)))
        assertFalse(gate.send(ControlMessage.InjectKeycode(0, 29)))
        accepts = true
        gate.setLocked(true, 640, 360)
        gate.releasePointers(640, 360, includeHistory = true)
        assertTrue(sent.isEmpty())
    }

    @Test fun reentrantFailureCleanupCannotDuplicateHeldReleases() {
        val sent = mutableListOf<ControlMessage>()
        lateinit var gate: SessionInputGate
        gate = SessionInputGate { message ->
            sent += message
            if (message is ControlMessage.InjectKeycode && message.action == 1) {
                gate.releaseKeys()
                gate.releasePointers(640, 360)
                gate.clear()
            }
            true
        }
        gate.send(ControlMessage.InjectKeycode(0, 29))
        gate.send(touch(0, 3))
        sent.clear()
        gate.setLocked(true, 640, 360)
        assertEquals(listOf(ControlMessage.InjectKeycode(1, 29), touch(1, 3).copy(pressure = 0f)), sent)
    }

    @Test fun clearingATransportNeverUnlocksAndPreventsHistoryLeakingIntoItsReplacement() {
        val sent = mutableListOf<ControlMessage>()
        val gate = SessionInputGate { sent += it; true }
        gate.send(touch(0, 3))
        gate.setLocked(true, 640, 360)
        gate.clear()
        sent.clear()
        gate.releasePointers(800, 600, includeHistory = true)
        assertTrue(sent.isEmpty())
        assertTrue(gate.state.locked)
        gate.setLocked(false, 800, 600)
        assertFalse(gate.send(touch(2, 3)))
    }

    private fun touch(action: Int, id: Long) =
        ControlMessage.InjectTouchEvent(action, id, 30, 40, 640, 360, 1f)
}
