package com.scrcpy.remote.ui

import com.scrcpy.remote.data.SavedDevice
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class DeviceListTest {
    @Test
    fun sortsFavoritesBeforeCaseInsensitiveNames() {
        val devices = listOf(
            device("1", "alpha"),
            device("2", "zulu", favorite = true),
            device("3", "Bravo"),
            device("4", "beta", favorite = true),
            device("5", "ALPINE"),
        )

        assertEquals(listOf("4", "2", "1", "5", "3"), visibleDevices(devices, "").map { it.id })
    }

    @Test
    fun preservesInputOrderForCaseInsensitiveTiesWithinFavoriteGroups() {
        val devices = listOf(
            device("1", "TABLET"),
            device("2", "Tablet", favorite = true),
            device("3", "tablet"),
            device("4", "TABLET", favorite = true),
            device("5", "Tablet"),
        )

        assertEquals(listOf("2", "4", "1", "3", "5"), visibleDevices(devices, "tablet").map { it.id })
    }

    @Test
    fun searchesNamesHostsAndFormattedAddresses() {
        val devices = listOf(
            device("name", "Kitchen display", host = "screen.local"),
            device("host", "Phone", host = "Kitchen.local", port = 4321),
            device("ip", "Spare", host = "192.168.1.20"),
        )

        assertEquals(listOf("name", "host"), visibleDevices(devices, " KITCHEN ").map { it.id })
        assertEquals(listOf("name"), visibleDevices(devices, "DISPLAY").map { it.id })
        assertEquals(listOf("host"), visibleDevices(devices, "kitchen.LOCAL:4321").map { it.id })
        assertEquals(listOf("host"), visibleDevices(devices, ":4321").map { it.id })
        assertEquals(listOf("ip"), visibleDevices(devices, "168.1").map { it.id })
    }

    @Test
    fun searchesIpv6HostsAndBracketedAddresses() {
        val ipv6 = device("ipv6", "Tablet", host = "2001:db8::A", port = 37123)
        val devices = listOf(ipv6, device("ipv4", "Phone", host = "192.168.1.20"))

        for (query in listOf("2001:DB8::a", "[2001:db8::a]:37123", "::a", "]:37123")) {
            assertEquals(query, listOf(ipv6), visibleDevices(devices, query))
        }
    }

    @Test
    fun matchesUnicodeNamesIgnoringCaseWithoutDependingOnDefaultLocale() {
        val devices = listOf(
            device("accent", "Écran"),
            device("greek", "Συσκευή"),
            device("korean", "거실 태블릿"),
            device("latin", "DISPLAY", host = "MIRROR.local"),
        )
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf("accent"), visibleDevices(devices, "éCRAN").map { it.id })
            assertEquals(listOf("greek"), visibleDevices(devices, "σΥΣΚΕΥΉ").map { it.id })
            assertEquals(listOf("korean"), visibleDevices(devices, "태블릿").map { it.id })
            assertEquals(listOf("latin"), visibleDevices(devices, "display").map { it.id })
            assertEquals(listOf("latin"), visibleDevices(devices, "mirror.LOCAL").map { it.id })
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun sortsAccentedNamesWithTheirBaseLetters() {
        val devices = listOf(device("1", "Zed"), device("2", "Étoile"), device("3", "alpha"))
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            assertEquals(listOf("3", "2", "1"), visibleDevices(devices, "").map { it.id })
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun blankQueriesIncludeAllDevicesAndUnmatchedQueriesReturnEmpty() {
        val devices = listOf(device("1", "Tablet"), device("2", "Phone"))

        assertEquals(visibleDevices(devices, ""), visibleDevices(devices, " \t\n "))
        assertEquals(emptyList<SavedDevice>(), visibleDevices(devices, "missing"))
        assertEquals(emptyList<SavedDevice>(), visibleDevices(emptyList(), ""))
        assertEquals(emptyList<SavedDevice>(), visibleDevices(emptyList(), "phone"))
    }

    @Test
    fun sortingAndFilteringNeverMutateTheInputList() {
        val devices = mutableListOf(
            device("1", "Zulu"),
            device("2", "Beta", favorite = true),
            device("3", "Alpha"),
        )
        val original = devices.toList()

        val sorted = visibleDevices(devices, "")
        visibleDevices(devices, "alpha")

        assertEquals(original, devices)
        assertNotSame(devices, sorted)
        assertEquals(listOf("2", "3", "1"), sorted.map { it.id })
    }

    private fun device(
        id: String,
        name: String,
        favorite: Boolean = false,
        host: String = "$id.local",
        port: Int = 5555,
    ) = SavedDevice(id = id, name = name, host = host, port = port, isFavorite = favorite)
}
