package com.scrcpy.remote.session

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A shared file's read grant belongs to the activity that received the intent, so the bytes are
 * copied into this app's cache immediately. These cover that copy and its cleanup; the grant
 * itself is the platform's part of the contract and cannot be issued from a test harness.
 */
@RunWith(AndroidJUnit4::class)
class ShareCacheTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Before as well as after: a test elsewhere that failed part way can leave a copy behind, and
    // these assertions count what is in the directory.
    @Before @After fun cleanUp() {
        TestShareProvider.clear(context)
        File(context.cacheDir, "shared-uploads").listFiles()?.forEach { it.delete() }
    }

    private fun source(name: String, content: ByteArray): Uri = TestShareProvider.offer(context, name, content)

    @Test fun aFileUriFromAnotherAppIsRefusedRatherThanReadWithOurOwnPermissions() {
        // Any app can send ACTION_SEND, and openInputStream resolves a file:// URI using *this*
        // app's permissions. Without the scheme check, a malicious app could name a path inside
        // this app's private storage — the ADB private key — and have it copied out and uploaded
        // to a device. The sender's own StrictMode is not an authorization boundary.
        val privateFile = File(context.filesDir, "adb-key-stand-in").apply { writeBytes(ByteArray(32)) }
        assertTrue("Readable by this process", privateFile.canRead())

        assertNull(ShareCache.copyIn(context, Uri.fromFile(privateFile)))
        val leftovers = File(context.cacheDir, "shared-uploads").listFiles()?.size ?: 0
        assertEquals("Nothing may be staged from a refused share", 0, leftovers)
        privateFile.delete()
    }

    @Test fun onlyContentUrisAreAcceptedFromAShare() {
        assertTrue(ShareCache.isShareable(context, Uri.parse("content://com.example.provider/doc/1")))
        for (rejected in listOf(
            "file:///data/user/0/com.scrcpy.remote/files/adb/adbkey",
            "file:///sdcard/Download/x.bin",
            "http://example.com/x.bin",
            "android.resource://com.example/1",
            "/data/local/tmp/x",
        )) {
            assertFalse(rejected, ShareCache.isShareable(context, Uri.parse(rejected)))
        }
    }

    @Test fun anOversizedShareIsStoppedPartWayRatherThanAfterItHasLanded() {
        // The sender decides how many bytes to serve. Checking the length after the copy would
        // mean the whole thing had to reach the cache partition first.
        val uri = source("huge.bin", ByteArray(400_000))
        assertNull(ShareCache.copyIn(context, uri, maxBytes = 64_000))
        val leftovers = File(context.cacheDir, "shared-uploads").listFiles()?.size ?: 0
        assertEquals("An oversized copy must not be left behind", 0, leftovers)

        // The same file is fine once it fits.
        assertNotNull(ShareCache.copyIn(context, uri, maxBytes = 400_000))
    }

    @Test fun anEarlierShareIsNotDeletedByALaterOneThatFails() {
        // The directory used to be emptied on every incoming share, on the reasoning that only
        // one can be pending. A second share that turns out to be unreadable would then have
        // deleted the first, leaving the app offering a file that no longer existed.
        val first = ShareCache.copyIn(context, source("first.bin", ByteArray(32)))
        assertNotNull(first)
        assertNull(ShareCache.copyIn(context, TestShareProvider.uriFor("missing.bin")))
        assertTrue("The earlier copy must survive", File(first!!.path).exists())
    }

    @Test fun twoLiveSharesBothKeepTheirOwnCopy() {
        // A copy can be waiting for a destination while the next one is still being written.
        val first = ShareCache.copyIn(context, source("a.bin", ByteArray(8) { 1 }))
        val second = ShareCache.copyIn(context, source("b.bin", ByteArray(8) { 2 }))
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(File(first!!.path).exists())
        assertTrue(File(second!!.path).exists())
        assertArrayEquals(ByteArray(8) { 1 }, File(first.path).readBytes())
        assertArrayEquals(ByteArray(8) { 2 }, File(second.path).readBytes())
    }

    @Test fun theCopyHasExactlyTheSharedBytes() {
        val content = ByteArray(300_000) { (it % 251).toByte() }
        val copied = ShareCache.copyIn(context, source("payload.bin", content))
        assertNotNull(copied)
        assertArrayEquals(content, File(copied!!.path).readBytes())
    }

    @Test fun theCopyKeepsTheNameTheSharingAppUsed() {
        // The cache file is a UUID, so the original name has to be carried separately or the
        // device would receive a file called something meaningless.
        val copied = ShareCache.copyIn(context, source("Quarterly report.pdf", ByteArray(16)))
        assertEquals("Quarterly report.pdf", copied?.name)
    }

    @Test fun anUnreadableShareIsReportedRatherThanPartlyCopied() {
        val missing = TestShareProvider.uriFor("does-not-exist.bin")
        assertNull(ShareCache.copyIn(context, missing))
        val leftovers = File(context.cacheDir, "shared-uploads").listFiles()?.size ?: 0
        assertEquals("A failed copy must not be left behind", 0, leftovers)
    }

    @Test fun anAbandonedCopyIsCleanedUpOnceItHasAgedOut() {
        // Abandoned copies still have to go, or a process that died between taking one and
        // sending it would leave the file behind for the life of the install. They are removed
        // by age rather than by wiping the directory, which would take live copies with them.
        val stale = File(File(context.cacheDir, "shared-uploads").apply { mkdirs() }, "stale")
        stale.writeBytes(ByteArray(4))
        assertTrue(stale.setLastModified(System.currentTimeMillis() - 7L * 60 * 60 * 1000))
        val fresh = ShareCache.copyIn(context, source("fresh.bin", ByteArray(8)))
        assertNotNull(fresh)
        assertFalse("The aged-out copy should have been swept", stale.exists())
        assertTrue(File(fresh!!.path).exists())
    }

    @Test fun discardingRemovesTheCopyAndToleratesBeingCalledTwice() {
        val copied = ShareCache.copyIn(context, source("payload.bin", ByteArray(8)))
        ShareCache.discard(copied!!.path)
        assertFalse(File(copied.path).exists())
        ShareCache.discard(copied.path)
        ShareCache.discard(null)
    }
}
