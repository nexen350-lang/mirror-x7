package com.scrcpy.remote.session

import android.view.KeyEvent
import androidx.annotation.StringRes
import com.scrcpy.remote.R

/** Which hardware key presses carry this phone's clipboard to the device first. */
object ClipboardKeys {
    /**
     * Whether this key press should send this phone's clipboard to the device before it is
     * forwarded.
     *
     * Ctrl+V (or Ctrl+Shift+V) pastes this phone's clipboard, as on scrcpy's desktop client: the
     * clipboard goes first, and the forwarded key then pastes it. Only the first press, so a held
     * key does not resend it, and only while clipboards are synced: with each kept private, the
     * device pastes its own. Input Lock sends nothing.
     */
    fun syncsBeforeForwarding(
        action: Int,
        keycode: Int,
        repeat: Int,
        metaState: Int,
        autosync: Boolean,
        locked: Boolean,
    ): Boolean = autosync && !locked && action == KeyEvent.ACTION_DOWN && repeat == 0 &&
        keycode == KeyEvent.KEYCODE_V && metaState and KeyEvent.META_CTRL_ON != 0 &&
        metaState and (KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) == 0

    /**
     * Whether this phone's clip, copied at [phoneClipAtMillis], is what Ctrl+V should send over
     * the last copy the device sent at [deviceClipAtMillis], if it sent one.
     *
     * A copy made on the device while the mirror was hidden is not put on this phone's clipboard,
     * so the device's is then the newer one: sending this phone's older clip over it would paste
     * the wrong text and lose what was just copied. A clip of unknown age, 0, loses to any copy
     * the device has sent.
     */
    fun phoneClipIsNewer(phoneClipAtMillis: Long, deviceClipAtMillis: Long?): Boolean =
        deviceClipAtMillis == null || phoneClipAtMillis >= deviceClipAtMillis
}

/** What to say when port discovery found no replacement it could trust. */
object PortDiscoveryNotice {
    /** The notice's text, and the port it names if it names one. */
    data class Notice(@StringRes val text: Int, val port: Int? = null)

    /** A port found by an earlier discovery is still the one dialled, so it is the one named. */
    fun noReplacement(retryPort: Int?): Notice =
        if (retryPort != null) Notice(R.string.recovery_kept_port, retryPort) else Notice(R.string.recovery_original_port)
}
