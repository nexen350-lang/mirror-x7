package com.scrcpy.remote.data

import java.util.UUID

/** New identity; immutable configuration values may safely be shared until either copy is edited. */
fun SavedDevice.duplicateDraft(displayName: String): SavedDevice =
    copy(id = UUID.randomUUID().toString(), name = displayName.takeWholeChars(100))
