package com.scrcpy.remote

import com.scrcpy.remote.ui.wrappableAddress
import com.scrcpy.remote.ui.wrappablePackageName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where long names may wrap. Android finds no line break inside a package name or an address, so
 * one too long for its line broke anywhere, even inside a word or a port number.
 */
class TextBreaksTest {
    @Test fun aLongPackageNameWrapsAfterADotRatherThanMidWord() {
        // It wrapped as "com.google.android.calen" over "dar".
        assertEquals("com.\u200Bgoogle.\u200Bandroid.\u200Bcalendar", wrappablePackageName("com.google.android.calendar"))
        assertEquals("notes", wrappablePackageName("notes"))
    }

    @Test fun aLongAddressWrapsAfterADotOrAColonRatherThanInsideANumber() {
        // It wrapped as "192.168.1.20:55" over "55".
        assertEquals("192.\u200B168.\u200B1.\u200B20:\u200B5555", wrappableAddress("192.168.1.20:5555"))
        assertEquals("[2001:\u200Bdb8:\u200B:\u200B1]:\u200B5555", wrappableAddress("[2001:db8::1]:5555"))
        assertEquals("phone.\u200Blocal:\u200B5555", wrappableAddress("phone.local:5555"))
        assertEquals("tablet", wrappableAddress("tablet"))
    }
}
