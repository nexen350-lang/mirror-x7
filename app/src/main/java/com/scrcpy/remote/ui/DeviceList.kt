package com.scrcpy.remote.ui

import com.scrcpy.remote.data.SavedDevice
import java.text.Collator

private val nameCollator: Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

internal fun visibleDevices(devices: List<SavedDevice>, query: String): List<SavedDevice> {
    val search = query.trim()
    return devices.filter { device ->
        search.isEmpty() ||
            device.name.contains(search, ignoreCase = true) ||
            device.host.contains(search, ignoreCase = true) ||
            device.address.contains(search, ignoreCase = true)
    }.sortedWith(
        compareByDescending<SavedDevice> { it.isFavorite }
            .thenComparator { first, second -> nameCollator.compare(first.name, second.name) },
    )
}
