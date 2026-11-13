package com.scrcpy.remote.session

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.DeviceApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** When the device's own pages about an app are not even asked for, and what the user is told. */
@RunWith(AndroidJUnit4::class)
class AppPagesFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test fun withoutASessionNothingIsAskedAndTheUserIsTold() {
        withModel { model ->
            assertFalse(main { model.openAppPage(NOTES, DeviceAppPages.Page.APP_INFO) })
            assertEquals(app.getString(R.string.app_pages_need_session), model.notice.value)
        }
    }

    @Test fun anAppThatCameWithTheDeviceIsNeverAskedToBeUninstalled() {
        withModel { model ->
            assertFalse(main { model.openAppPage(SETTINGS, DeviceAppPages.Page.UNINSTALL) })
            // Turned away before anything else, so not even the missing session is mentioned.
            assertNull(model.notice.value)
        }
    }

    @Test fun lockedInputOpensNoPagesOnTheDevice() {
        withModel { model ->
            main { model.setInputLocked(true) }
            assertFalse(main { model.openAppPage(NOTES, DeviceAppPages.Page.APP_INFO) })
            assertFalse(main { model.openAppPage(NOTES, DeviceAppPages.Page.UNINSTALL) })
            // The lock's own banner says why; a notice for each attempt would cover it.
            assertNull(model.notice.value)
        }
    }

    @Test fun androidsOwnRegexEngineReadsTheDevicesAnswers() {
        // Android compiles patterns with ICU, which refused one that the JVM tests accepted, and
        // the first App info menu then crashed the app.
        assertEquals(DeviceAppPages.Result.Opened, DeviceAppPages.parse(
            "Starting: Intent { act=android.intent.action.DELETE dat=package:com.example.Error.exceptionlog }\n"))
        val refused = DeviceAppPages.parse(
            "Starting: Intent { act=android.settings.NOPE_NOT_REAL dat=package:com.android.settings }\n" +
                "Error: Activity not started, unable to resolve Intent { act=android.settings.NOPE_NOT_REAL " +
                "dat=package:com.android.settings flg=0x10000000 }\n")
        assertTrue(refused is DeviceAppPages.Result.Failed)
    }

    private fun withModel(block: (MirrorViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: MirrorViewModel
        instrumentation.runOnMainSync {
            model = MirrorViewModel(app)
            store.put("app-pages-flow-test", model)
        }
        try {
            block(model)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private companion object {
        val NOTES = DeviceApp("Notes", "com.example.notes", system = false)
        val SETTINGS = DeviceApp("Settings", "com.android.settings", system = true)
    }
}
