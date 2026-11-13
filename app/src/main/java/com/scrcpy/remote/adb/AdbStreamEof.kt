package com.scrcpy.remote.adb

import java.io.IOException

/**
 * Recognises how libadb-android signals the end of a stream.
 *
 * libadb 3.1.1 throws `IOException("Stream closed.")` where `InputStream` would normally return
 * -1. The local source patch reserves this exception for a received peer CLSE; transport loss
 * reports a distinct failure instead of accepting incomplete shell/catalog output.
 *
 * Kept deliberately narrow — exact class and message — so that genuine I/O failures such as a
 * dropped connection, a permission problem or a failed file operation are still reported.
 */
fun isAdbStreamEof(error: Throwable): Boolean =
    error.javaClass == IOException::class.java && error.message == "Stream closed."
