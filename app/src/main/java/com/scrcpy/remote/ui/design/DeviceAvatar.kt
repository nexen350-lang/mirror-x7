package com.scrcpy.remote.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.data.DeviceIcon
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.SettingsValidation
import java.util.Locale

/*
 * A saved device's avatar: its initials on a colour of its own, so devices are told apart at a
 * glance rather than each showing the same phone outline.
 */

/** Colour pairs for avatars, chosen for at least 4.5:1 between the letters and the fill. */
object AvatarPalette {
    const val SIZE = 8

    /** Fill and letters in the light theme: pale fills with deep letters. */
    internal val light = listOf(
        Color(0xFFD9EBFF) to Color(0xFF00356E), // blue
        Color(0xFFE3E2FB) to Color(0xFF1B1A52), // indigo
        Color(0xFFF1E0FA) to Color(0xFF4A1466), // purple
        Color(0xFFFDDDEB) to Color(0xFF6B0D3A), // pink
        Color(0xFFFFE3CC) to Color(0xFF6A2E00), // orange
        Color(0xFFFFF0C2) to Color(0xFF5C4300), // amber
        Color(0xFFD8F3DC) to Color(0xFF0D4A1E), // green
        Color(0xFFCDF1F0) to Color(0xFF00454A), // teal
    )

    /** Fill and letters in the dark theme: deep fills with pale letters, the same hues. */
    internal val dark = listOf(
        Color(0xFF00458F) to Color(0xFFD9EBFF),
        Color(0xFF2B2A6B) to Color(0xFFE3E2FB),
        Color(0xFF4E1F66) to Color(0xFFF1E0FA),
        Color(0xFF6B1D45) to Color(0xFFFDDDEB),
        Color(0xFF6A3200) to Color(0xFFFFE3CC),
        Color(0xFF5C4600) to Color(0xFFFFF0C2),
        Color(0xFF154E26) to Color(0xFFD8F3DC),
        Color(0xFF004F54) to Color(0xFFCDF1F0),
    )
}

/**
 * Up to two initials for [name]: the first letters of its first two words, or null when the name
 * does not start with a letter or is an IP address. A device saved without a name is named after
 * its address, and "19" would read as a number rather than as a device, so it gets the device icon
 * instead. An IPv6 address can start with a letter, as fd00::5 does, so one is recognised the way
 * the address field recognises it; a name that merely contains a colon keeps its initials.
 *
 * Scripts without letter case, such as Hangul and Han, give one character: two side by side read
 * as a word rather than as initials.
 */
fun deviceMonogram(name: String): String? {
    val trimmed = name.trim()
    if (':' in trimmed && SettingsValidation.endpointError(trimmed, ANY_PORT) == null) return null
    val words = trimmed.split(WORD_SEPARATORS).filter(String::isNotEmpty)
    val first = words.firstOrNull()?.codePointAt(0) ?: return null
    if (!Character.isLetter(first)) return null
    val initial = String(Character.toChars(first))
    if (!hasCase(first)) return initial
    val second = words.getOrNull(1)?.codePointAt(0)
        ?.takeIf { (Character.isLetter(it) && hasCase(it)) || Character.isDigit(it) }
        ?.let { String(Character.toChars(it)) }
        .orEmpty()
    return (initial + second).uppercase(Locale.ROOT)
}

/**
 * Which [AvatarPalette] colour a device gets. Keyed on the device's id rather than its name, so a
 * renamed device keeps its colour and a duplicate gets its own. The hash is mixed before it is
 * reduced: short or sequential ids differ only in their low bits, which alone would give
 * neighbouring devices the same colour.
 */
fun avatarPaletteIndex(id: String): Int {
    var hash = id.hashCode()
    hash = (hash xor (hash ushr 16)) * 0x45d9f3b
    hash = (hash xor (hash ushr 16)) * 0x45d9f3b
    hash = hash xor (hash ushr 16)
    return Math.floorMod(hash, AvatarPalette.SIZE)
}

/**
 * Colours for the devices [ids], in their saved order. Each device takes the colour its id picks
 * unless an earlier device already has it, and then the next free one: chosen from the id alone,
 * three devices shared a colour one time in three. So up to eight devices all differ, a device
 * keeps its colour when renamed, and adding a device, which goes to the end, never changes
 * another's. Past eight, a new round of all eight colours begins.
 */
fun avatarPaletteIndices(ids: List<String>): Map<String, Int> {
    val taken = BooleanArray(AvatarPalette.SIZE)
    var free = AvatarPalette.SIZE
    val colours = LinkedHashMap<String, Int>()
    for (id in ids) {
        if (id in colours) continue
        if (free == 0) {
            taken.fill(false)
            free = AvatarPalette.SIZE
        }
        var index = avatarPaletteIndex(id)
        while (taken[index]) index = (index + PROBE_STEP) % AvatarPalette.SIZE
        taken[index] = true
        free--
        colours[id] = index
    }
    return colours
}

/**
 * Each saved device's avatar colour, by id: the one the user chose, or the automatic one.
 *
 * Automatic colours are handed out across every device in saved order, chosen ones included, so
 * choosing a colour for one device changes that device and no other. A chosen colour may match
 * another device's automatic one; that is the user's choice to make.
 */
fun deviceAvatarColors(devices: List<SavedDevice>): Map<String, Int> {
    val automatic = avatarPaletteIndices(devices.map { it.id })
    return devices.associate { it.id to (it.avatarColor ?: automatic.getValue(it.id)) }
}

/**
 * What an avatar shows, for tests: `initials:LR`, or `icon:` and a [com.scrcpy.remote.data.DeviceIcon]
 * name, or `icon:default`. A custom key, so screen readers never read it.
 */
val AvatarContent = SemanticsPropertyKey<String>("AvatarContent")

private val WORD_SEPARATORS = Regex("[\\s_\\-]+")

/**
 * How far along the palette a device moves when its colour is taken: three, so it lands on a
 * clearly different hue (blue to pink, not blue to indigo), and since three and eight share no
 * factor, every colour is reached.
 */
private const val PROBE_STEP = 3

/** Any valid port: only the address part of the check matters for a name. */
private const val ANY_PORT = 5555

private fun hasCase(codePoint: Int): Boolean =
    Character.isUpperCase(codePoint) || Character.isLowerCase(codePoint) || Character.isTitleCase(codePoint)

/** The fill and letter colours of palette colour [index] in the current theme. */
@Composable
fun avatarColors(index: Int): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return (if (dark) AvatarPalette.dark else AvatarPalette.light)[Math.floorMod(index, AvatarPalette.SIZE)]
}

/** The picture for a kind of device. */
fun DeviceIcon.vector(): ImageVector = when (this) {
    DeviceIcon.PHONE -> Icons.Outlined.PhoneAndroid
    DeviceIcon.TABLET -> Icons.Outlined.TabletAndroid
    DeviceIcon.TV -> Icons.Outlined.Tv
    DeviceIcon.WATCH -> Icons.Outlined.Watch
}

/**
 * The avatar itself, in the [AvatarPalette] colour [paletteIndex], showing [icon] or else the
 * name's initials. Decorative: the device's name is read beside it, and initials read aloud
 * would only repeat it badly, so it carries no semantics a screen reader would announce.
 */
@Composable
fun DeviceAvatar(
    name: String,
    paletteIndex: Int,
    modifier: Modifier = Modifier,
    icon: DeviceIcon? = null,
    size: Dp = 44.dp,
) {
    val (fill, letters) = avatarColors(paletteIndex)
    val monogram = remember(name) { deviceMonogram(name) }
    val content = when {
        icon != null -> "icon:${icon.name}"
        monogram != null -> "initials:$monogram"
        else -> "icon:default"
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .testTag("device-avatar")
            .clearAndSetSemantics { this[AvatarContent] = content },
        contentAlignment = Alignment.Center,
    ) {
        if (icon == null && monogram != null) {
            // Sized from the circle, not the font scale: larger text would only overflow it, and
            // the name beside it already grows with the user's text size.
            val fontSize = with(LocalDensity.current) { (size * if (monogram.length > 1) 0.36f else 0.44f).toSp() }
            Text(monogram, color = letters, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
        } else {
            Icon((icon ?: DeviceIcon.PHONE).vector(), null, Modifier.size(size * 0.5f), tint = letters)
        }
    }
}
