package com.scrcpy.remote.session

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.scrcpy.ControlMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputLockStateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun restoredViewModelStartsLockedBeforeAnyConnectionCanForwardInput() = onMain {
        val saved = SavedStateHandle()
        val originalStore = ViewModelStore()
        val replacementStore = ViewModelStore()
        try {
            val original = MirrorViewModel(app, saved).also { originalStore.put("session", it) }
            original.setInputLocked(true)
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            val replacement = MirrorViewModel(app, restored).also { replacementStore.put("session", it) }
            assertTrue(replacement.inputState.value.locked)
            assertFalse(replacement.sendText("Must never be pasted"))
            replacement.pushClipboard()
            repeat(10) { replacement.send(ControlMessage.InjectKeycode(0, 29)) }
            assertNull("Blocked input must not queue notices over the persistent unlock button",
                replacement.notice.value)
            replacement.disconnect()
            assertTrue(replacement.inputState.value.locked)
        } finally {
            originalStore.clear()
            replacementStore.clear()
        }
    }

    @Test fun explicitUnlockIsSavedAndRapidTogglesPublishANewInputGeneration() = onMain {
        val saved = SavedStateHandle()
        val store = ViewModelStore()
        val replacementStore = ViewModelStore()
        try {
            val model = MirrorViewModel(app, saved).also { store.put("session", it) }
            val before = model.inputState.value
            model.setInputLocked(true)
            model.setInputLocked(false)
            assertFalse(model.inputState.value.locked)
            assertEquals(before.generation + 2, model.inputState.value.generation)
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            val replacement = MirrorViewModel(app, restored).also { replacementStore.put("session", it) }
            assertFalse(replacement.inputState.value.locked)
        } finally {
            store.clear()
            replacementStore.clear()
        }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
