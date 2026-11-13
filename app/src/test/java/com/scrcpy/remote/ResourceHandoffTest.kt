package com.scrcpy.remote

import com.scrcpy.remote.session.ResourceHandoff
import com.scrcpy.remote.session.StartupResources
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException

class ResourceHandoffTest {
    private class Resource : Closeable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun cancelledBeforeWorkerCompletesClosesLateResult() {
        val handoff = ResourceHandoff<Resource>()
        val result = Resource()
        handoff.close()
        handoff.offer(result)
        assertEquals(1, result.closes)
    }
    @Test fun cancelledAfterWorkerCompletesBeforeMainAdoptsClosesResult() {
        val handoff = ResourceHandoff<Resource>()
        val result = Resource()
        handoff.offer(result)
        handoff.close()
        handoff.close()
        assertEquals(1, result.closes)
    }
    @Test fun adoptedResourceBelongsOnlyToSession() {
        val handoff = ResourceHandoff<Resource>()
        val result = Resource()
        handoff.offer(result)
        assertSame(result, handoff.take())
        handoff.close()
        assertEquals(0, result.closes)
    }

    @Test fun releasingAClosedResourceMakesRoomForTheNextRegistration() {
        val handoff = ResourceHandoff<Resource>()
        val first = Resource()
        val second = Resource()
        handoff.offer(first)
        first.close()
        handoff.release(first)
        handoff.offer(second)
        handoff.close()
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
    }

    @Test fun aStaleReleaseCannotForgetAnEqualButDifferentReplacementOwner() {
        class EqualResource : Closeable {
            var closes = 0
            override fun close() { closes++ }
            override fun equals(other: Any?) = other is EqualResource
            override fun hashCode() = 0
        }
        val handoff = ResourceHandoff<EqualResource>()
        val first = EqualResource()
        val replacement = EqualResource()
        handoff.offer(first)
        assertSame(first, handoff.take())
        handoff.offer(replacement)
        first.close()
        handoff.release(first)
        handoff.close()
        assertEquals(1, first.closes)
        assertEquals("A stale release must compare identity, not equality", 1, replacement.closes)
    }

    @Test fun releaseAfterCancellationDoesNotReopenTheHandoffOrCloseAnythingAgain() {
        val handoff = ResourceHandoff<Resource>()
        val first = Resource()
        val late = Resource()
        handoff.offer(first)
        handoff.close()
        handoff.release(first)
        handoff.offer(late)
        handoff.release(late)
        handoff.close()
        assertEquals(1, first.closes)
        assertEquals(1, late.closes)
        assertThrows(IllegalStateException::class.java) { handoff.take() }
    }

    @Test fun cancellationClosesEstablishedStartupResources() {
        val startup = StartupResources()
        val transport = startup.trackTransport(Resource())
        val socket = startup.track(Resource())
        startup.close()
        startup.close()
        assertEquals(1, transport.closes)
        assertEquals(1, socket.closes)
        val lateSocket = Resource()
        try { startup.track(lateSocket); fail("Must reject after cancellation") }
        catch (_: InterruptedIOException) { assertEquals(1, lateSocket.closes) }
        val lateTransport = Resource()
        assertThrows(InterruptedIOException::class.java) { startup.trackTransport(lateTransport) }
        assertEquals(1, lateTransport.closes)
    }
    @Test fun transferredResourcesBelongToSession() {
        val startup = StartupResources()
        val transport = startup.trackTransport(Resource())
        val socket = startup.track(Resource())
        startup.transfer()
        startup.close()
        assertEquals(0, transport.closes)
        assertEquals(0, socket.closes)
    }

    @Test fun ordinaryStartupResourcesKeepTheirReverseOrderAndCloseOnlyOnce() {
        val startup = StartupResources()
        val closed = mutableListOf<String>()
        for (name in listOf("sync", "shell", "socket", "controller")) {
            startup.track(Closeable { closed += name })
        }
        startup.close()
        startup.close()
        assertEquals(listOf("controller", "socket", "shell", "sync"), closed)
    }

    @Test fun transportCloseFailureDoesNotSkipLogicalResourceCleanup() {
        val startup = StartupResources()
        val failure = IOException("Transport close failed")
        val closed = mutableListOf<String>()
        startup.trackTransport(Closeable { throw failure })
        startup.track(Closeable { closed += "shell" })
        startup.track(Closeable { closed += "socket" })

        assertSame(failure, assertThrows(IOException::class.java) { startup.close() })
        assertEquals(listOf("socket", "shell"), closed)
    }

    @Test fun ordinaryStartupCleanupStillAggregatesFailuresInReverseOrder() {
        val startup = StartupResources()
        val first = IOException("First resource")
        val last = IOException("Last resource")
        startup.track(Closeable { throw first })
        startup.track(Closeable { throw last })

        val failure = assertThrows(IOException::class.java) { startup.close() }

        assertSame(last, failure)
        assertArrayEquals(arrayOf(first), failure.suppressed)
    }

    @Test fun prioritizedTransportCleanupRetainsEveryFailureInCloseOrder() {
        val startup = StartupResources()
        val transport = IOException("Transport")
        val shell = IOException("Shell")
        val socket = IOException("Socket")
        startup.trackTransport(Closeable { throw transport })
        startup.track(Closeable { throw shell })
        startup.track(Closeable { throw socket })

        val failure = assertThrows(IOException::class.java) { startup.close() }

        assertSame(transport, failure)
        assertArrayEquals(arrayOf(socket, shell), failure.suppressed)
        startup.close()
    }
}
