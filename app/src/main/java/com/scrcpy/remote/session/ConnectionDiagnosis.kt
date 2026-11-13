package com.scrcpy.remote.session

import androidx.annotation.StringRes
import com.scrcpy.remote.R
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind

enum class DiagnosisCategory { AUTHENTICATION, NETWORK, CONNECTION_REFUSED, SERVER_REFUSED, TIMEOUT, CODEC_INPUT, OTHER }

enum class DiagnosisAction { RETRY, REPAIR, WIRELESS_DEBUGGING_HELP, OPEN_SETTINGS, COPY_DETAILS, DETAILS }

/** What a failed connection means, what to do next, and which one-tap actions apply. */
data class ConnectionDiagnosis(
    val category: DiagnosisCategory,
    val kind: FailureKind,
    @StringRes val cause: Int,
    @StringRes val nextStep: Int,
    val actions: List<DiagnosisAction>,
)

/**
 * Turns the retry policy's [FailureKind] into a diagnosis. Kept free of framework types beyond
 * string ids so every category is unit-testable; the policy stays the one classifier.
 */
object ConnectionDiagnoser {
    fun diagnose(error: Throwable, stage: CheckupStage? = null): ConnectionDiagnosis =
        diagnose(ConnectionRecoveryPolicy.classify(error), stage)

    fun diagnose(kind: FailureKind, stage: CheckupStage? = null): ConnectionDiagnosis {
        val common = listOf(DiagnosisAction.COPY_DETAILS, DiagnosisAction.DETAILS)
        return when (kind) {
            FailureKind.AUTHENTICATION, FailureKind.PAIRING_REQUIRED -> ConnectionDiagnosis(
                DiagnosisCategory.AUTHENTICATION, kind, R.string.diag_auth_cause, R.string.diag_auth_next,
                listOf(DiagnosisAction.RETRY, DiagnosisAction.REPAIR, DiagnosisAction.WIRELESS_DEBUGGING_HELP) + common)
            FailureKind.TRANSIENT_TRANSPORT ->
                // After the device's port answered, this is a connection that dropped, not a bad address.
                if (stage != null && stage > CheckupStage.REACH) ConnectionDiagnosis(
                    DiagnosisCategory.NETWORK, kind, R.string.diag_lost_cause, R.string.diag_lost_next,
                    listOf(DiagnosisAction.RETRY, DiagnosisAction.OPEN_SETTINGS) + common)
                else ConnectionDiagnosis(
                    DiagnosisCategory.NETWORK, kind, R.string.diag_network_cause, R.string.diag_network_next,
                    listOf(DiagnosisAction.RETRY, DiagnosisAction.OPEN_SETTINGS, DiagnosisAction.WIRELESS_DEBUGGING_HELP) + common)
            FailureKind.CONNECTION_REFUSED -> ConnectionDiagnosis(
                DiagnosisCategory.CONNECTION_REFUSED, kind, R.string.diag_refused_cause, R.string.diag_refused_next,
                listOf(DiagnosisAction.RETRY, DiagnosisAction.WIRELESS_DEBUGGING_HELP, DiagnosisAction.REPAIR) + common)
            FailureKind.INVALID_INPUT -> ConnectionDiagnosis(
                DiagnosisCategory.SERVER_REFUSED, kind, R.string.diag_server_cause, R.string.diag_server_next,
                listOf(DiagnosisAction.RETRY) + common)
            FailureKind.NOT_RESPONDING -> ConnectionDiagnosis(
                DiagnosisCategory.TIMEOUT, kind, R.string.diag_timeout_cause, R.string.diag_timeout_next,
                listOf(DiagnosisAction.RETRY, DiagnosisAction.OPEN_SETTINGS) + common)
            FailureKind.CODEC_OR_INPUT, FailureKind.PROTOCOL -> ConnectionDiagnosis(
                DiagnosisCategory.CODEC_INPUT, kind, R.string.diag_codec_cause, R.string.diag_codec_next,
                listOf(DiagnosisAction.RETRY) + common)
            FailureKind.CANCELLED, FailureKind.OTHER -> ConnectionDiagnosis(
                DiagnosisCategory.OTHER, kind, R.string.diag_other_cause, R.string.diag_other_next,
                listOf(DiagnosisAction.RETRY) + common)
        }
    }
}

/** The text behind "Copy diagnostic details". Never includes keys, codes or credentials. */
object DiagnosticReport {
    private const val MAX_LOG_LINES = 20
    private const val MAX_LOG_CHARS = 2_000
    private const val MAX_FIELD_CHARS = 300

    fun build(
        host: String,
        port: Int,
        error: Throwable?,
        category: DiagnosisCategory,
        stage: CheckupStage?,
        serverLog: String,
        appVersion: String? = null,
    ): String = buildString {
        appVersion?.let { appendLine("App: ${redact(it)}") }
        appendLine("Host: ${redact(host)}")
        appendLine("Port: $port")
        appendLine("Category: $category")
        stage?.let { appendLine("Stage: $it") }
        error?.let {
            appendLine("Error: ${it.javaClass.name}")
            appendLine("Message: ${redact(ConnectionFailureGuidance.technicalDetail(it)).take(MAX_FIELD_CHARS)}")
        }
        val tail = logTail(serverLog)
        if (tail.isNotEmpty()) {
            appendLine("Server log (tail):")
            append(tail)
        }
    }.trimEnd()

    fun logTail(log: String): String =
        redact(log).lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }
            .toList().takeLast(MAX_LOG_LINES).joinToString("\n").takeLast(MAX_LOG_CHARS)

    private val PEM = Regex("-----BEGIN [^-]+-----[\\s\\S]*?(-----END [^-]+-----|$)")
    // A sensitive key name followed by = or :, then the rest of the line: values such as
    // "Bearer <token>" or a multi-word password are more than one word. Bare "code" and "key"
    // are not sensitive names ("exit code: 1" must survive).
    private val ASSIGNMENT = Regex(
        "(?i)\\b([\\w.-]*?(?:pass(?:word|phrase|wd)?|pairing[ _-]?code|token|secret|api[ _-]?key|private[ _-]?key|authorization|credential\\w*))\\b(\\s*[:=]\\s*)[^\\r\\n]*")
    private val SCHEME = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+")
    private val LONG_TOKEN = Regex("[A-Za-z0-9+/_=-]{32,}")

    /** Masks private keys, secret-looking assignments and long opaque tokens (key material, hashes). */
    fun redact(text: String): String = text
        .replace(PEM, "[redacted key]")
        .replace(ASSIGNMENT) { "${it.groupValues[1]}${it.groupValues[2]}[redacted]" }
        .replace(SCHEME) { "${it.groupValues[1]} [redacted]" }
        .replace(LONG_TOKEN, "[redacted]")
}
