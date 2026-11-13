package com.scrcpy.remote

import com.scrcpy.remote.data.PreferenceWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The shared writer hands a result back to a suspended caller. Anything it fails to complete
 * leaves that caller awaiting forever, which the user sees as a screen stuck on "saving" with no
 * way out — so every way an operation can end has to complete the deferred.
 */
class PreferenceWriterTest {

    @Test fun aSuccessfulWriteReturnsItsValue() = runBlocking {
        assertEquals(42, withTimeout(5_000) { PreferenceWriter.submit { 42 } })
    }

    @Test fun anExceptionReachesTheCallerRatherThanHangingIt() = runBlocking {
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { withTimeout(5_000) { PreferenceWriter.submit<Unit> { error("no room") } } }
        }
        assertEquals("no room", failure.message)
    }

    @Test fun anErrorAlsoReachesTheCallerInsteadOfStrandingIt() = runBlocking {
        // Realistically an OutOfMemoryError while encoding a large snapshot. Catching only
        // Exception left the caller suspended and the UI busy for the rest of the process.
        val failure = assertThrows(OutOfMemoryError::class.java) {
            runBlocking {
                withTimeout(5_000) { PreferenceWriter.submit<Unit> { throw OutOfMemoryError("snapshot") } }
            }
        }
        assertEquals("snapshot", failure.message)
    }

    @Test fun theWriterKeepsServingLaterWritesAfterAFailure() = runBlocking {
        runCatching { PreferenceWriter.submit<Unit> { error("first") } }
        runCatching { PreferenceWriter.submit<Unit> { throw OutOfMemoryError("second") } }
        assertEquals("third", withTimeout(5_000) { PreferenceWriter.submit { "third" } })
    }

    @Test fun writesAreServedInTheOrderTheyWereAccepted() = runBlocking {
        val order = mutableListOf<Int>()
        val jobs = (1..8).map { index ->
            async { PreferenceWriter.submit { synchronized(order) { order += index } } }
        }
        withTimeout(10_000) { jobs.forEach { it.await() } }
        assertEquals((1..8).toList(), order)
    }
}
