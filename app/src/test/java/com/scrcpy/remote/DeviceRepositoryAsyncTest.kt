package com.scrcpy.remote

import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PreferenceStorage
import com.scrcpy.remote.data.SettingsCodec
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceRepositoryAsyncTest {
    private class SlowStorage : PreferenceStorage {
        private val values = mutableMapOf<String, Any?>("settings" to SettingsCodec.encodeSettings(MirrorSettings()))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var fail = false
        var calls = 0
        var thread: Thread? = null
        override fun read(): Map<String, *> = values.toMap()
        override fun write(values: Map<String, Any?>): Boolean {
            calls++
            thread = Thread.currentThread()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            if (fail) return false
            this.values.putAll(values)
            return true
        }
    }

    @Test fun slowWritesRunOffCallerThreadAndQueuedEditsMergeWithTheLatestCommit() = runBlocking {
        val storage = SlowStorage()
        val repository = DeviceRepository(storage)
        val baseline = repository.settings.value
        val caller = Thread.currentThread()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            assertTrue(repository.mutate { updateSettings { it.copy(touchOffsetXDp = 12) } })
        }
        try {
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS))
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                assertTrue(repository.mutate { mergeSettings(baseline, baseline.copy(audioEnabled = false)) })
            }
            assertNotEquals(caller, storage.thread)
            assertTrue(repository.writing.value)
            assertEquals(baseline, repository.settings.value)
            assertEquals(1, storage.calls)
            storage.release.countDown()
            first.join()
            second.join()
            assertEquals(12, repository.settings.value.touchOffsetXDp)
            assertFalse(repository.settings.value.audioEnabled)
            assertEquals(2, storage.calls)
        } finally {
            storage.release.countDown()
            first.join()
        }
    }

    @Test fun cancellingAnAwaiterDoesNotLoseAnAlreadyAcceptedCommit() = runBlocking {
        val storage = SlowStorage()
        val repository = DeviceRepository(storage)
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.mutate { updateSettings { it.copy(maxFps = 30) } }
        }
        try {
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS))
            request.cancelAndJoin()
            assertEquals(MirrorSettings(), repository.settings.value)
            storage.release.countDown()
            assertEquals(30, repository.mutate { getSettings().maxFps })
        } finally {
            storage.release.countDown()
            request.cancelAndJoin()
        }
    }

    @Test fun checkedFailureDoesNotPublishAndTheWriterAcceptsTheNextSave() = runBlocking {
        val storage = SlowStorage().apply { release.countDown(); fail = true }
        val repository = DeviceRepository(storage)
        assertFalse(repository.mutate { updateSettings { it.copy(maxFps = 30) } })
        assertEquals(MirrorSettings(), repository.settings.value)
        assertTrue(repository.errors.value.any { "write failed" in it.message })
        storage.fail = false
        assertTrue(repository.mutate { updateSettings { it.copy(touchOffsetYDp = 11) } })
        assertEquals(MirrorSettings(touchOffsetYDp = 11), repository.settings.value)
    }

    @Test fun operationExceptionsAreDeliveredWithoutKillingTheApplicationWriter() = runBlocking {
        val storage = SlowStorage().apply { release.countDown() }
        val repository = DeviceRepository(storage)
        var failure: IOException? = null
        try {
            repository.mutate<Unit> { throw IOException("test failure") }
        } catch (error: IOException) {
            failure = error
        }
        assertEquals("test failure", failure?.message)
        assertTrue(repository.mutate { updateSettings { it.copy(maxFps = 30) } })
    }
}
