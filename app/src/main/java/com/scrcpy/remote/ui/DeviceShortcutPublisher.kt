package com.scrcpy.remote.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceShortcuts
import com.scrcpy.remote.data.SavedDevice

/**
 * Publishes launcher shortcuts for saved devices, so connecting again is a long-press rather than
 * a launch, a scroll and a tap.
 *
 * Kept out of the repository on purpose: this is a presentation concern that talks to the
 * launcher, and a launcher that refuses the update must never be able to fail a save.
 */
object DeviceShortcutPublisher {
    private const val TAG = "DeviceShortcuts"
    private const val ID_PREFIX = "device-"

    /**
     * Pinned shortcuts use their own id space. A pinned icon outlives the dynamic set — the user
     * placed it deliberately — so it must not be replaced or removed by a routine republish.
     */
    private const val PIN_ID_PREFIX = "pinned-device-"

    /** Launchers may allow fewer than [DeviceShortcuts.MAX]; exceeding it makes the set call throw. */
    private fun dynamicLimit(context: Context): Int =
        minOf(DeviceShortcuts.MAX, runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) }
            .getOrDefault(DeviceShortcuts.MAX)).coerceAtLeast(1)

    fun publish(context: Context, devices: List<SavedDevice>) {
        val application = context.applicationContext
        val shortcuts = DeviceShortcuts.select(devices, dynamicLimit(application)).map { device ->
            ShortcutInfoCompat.Builder(application, ID_PREFIX + device.id)
                .setShortLabel(DeviceShortcuts.shortLabel(device))
                .setLongLabel(DeviceShortcuts.longLabel(device))
                .setIcon(IconCompat.createWithResource(application, R.drawable.ic_shortcut_device))
                .setIntent(intentFor(application, device))
                .build()
        }
        // Best effort throughout: the rate limit is a documented launcher behaviour, and a device
        // list that could not be mirrored into the launcher is not worth surfacing to the user.
        if (shortcuts.isEmpty()) {
            runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(application) }
                .onFailure { Log.w(TAG, "Could not clear launcher shortcuts", it) }
            return
        }
        // Replaced in one call rather than cleared and then set. Clearing first meant a refused
        // or rate-limited update left the user with no shortcuts at all, having had a working
        // set a moment earlier.
        val published = runCatching { ShortcutManagerCompat.setDynamicShortcuts(application, shortcuts) }
            .onFailure { Log.w(TAG, "Could not publish launcher shortcuts", it) }
            .getOrDefault(false)
        if (!published) Log.w(TAG, "The launcher refused the shortcut update; keeping the previous set")
        // Pinned icons from older versions lack the trust nonce and would now only ask for
        // confirmation; refresh the ones that still exist (a no-op for any the user removed).
        val pinned = devices.map { device ->
            ShortcutInfoCompat.Builder(application, PIN_ID_PREFIX + device.id)
                .setShortLabel(DeviceShortcuts.shortLabel(device))
                .setLongLabel(DeviceShortcuts.longLabel(device))
                .setIcon(IconCompat.createWithResource(application, R.drawable.ic_shortcut_device))
                .setIntent(intentFor(application, device))
                .build()
        }
        runCatching { ShortcutManagerCompat.updateShortcuts(application, pinned) }
            .onFailure { Log.w(TAG, "Could not refresh pinned shortcuts", it) }
    }

    /**
     * Asks the launcher to put [device] on the home screen as its own icon.
     *
     * This is the reliable answer to "I want to tap something and connect". A browser bookmark
     * cannot do it: Chrome deliberately refuses to launch an app from a URL that was typed or
     * came from a bookmark, treating both as untrusted navigation, so only a link tapped on a
     * real page works there. A pinned shortcut skips the browser entirely.
     *
     * Returns false when the launcher does not support pinning, which some do not; the caller
     * says so rather than leaving the user waiting for an icon that will never appear.
     */
    fun requestPin(context: Context, device: SavedDevice): Boolean {
        val application = context.applicationContext
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(application)) return false
        val shortcut = ShortcutInfoCompat.Builder(application, PIN_ID_PREFIX + device.id)
            .setShortLabel(DeviceShortcuts.shortLabel(device))
            .setLongLabel(DeviceShortcuts.longLabel(device))
            .setIcon(IconCompat.createWithResource(application, R.drawable.ic_shortcut_device))
            .setIntent(intentFor(application, device))
            .build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(application, shortcut, null) }
            .onFailure { Log.w(TAG, "Could not ask the launcher to pin a shortcut", it) }
            .getOrDefault(false)
    }

    /**
     * Targets [MainActivity], naming the device by id *and* by address.
     *
     * The id is what makes a shortcut connect immediately: it is only honoured when it matches a
     * device the user has already saved, which bounds it to "connect to one of your own devices".
     * MainActivity is exported, so an address alone could not be trusted that way.
     *
     * The address is carried as well so the shortcut still works once that saved device is gone —
     * renamed, deleted, or lost with the app's data. In that case it cannot be treated as
     * pre-approved, so it goes through the same confirmation a `scrcpy://` link does rather than
     * silently doing nothing, which is how a stale shortcut used to behave.
     */
    private fun intentFor(context: Context, device: SavedDevice): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            // Not cleared to the device list: that finished a mirror screen already running above
            // it, so a shortcut for the device being mirrored tore its session down and built the
            // same one again. Left alone, the mirror screen is reused and a repeat is a no-op.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_SHORTCUT_DEVICE_ID, device.id)
            .putExtra(MainActivity.EXTRA_SHORTCUT_NONCE, ShortcutTrust.nonce(context))
            .putExtra(MainActivity.EXTRA_SHORTCUT_HOST, device.host)
            .putExtra(MainActivity.EXTRA_SHORTCUT_PORT, device.port)
            .putExtra(MainActivity.EXTRA_SHORTCUT_NAME, DeviceShortcuts.shortLabel(device))
}
