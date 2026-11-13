package com.scrcpy.remote

import com.scrcpy.remote.session.FileTransfers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileSizeChoiceTest {
    @Test fun positiveQueriedSizeWins() =
        assertEquals(5L, FileTransfers.chooseSize(5L) { 9L })

    @Test fun zeroOrNegativeFallsBackToDescriptor() {
        assertEquals(9L, FileTransfers.chooseSize(0L) { 9L })
        assertEquals(9L, FileTransfers.chooseSize(-1L) { 9L })
        assertEquals(9L, FileTransfers.chooseSize(null) { 9L })
    }

    @Test fun zeroKeptWhenDescriptorUnknown() {
        assertEquals(0L, FileTransfers.chooseSize(0L) { null })
        assertEquals(-1L, FileTransfers.chooseSize(-1L) { null })
        assertNull(FileTransfers.chooseSize(null) { null })
    }
}
