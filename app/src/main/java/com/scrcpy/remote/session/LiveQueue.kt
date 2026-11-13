package com.scrcpy.remote.session

import kotlinx.coroutines.channels.Channel

/** Discards everything currently queued in [channel]. */
internal fun <T> drainChannel(channel: Channel<T>) {
    while (channel.tryReceive().isSuccess) Unit
}
