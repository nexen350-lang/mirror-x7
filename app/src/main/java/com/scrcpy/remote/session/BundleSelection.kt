package com.scrcpy.remote.session

import java.util.Locale

/** What decides which of an app bundle's files a device needs. */
data class DeviceSpec(
    val sdk: Int,
    /** In the device's order of preference, as Android names them: `x86_64`, `arm64-v8a`. */
    val abis: List<String>,
    val densityDpi: Int,
    /** Language codes, such as `en` and `fr`, of the device's languages. */
    val languages: List<String>,
)

/** Asking a device for its [DeviceSpec] over its shell. */
object DeviceSpecs {
    /**
     * One round trip for everything. A changed display size overrides the physical density, and
     * apps draw at it; the user's list of languages, set since Android 7, comes before the single
     * language older versions keep.
     */
    const val COMMAND = "echo sdk=\$(getprop ro.build.version.sdk); echo abis=\$(getprop ro.product.cpu.abilist); " +
        "echo abi=\$(getprop ro.product.cpu.abi); wm density; echo lcd=\$(getprop ro.sf.lcd_density); " +
        "echo locales=\$(settings get system system_locales); echo locale=\$(getprop persist.sys.locale); " +
        "echo product=\$(getprop ro.product.locale)"

    fun parse(output: String): DeviceSpec? {
        val values = mutableMapOf<String, String>()
        var physical: Int? = null
        var override: Int? = null
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("Physical density:") -> physical = line.substringAfter(':').trim().toIntOrNull()
                line.startsWith("Override density:") -> override = line.substringAfter(':').trim().toIntOrNull()
                '=' in line -> values.putIfAbsent(line.substringBefore('=').trim(), line.substringAfter('=').trim())
            }
        }
        val sdk = values["sdk"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val abis = values["abis"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOfNotNull(values["abi"]?.takeIf { it.isNotEmpty() }) }
        if (abis.isEmpty()) return null
        // A device without the window manager's answer still has the density it was built with.
        val density = (override ?: physical ?: values["lcd"]?.toIntOrNull())?.takeIf { it > 0 } ?: return null
        val locales = listOf(values["locales"], values["locale"], values["product"])
            .firstOrNull { !it.isNullOrEmpty() && it != "null" }.orEmpty()
        val languages = locales.split(',').mapNotNull { tag ->
            tag.trim().split('-', '_').first().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
        }.distinct()
        return DeviceSpec(sdk, abis, density, languages)
    }
}

/** Which of a bundle's files to install on a device, or why none can be. */
sealed interface BundleChoice {
    data class Install(val parts: List<AppBundle.Part>) : BundleChoice

    /** The bundle has native code only for processors the device does not run. */
    data class NoMatchingProcessor(val offered: List<String>, val device: List<String>) : BundleChoice

    /** Every variant of the bundle needs a newer Android than the device has. */
    data class NeedsNewerAndroid(val minSdk: Int, val deviceSdk: Int) : BundleChoice
}

/**
 * Chooses the files of an app bundle that a device needs, as Google Play and bundletool do: the
 * variant for its Android version, then in each module the base or feature itself, the one split
 * for its processor, the one for its screen density, and those for its languages.
 *
 * Anything less is a partial app. Android 13 and newer refuse one whose base says which kinds of
 * split it needs, but Android 8 installs a base alone without a word, and the app then fails when
 * it reaches for code or pictures that were left behind.
 */
object BundleSelection {
    fun choose(bundle: AppBundle, device: DeviceSpec): BundleChoice {
        val variant = bundle.variants.filter { it.minSdk <= device.sdk }.maxByOrNull { it.minSdk }
            ?: return BundleChoice.NeedsNewerAndroid(bundle.variants.minOfOrNull { it.minSdk } ?: 0, device.sdk)
        // The base first, then features in the order the bundle lists them.
        val modules = variant.parts.filterNot { it.onDemand }.groupBy { SplitNames.module(it.split) }
            .entries.sortedBy { if (it.key.isEmpty()) 0 else 1 }.map { it.value }
        // One process runs one processor's code, so every module's must be for the same one.
        val offers = modules.map { parts -> parts.mapNotNull { (SplitNames.kind(it.split) as? SplitNames.Kind.Abi)?.abi } }
            .filter { it.isNotEmpty() }
        val abi = if (offers.isEmpty()) {
            null
        } else {
            val common = offers.reduce { shared, next -> shared.filter { it in next } }
            device.abis.firstOrNull { it in common }
                ?: return BundleChoice.NoMatchingProcessor(common.ifEmpty { offers.flatten().distinct() }, device.abis)
        }
        val languages = device.languages.map(SplitNames::language).toSet()
        val chosen = mutableListOf<AppBundle.Part>()
        for (parts in modules) {
            // A configuration split is only ever installed with its module.
            chosen += parts.firstOrNull { SplitNames.kind(it.split) == SplitNames.Kind.Master } ?: continue
            if (abi != null) parts.firstOrNull { (SplitNames.kind(it.split) as? SplitNames.Kind.Abi)?.abi == abi }?.let(chosen::add)
            val densities = parts.mapNotNull { part -> (SplitNames.kind(part.split) as? SplitNames.Kind.Density)?.let { part to it.dpi } }
            if (densities.isNotEmpty()) {
                val best = bestDensity(densities.map { it.second }, device.densityDpi)
                chosen += densities.first { it.second == best }.first
            }
            chosen += parts.filter { (SplitNames.kind(it.split) as? SplitNames.Kind.Language)?.language in languages }
        }
        return BundleChoice.Install(chosen)
    }

    /** Why a bundle was refused on this phone, for a [choice] that installs nothing; null when the device said nothing. */
    fun refusal(choice: BundleChoice?): ApkInstall.Refusal = when (choice) {
        is BundleChoice.NoMatchingProcessor -> ApkInstall.Refusal.WRONG_PROCESSOR
        is BundleChoice.NeedsNewerAndroid -> ApkInstall.Refusal.NEEDS_NEWER_ANDROID
        // The device was asked only what it is, and did not say: no files were chosen.
        is BundleChoice.Install, null -> ApkInstall.Refusal.UNDESCRIBED_DEVICE
    }

    /**
     * Of the density buckets [available], in dpi, the one Android's resources would use at
     * [deviceDpi]: the platform's own rule (ResTable_config::isBetterThan), which bundletool
     * follows too. Scaling a picture down is better than scaling one up, but not at any cost.
     */
    fun bestDensity(available: Collection<Int>, deviceDpi: Int): Int =
        available.reduce { best, candidate -> if (isBetter(candidate, best, deviceDpi)) candidate else best }

    private fun isBetter(density: Int, other: Int, requested: Int): Boolean {
        if (density == other) return false
        val densityIsHigher = density > other
        val high = maxOf(density, other)
        val low = minOf(density, other)
        return when {
            requested >= high -> densityIsHigher
            low >= requested -> !densityIsHigher
            (2L * low - requested) * high > requested.toLong() * requested -> !densityIsHigher
            else -> densityIsHigher
        }
    }
}
