package com.scrcpy.remote

import com.scrcpy.remote.data.VirtualDisplaySpec
import com.scrcpy.remote.session.DesktopResizeState
import com.scrcpy.remote.session.DesktopSize
import com.scrcpy.remote.session.DesktopViewport
import com.scrcpy.remote.session.canChooseSessionDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveDesktopTest {
    private val portrait = DesktopSize(720, 1600)
    private val landscape = DesktopSize(1600, 720)

    @Test fun sizingUsesTheViewportAspectAndHonorsTheStreamingLimit() {
        assertEquals(portrait, DesktopSize.fit(1080, 2400, 1600))
        assertEquals(landscape, DesktopSize.fit(2400, 1080, 1600))
        assertEquals(DesktopSize(448, 1000), DesktopSize.fit(1080, 2400, 1000))
        assertEquals(DesktopSize(640, 480), DesktopSize.fit(640, 480, 1600))
    }

    @Test fun everyGeneratedSizeIsAlignedAndAcceptedByTheExistingSpecValidator() {
        for (width in listOf(1, 159, 321, 1080, 2560, Int.MAX_VALUE)) {
            for (height in listOf(1, 161, 719, 2400, Int.MAX_VALUE)) {
                for (limit in listOf(640, 1000, 1600, 2560)) {
                    val display = DesktopViewport(width, height, 420).display(limit)
                    assertTrue(display.size.width <= limit)
                    assertTrue(display.size.height <= limit)
                    assertEquals(0, display.size.width % 8)
                    assertEquals(0, display.size.height % 8)
                    assertNull(display.spec, VirtualDisplaySpec.error(display.spec))
                }
            }
        }
    }

    @Test fun densityTracksTheInitialPixelReductionInsteadOfMakingTextTiny() {
        val display = DesktopViewport(1080, 2400, 420).display(1600)
        assertEquals("720x1600/280", display.spec)
        assertEquals(60, DesktopViewport(1080, 2400, 1).display(1600).densityDpi)
        assertEquals(1000, DesktopViewport(640, 480, 2000).display(1600).densityDpi)
    }

    @Test fun invalidDimensionsAreRejectedBeforeTheyCanReachTheWire() {
        assertThrows(IllegalArgumentException::class.java) { DesktopSize.fit(0, 1080, 1600) }
        assertThrows(IllegalArgumentException::class.java) { DesktopSize.fit(1080, -1, 1600) }
        assertThrows(IllegalArgumentException::class.java) { DesktopSize.fit(1080, 2400, 100) }
        assertThrows(IllegalArgumentException::class.java) { DesktopSize(721, 1600) }
        assertThrows(IllegalArgumentException::class.java) { DesktopViewport(1080, 2400, 0) }
    }

    @Test fun leavingAVirtualDesktopCannotSelectAnIdThatItsTeardownMayDestroy() {
        assertTrue(canChooseSessionDisplay(0, virtualDisplayActive = true))
        assertFalse(canChooseSessionDisplay(3, virtualDisplayActive = true))
        assertFalse(canChooseSessionDisplay(4096, virtualDisplayActive = true))
        assertTrue(canChooseSessionDisplay(3, virtualDisplayActive = false))
        assertTrue(canChooseSessionDisplay(4096, virtualDisplayActive = false))
        assertFalse(canChooseSessionDisplay(-1, virtualDisplayActive = false))
        assertFalse(canChooseSessionDisplay(4097, virtualDisplayActive = false))
    }

    @Test fun theInitialCaptureMustArriveBeforeRecordingOrResizing() {
        val state = DesktopResizeState()
        state.start(portrait)
        assertFalse(state.canRecord)
        assertNull(state.nextRequest(recording = false, visible = true))
        state.onCapture(720, 1600, clientResized = false)
        assertTrue(state.canRecord)
        assertNull(state.nextRequest(recording = false, visible = true))
    }

    @Test fun onlyTheLatestViewportIsSentAfterAnOutstandingResizeIsAcknowledged() {
        val state = ready()
        state.updateViewport(landscape)
        assertFalse(state.canRecord)
        assertEquals(landscape, state.nextRequest(recording = false, visible = true))
        state.updateViewport(DesktopSize(800, 800))
        state.updateViewport(DesktopSize(960, 720))
        assertNull(state.nextRequest(recording = false, visible = true))
        state.onCapture(1600, 720, clientResized = true)
        assertFalse(state.canRecord)
        assertEquals(DesktopSize(960, 720), state.nextRequest(recording = false, visible = true))
        state.onCapture(960, 720, clientResized = true)
        assertTrue(state.canRecord)
        assertNull(state.nextRequest(recording = false, visible = true))
    }

    @Test fun aViewportChangeBeforeTheFirstHeaderIsNotMistakenForTheInitialSize() {
        val state = DesktopResizeState()
        state.start(portrait)
        state.updateViewport(landscape)
        state.onCapture(720, 1600, clientResized = false)
        assertFalse(state.canRecord)
        assertEquals(landscape, state.nextRequest(recording = false, visible = true))
    }

    @Test fun exactDimensionsAcknowledgeAResizeWhenTheServerCombinesResetReasons() {
        val state = ready()
        state.updateViewport(landscape)
        state.nextRequest(recording = false, visible = true)
        state.onCapture(1600, 720, clientResized = false)
        assertNull(state.pending)
        assertTrue(state.canRecord)
    }

    @Test fun unrelatedCaptureResetsDoNotPretendTheResizeFinished() {
        val state = ready()
        state.updateViewport(landscape)
        state.nextRequest(recording = false, visible = true)
        state.onCapture(720, 1600, clientResized = false)
        assertEquals(landscape, state.pending)
        assertFalse(state.canRecord)
        state.onCapture(1600, 720, clientResized = true)
        assertTrue(state.canRecord)
    }

    @Test fun targetEncoderConstraintsDoNotCauseAnEndlessResizeLoop() {
        val state = ready()
        state.updateViewport(landscape)
        state.nextRequest(recording = false, visible = true)
        state.onCapture(1280, 576, clientResized = true)
        assertTrue(state.canRecord)
        assertNull(state.nextRequest(recording = false, visible = true))
    }

    @Test fun viewportChangesWaitUntilRecordingStopsAndOnlyTheLatestOneIsApplied() {
        val state = ready()
        state.updateViewport(landscape)
        assertNull(state.nextRequest(recording = true, visible = true))
        state.updateViewport(DesktopSize(800, 800))
        assertNull(state.nextRequest(recording = true, visible = true))
        assertEquals(DesktopSize(800, 800), state.nextRequest(recording = false, visible = true))
    }

    @Test fun aHiddenWindowCannotResizeTheRemoteDesktop() {
        val state = ready()
        state.updateViewport(landscape)
        assertNull(state.nextRequest(recording = false, visible = false))
        assertEquals(landscape, state.nextRequest(recording = false, visible = true))
    }

    @Test fun anAlreadyMatchingCaptureNeedsNoRequestOrAcknowledgement() {
        val state = ready()
        state.onCapture(1600, 720, clientResized = false)
        state.updateViewport(landscape)
        assertNull(state.nextRequest(recording = false, visible = true))
        assertTrue(state.canRecord)
    }

    @Test fun reconnectsAndOtherSourcesRetireAllOldResizeState() {
        val state = ready()
        state.updateViewport(landscape)
        state.nextRequest(recording = false, visible = true)
        state.stop()
        state.onCapture(1600, 720, clientResized = true)
        assertTrue(state.canRecord)
        assertNull(state.pending)
        assertNull(state.nextRequest(recording = false, visible = true))
        state.start(portrait)
        assertFalse(state.canRecord)
    }

    private fun ready() = DesktopResizeState().apply {
        start(portrait)
        onCapture(720, 1600, clientResized = false)
    }
}
