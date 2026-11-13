package com.scrcpy.remote.data

/**
 * Chooses which saved devices become launcher shortcuts.
 *
 * Android shows only a handful of shortcuts and orders them as given, so the choice has to be
 * deliberate: favourites first — that is what marking a favourite means — then the remaining
 * devices in their saved order. Ties keep the user's own ordering rather than being re-sorted,
 * so a shortcut does not move around between launches for no visible reason.
 */
object DeviceShortcuts {
    /**
     * Launchers are only required to display four, and several display exactly that. Publishing
     * more would silently drop the tail while still costing work on every device change.
     */
    const val MAX = 4

    fun select(devices: List<SavedDevice>, limit: Int = MAX): List<SavedDevice> {
        require(limit >= 0) { "Limit must not be negative" }
        // Stable: sortedByDescending keeps equal elements in their original relative order.
        return devices.sortedByDescending { it.isFavorite }.take(limit)
    }

    /**
     * What the launcher would actually show, so republishing can be skipped when nothing it
     * displays has changed.
     *
     * A saved device carries far more than its shortcut: touch calibration is written to it
     * during a session, every nudge of the offset emitting a new device list. Republishing on
     * each of those would spend the launcher's documented rate limit on identical shortcuts and
     * could cost a later, real update.
     */
    fun signature(devices: List<SavedDevice>): List<String> =
        // Every device, not just the selected ones: pinned shortcuts exist for any device and are
        // refreshed on publish, so a rename or edit outside the dynamic set must republish too.
        select(devices).map { "top\u0000${it.id}" } +
            devices.map { "${it.id}\u0000${shortLabel(it)}\u0000${it.address}" }

    /**
     * Short label shown under the icon. Launchers truncate aggressively, so this is the device's
     * own name rather than its address.
     */
    fun shortLabel(device: SavedDevice): String = device.name.trim().ifEmpty { device.host }

    /** Longer label, used where there is room for it. */
    fun longLabel(device: SavedDevice): String = "${shortLabel(device)} · ${device.address}"
}
