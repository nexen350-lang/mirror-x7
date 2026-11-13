package com.scrcpy.remote.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a restored device list should say that a share was lost with the previous process. */
class ShareCopyLostTest {
    @Test fun onlyACopyThatWasRunningAndLeftNothingBehindWasLost() {
        assertTrue("A new process has no copy and no result",
            shareCopyWasLost(copyingWhenSaved = true, copyingNow = false, resultWaiting = false, pendingShare = null))
        assertFalse("A rotation keeps the copy running",
            shareCopyWasLost(copyingWhenSaved = true, copyingNow = true, resultWaiting = false, pendingShare = null))
        assertFalse("The copy landed during the recreation and waits to be taken",
            shareCopyWasLost(copyingWhenSaved = true, copyingNow = false, resultWaiting = true, pendingShare = null))
        assertFalse("A file was already taken",
            shareCopyWasLost(copyingWhenSaved = true, copyingNow = false, resultWaiting = false, pendingShare = "/cache/a"))
        assertFalse("Nothing was copying when the list was saved",
            shareCopyWasLost(copyingWhenSaved = false, copyingNow = false, resultWaiting = false, pendingShare = null))
    }
}
