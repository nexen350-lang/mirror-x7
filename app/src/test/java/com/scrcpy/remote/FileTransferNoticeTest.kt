package com.scrcpy.remote

import com.scrcpy.remote.adb.AdbSync
import com.scrcpy.remote.session.FileTransferState
import com.scrcpy.remote.session.FileTransfers
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * What the user is told about a transfer that did not finish cleanly. Saying "it is not on the
 * device" is only true until DONE is sent: adbd discards a partial file, but once it has read DONE
 * it may already have saved the file and only its confirmation was lost.
 */
class FileTransferNoticeTest {
    private fun cutOff(sent: Long, total: Long, committing: Boolean) =
        FileTransfers.interruptedNotice(FileTransferState(running = true, name = "a.bin",
            sentBytes = sent, totalBytes = total, committing = committing))

    @Test fun onlyATransferThatReachedItsCommitIsUnconfirmed() {
        assertEquals(R.string.send_file_unconfirmed, cutOff(10, 10, committing = true))
        assertEquals("Every byte sent but no DONE: the device discards the partial file",
            R.string.send_file_interrupted, cutOff(10, 10, committing = false))
        assertEquals("An empty file is committed by DONE alone",
            R.string.send_file_unconfirmed, cutOff(0, 0, committing = true))
        assertEquals(R.string.send_file_interrupted, cutOff(9, 10, committing = false))
        assertEquals("A transfer that never started its commit", R.string.send_file_interrupted,
            FileTransfers.interruptedNotice(FileTransferState(running = true, name = "a.bin")))
    }

    @Test fun anInstallCutOffWhileTheDeviceInstallsMayHaveFinished() {
        // Once the package manager has the file, only the device knows whether it got to the end.
        assertEquals(R.string.install_interrupted_unconfirmed, FileTransfers.interruptedNotice(
            FileTransferState(running = true, name = "a.apk", sentBytes = 4, totalBytes = 4,
                committing = true, install = true, installing = true)))
    }

    @Test fun anInstallCutOffBeforeTheDeviceStartedInstallingDidNotInstall() {
        // Even a fully copied package is only a file in a temporary folder until it is installed.
        assertEquals(R.string.install_interrupted, FileTransfers.interruptedNotice(
            FileTransferState(running = true, name = "a.apk", sentBytes = 4, totalBytes = 4,
                committing = true, install = true)))
        assertEquals(R.string.install_interrupted, FileTransfers.interruptedNotice(
            FileTransferState(running = true, name = "a.apk", sentBytes = 1, totalBytes = 4, install = true)))
    }

    @Test fun aLostConfirmationIsNotReportedAsAFailedSend() {
        assertEquals(R.string.send_file_unconfirmed,
            FileTransfers.failureNotice(AdbSync.SyncCommitUnconfirmedException(IOException("Stream closed"))))
        assertEquals(R.string.send_file_unconfirmed, FileTransfers.failureNotice(
            IOException("wrapped", AdbSync.SyncCommitUnconfirmedException(IOException()))))
        assertEquals(R.string.send_file_failed, FileTransfers.failureNotice(IOException("adb push failed: disk full")))
    }
}
