package com.scrcpy.remote

import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.session.MirrorViewModel
import com.scrcpy.remote.ui.screenshot.ArtefactSharing
import com.scrcpy.remote.ui.screenshot.ScreenshotCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the outbound file provider will and will not hand to another app.
 *
 * This is the test the feature plan asked for before sharing was allowed to exist. The cache also
 * holds files shared *into* this app on their way to the remote device, so a provider scoped to
 * the cache root would let an app that received one screenshot go looking for those too.
 */
@RunWith(AndroidJUnit4::class)
class ArtefactSharingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun cacheFile(directory: String, name: String): File {
        val parent = File(context.cacheDir, directory)
        parent.mkdirs()
        return File(parent, name).apply { writeText("x") }
    }

    @Test fun aScreenshotAndARecordingCanBeShared() {
        val shot = cacheFile(ScreenshotCache.DIRECTORY, "abc.png")
        val clip = cacheFile(MirrorViewModel.RECORDING_DIRECTORY, "session-1.mp4")

        assertTrue(ArtefactSharing.isShareable(context, shot))
        assertTrue(ArtefactSharing.isShareable(context, clip))
        assertNotNull(ArtefactSharing.uriFor(context, shot))
        assertNotNull(ArtefactSharing.uriFor(context, clip))
    }

    @Test fun nothingElseInTheCacheCanBeShared() {
        // The inbound share cache is the one that matters: it holds a file another app handed us
        // for the remote device, and it must not be reachable through an outbound grant.
        val inbound = cacheFile("shared", "someone-elses-document.pdf")
        val loose = File(context.cacheDir, "loose.txt").apply { writeText("x") }
        val nested = cacheFile("screenshots/deeper", "nested.png")

        assertFalse(ArtefactSharing.isShareable(context, inbound))
        assertFalse(ArtefactSharing.isShareable(context, loose))
        assertFalse("only files directly in the artefact directories", ArtefactSharing.isShareable(context, nested))
        assertNull(ArtefactSharing.uriFor(context, inbound))
        assertNull(ArtefactSharing.uriFor(context, loose))
    }

    @Test fun aTraversingNameCannotPresentAnotherFileAsAnArtefact() {
        // Canonicalisation is what stops this; without it the parent directory reads as
        // "screenshots" and the file resolves somewhere else entirely.
        //
        // The real file is written at its true location first — writing *through* the traversing
        // path depends on both directories already existing, which made this fail during setup
        // and never reach the assertion.
        File(context.cacheDir, ScreenshotCache.DIRECTORY).mkdirs()
        val real = cacheFile("shared", "secret.pdf")
        val escaped = File(context.cacheDir, "${ScreenshotCache.DIRECTORY}/../shared/secret.pdf")

        assertTrue("the traversing path must reach the same file", escaped.canonicalFile == real.canonicalFile)
        assertTrue(escaped.exists())
        assertFalse(ArtefactSharing.isShareable(context, escaped))
        assertNull(ArtefactSharing.uriFor(context, escaped))
    }

    @Test fun theProviderAuthorityMatchesTheManifest() {
        // A mismatch throws only at the moment a user tries to share, which is the worst time to
        // find out. The provider is resolved here instead.
        val authority = ArtefactSharing.authority(context)
        val info = context.packageManager.resolveContentProvider(authority, 0)
        assertNotNull("no provider is registered for $authority", info)
        assertEquals(context.packageName, info!!.packageName)
        assertFalse("the artefact provider must not be exported", info.exported)
        assertTrue("it must be able to grant URI permissions", info.grantUriPermissions)
    }

    @Test fun theSharedDirectoriesAreTheOnesTheArtefactsActuallyUse() {
        // Pins the set against the two constants that decide where files are written, so renaming
        // either one without updating shared_artefacts.xml fails here rather than in the field.
        assertEquals(
            setOf(ScreenshotCache.DIRECTORY, MirrorViewModel.RECORDING_DIRECTORY),
            ArtefactSharing.SHAREABLE_DIRECTORIES,
        )
    }

    @Test fun aChooserCarriesTheGrantAndTheUriInBothPlaces() {
        val shot = cacheFile(ScreenshotCache.DIRECTORY, "grant.png")
        val chooser = ArtefactSharing.chooserFor(context, shot, ArtefactSharing.MIME_SCREENSHOT)
        assertNotNull(chooser)

        val send = IntentCompat.getParcelableExtra(
            chooser!!, android.content.Intent.EXTRA_INTENT, android.content.Intent::class.java)
        assertNotNull("the chooser must wrap a send intent", send)
        assertEquals(ArtefactSharing.MIME_SCREENSHOT, send!!.type)
        assertTrue(
            "a receiver that never gets the grant cannot open the file",
            send.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        val stream = IntentCompat.getParcelableExtra(
            send, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
        assertNotNull(stream)
        // Several receivers read the clip rather than the extra; both must carry it.
        assertEquals(stream, send.clipData?.getItemAt(0)?.uri)
    }

    @Test fun aFileThatIsNotOursYieldsNoChooserRatherThanAFailingOne() {
        val loose = File(context.cacheDir, "not-an-artefact.bin").apply { writeText("x") }
        assertNull(ArtefactSharing.chooserFor(context, loose, ArtefactSharing.MIME_SCREENSHOT))
    }
}
