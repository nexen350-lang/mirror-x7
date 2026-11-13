package com.scrcpy.remote.scrcpy

import androidx.annotation.StringRes
import com.scrcpy.remote.R

/** The sole bundled, unmodified upstream server. Historical tokens migrate to this release. */
@kotlinx.serialization.Serializable(with = ScrcpyServerVersionSerializer::class)
enum class ScrcpyServerVersion(
    val version: String,
    val sha256: String,
    val size: Int,
    @StringRes val labelRes: Int,
) {
    // Preserve the persisted identifier used by existing settings and backups.
    V5_0("5.0.1", "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536", 733_930,
        R.string.server_v50),
    ;

    val assetName: String get() = "scrcpy-server-v$version"
    val remotePath: String get() = "/data/local/tmp/scrcpy-server-$version.jar"

    companion object {
        val DEFAULT: ScrcpyServerVersion = V5_0

        /** Compatibility parsing only: no historical token can deploy an older server. */
        fun of(version: String): ScrcpyServerVersion? = when (version) {
            "4.1", "5.0", "5.0.1", "V4_1", "V5_0" -> DEFAULT
            else -> null
        }
    }
}

/** Read old settings/backups without keeping a deployable legacy enum entry. */
object ScrcpyServerVersionSerializer : kotlinx.serialization.KSerializer<ScrcpyServerVersion> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "ScrcpyServerVersion", kotlinx.serialization.descriptors.PrimitiveKind.STRING)

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: ScrcpyServerVersion) =
        encoder.encodeString(value.name)

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ScrcpyServerVersion {
        val token = decoder.decodeString()
        return ScrcpyServerVersion.of(token)
            ?: throw kotlinx.serialization.SerializationException("Unknown scrcpy server: $token")
    }
}
