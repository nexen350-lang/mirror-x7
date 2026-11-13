package com.scrcpy.remote.session

import android.app.Notification
import android.app.NotificationManager
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MirrorSessionServiceTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Before fun grantNotifications() {
        // POST_NOTIFICATIONS only exists from API 33, and UiAutomation.grantRuntimePermission
        // only from API 28. Below that the notification is posted without any grant.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, "android.permission.POST_NOTIFICATIONS",
        )
    }

    @After fun stopService() {
        MirrorSessionService.stop(context)
        awaitNoService()
        awaitNoNotification()
    }

    @Test fun aRefusedStartIsCountedSoTheSessionOwnerCanSaySoWhateverTheReasonWas() {
        // Android 12+ rejects a foreground start once the app is cached, which is reachable simply
        // by connecting slowly and pressing Home. The refusal used to be reported only for an
        // audio hold, so a connection that landed while the user was elsewhere ran with no
        // foreground protection and said nothing. Counting it here is what makes the single
        // failure collector cover every reason the process could not be held up.
        val before = MirrorSessionService.startFailures.value
        // A context with no valid application is refused the same way a cached start is: the
        // point is that the failure is recorded rather than only returned.
        val refused = MirrorSessionService.start(BrokenContext(context), "Desk phone", SessionHold.CONNECTED)
        assertFalse("A start through a broken context must not report success", refused)
        assertEquals("The refusal must be counted", before + 1, MirrorSessionService.startFailures.value)
    }

    /** Refuses to start anything, standing in for a platform that rejects a background start. */
    private class BrokenContext(base: Context) : android.content.ContextWrapper(base) {
        override fun startForegroundService(service: android.content.Intent) =
            throw IllegalStateException("not allowed from background")
    }

    @Test fun startingTheServicePostsAnOngoingNotificationNamingTheDevice() {
        assertTrue(MirrorSessionService.start(context, "Desk phone", SessionHold.CONNECTED))
        val notification = awaitNotificationText(
            context.getString(R.string.background_notification_device, "Desk phone"))

        assertEquals(
            context.getString(R.string.background_notification_title),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        // Ongoing: the session is still running, so it must not be swipeable away.
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)

        val channel = notifications.getNotificationChannel(CHANNEL_ID)
        assertNotNull("the channel must exist before the notification is posted", channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test fun aNamedTransferSaysWhatItIsDoingRatherThanThatItConnected() {
        // A share to a saved device holds the process before anything has connected, and that
        // attempt can still fail: "Connected to" it was untrue for as long as the attempt took.
        assertTrue(MirrorSessionService.start(context, "Desk phone", SessionHold.TRANSFER))
        awaitNotificationText(context.getString(R.string.transfer_notification_device, "Desk phone"))
    }
    @Test fun aSessionWithoutADeviceNameDescribesOnlyWhatIsActuallyRunning() {
        // An address or link has no saved name. The fallback used to promise playing audio for
        // every hold, including a silent session and a transfer that had not even connected yet.
        // Ordered so each start changes the text, which proves that start was actually posted.
        val failures = MirrorSessionService.startFailures.value
        for ((hold, text) in listOf(
            SessionHold.CONNECTED to R.string.background_notification_connected,
            // Two types at once: the platform must accept the combination the manifest declares.
            SessionHold.TRANSFER_WITH_AUDIO to R.string.transfer_notification_text,
            SessionHold.AUDIO to R.string.background_notification_text,
            SessionHold.TRANSFER to R.string.transfer_notification_text,
        )) {
            assertTrue(MirrorSessionService.start(context, null, hold))
            awaitNotificationText(context.getString(text))
        }
        assertEquals("No hold may be refused", failures, MirrorSessionService.startFailures.value)
    }

    @Test fun eachHoldRunsAsTheForegroundServiceTypePlayIsToldAbout() {
        // Play's foreground service declaration describes these types, and Android 14 and newer
        // refuse a connectedDevice start whose prerequisite permission is missing. Only the
        // platform's own service record shows which type the service actually runs as.
        assumeTrue(
            "Android checks foreground service types from Android 14",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        )
        val failures = MirrorSessionService.startFailures.value
        for ((hold, text, type) in listOf(
            Triple(SessionHold.CONNECTED, R.string.background_notification_connected, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE),
            Triple(SessionHold.TRANSFER, R.string.transfer_notification_text, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
            Triple(SessionHold.AUDIO, R.string.background_notification_text, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK),
        )) {
            assertTrue(MirrorSessionService.start(context, null, hold))
            awaitNotificationText(context.getString(text))
            awaitRunningType(hold, type)
        }
        assertEquals("No hold may be refused", failures, MirrorSessionService.startFailures.value)
    }

    /** Waits for the service's own record to show [expected], the type [hold] declares. */
    private fun awaitRunningType(hold: SessionHold, expected: Int) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS
        var record = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            record = shell("dumpsys activity services ${context.packageName}")
                .substringAfter(MirrorSessionService::class.java.simpleName, "")
            val type = Regex("isForeground=true [^\\n]*types=0x([0-9A-Fa-f]+)").find(record)
                ?.groupValues?.get(1)?.toLong(16)?.toInt()
            if (type == expected) return
            SystemClock.sleep(POLL_MILLIS)
        }
        val running = record.lineSequence().firstOrNull { "isForeground=" in it }?.trim()
        throw AssertionError(
            "$hold should run as type 0x${Integer.toHexString(expected)}; its record says: ${running ?: record.take(600)}",
        )
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun theNotificationActionRequestsAStopAndTakesTheNotificationDown() {
        val before = MirrorSessionService.stopRequests.value
        assertTrue(MirrorSessionService.start(context, "Desk phone", SessionHold.CONNECTED))
        val notification = awaitNotificationText(
            context.getString(R.string.background_notification_device, "Desk phone"))

        notification.actions.single {
            it.title == context.getString(R.string.background_notification_stop)
        }.actionIntent.send()

        // The service owns no session, so a stop must be observable by the session owner rather
        // than quietly ending with the connection still running.
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (MirrorSessionService.stopRequests.value == before && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
        assertEquals(before + 1, MirrorSessionService.stopRequests.value)
        awaitNoNotification()
        assertNull(current())
    }

    @Test fun restartingUpdatesTheSameNotificationInsteadOfStackingThem() {
        assertTrue(MirrorSessionService.start(context, "First", SessionHold.CONNECTED))
        awaitNotificationText(context.getString(R.string.background_notification_device, "First"))
        assertTrue(MirrorSessionService.start(context, "Second", SessionHold.CONNECTED))
        awaitNotificationText(context.getString(R.string.background_notification_device, "Second"))
        assertEquals(1, notifications.activeNotifications.count { it.packageName == context.packageName })
    }

    @Test fun releasingBeforeTheFirstStartCallbackDoesNotCrashOrLeaveAService() {
        val failures = MirrorSessionService.startFailures.value
        repeat(5) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(MirrorSessionService.start(context, "Immediate release", SessionHold.CONNECTED))
                MirrorSessionService.stop(context)
            }
            awaitServiceDrain()
            awaitNoService()
            assertNull(current())
        }
        assertEquals(failures, MirrorSessionService.startFailures.value)
    }

    @Test fun immediateReleaseThenRestartKeepsTheLatestRequestedHold() {
        val failures = MirrorSessionService.startFailures.value
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(MirrorSessionService.start(context, "Obsolete", SessionHold.CONNECTED))
            MirrorSessionService.stop(context)
            assertTrue(MirrorSessionService.start(context, "Latest", SessionHold.CONNECTED))
        }
        awaitNotificationText(context.getString(R.string.background_notification_device, "Latest"))
        awaitServiceDrain()
        assertEquals(context.getString(R.string.background_notification_device, "Latest"),
            current()?.notification?.extras?.getString(Notification.EXTRA_TEXT))
        assertEquals(failures, MirrorSessionService.startFailures.value)
    }

    @Test fun aRefusedRestartStillReleasesTheEarlierPendingService() {
        val failures = MirrorSessionService.startFailures.value
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(MirrorSessionService.start(context, "Pending release", SessionHold.CONNECTED))
            MirrorSessionService.stop(context)
            assertFalse(MirrorSessionService.start(BrokenContext(context), "Refused", SessionHold.CONNECTED))
        }
        awaitServiceDrain()
        awaitNoService()
        assertNull(current())
        assertEquals(failures + 1, MirrorSessionService.startFailures.value)
    }

    private fun awaitServiceDrain() {
        val drained = CountDownLatch(1)
        Handler(Looper.getMainLooper()).postDelayed({ drained.countDown() }, 1_000)
        assertTrue("Service callbacks must settle without killing the app", drained.await(5, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(100)
    }

    @Suppress("DEPRECATION")
    private fun awaitNoService() {
        val manager = context.getSystemService(ActivityManager::class.java)
        fun present() = manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == MirrorSessionService::class.java.name }
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS
        while (present() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(POLL_MILLIS)
        assertFalse("The stopped session must not retain its service", present())
    }

    private fun current(): StatusBarNotification? =
        notifications.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }

    /**
     * Waits for the notification this test asked for, identified by its text rather than by mere
     * presence. The notification is process-wide, so a previous test's notification can still be
     * posted while this one starts; matching on content makes each test independent of that.
     */
    private fun awaitNotificationText(expected: String): Notification {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var seen: String? = null
        while (System.currentTimeMillis() < deadline) {
            val found = current()
            seen = found?.notification?.extras?.getString(Notification.EXTRA_TEXT)
            if (seen == expected) return found!!.notification
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for notification text <$expected>, last was <$seen>")
    }

    private fun awaitNoNotification() {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (current() != null && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MILLIS)
    }

    private companion object {
        const val CHANNEL_ID = "mirror-session"
        const val NOTIFICATION_ID = 1
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
