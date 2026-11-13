package com.scrcpy.remote.ui

/** An invisible place where a line may break. */
private const val BREAK = "\u200B"

/**
 * [packageName] with a zero-width space after each dot. Android finds no line break inside a
 * package name, so one too long for its line wrapped mid-word ("com.google.android.calen" over
 * "dar"); after a dot, each part reads whole.
 */
internal fun wrappablePackageName(packageName: String): String = packageName.replace(".", ".$BREAK")

/**
 * [address] with a zero-width space after each dot and colon, so a host, an IPv6 group or the port
 * moves to the next line whole. It wrapped as "192.168.1.20:55" over "55".
 */
internal fun wrappableAddress(address: String): String = address.replace(".", ".$BREAK").replace(":", ":$BREAK")
