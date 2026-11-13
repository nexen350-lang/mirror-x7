package com.scrcpy.remote.session

enum class ProbeStep { NETWORK, ADB_AUTHORIZATION, SERVER }

enum class ProbeStatus { PENDING, RUNNING, PASSED, FAILED, SKIPPED }

data class ProbeStepResult(val step: ProbeStep, val status: ProbeStatus, val error: Throwable? = null)

data class ProbeReport(val steps: List<ProbeStepResult>) {
    val firstFailure: ProbeStepResult? get() = steps.firstOrNull { it.status == ProbeStatus.FAILED }
    val passed: Boolean get() = steps.all { it.status == ProbeStatus.PASSED }
    val diagnosis: ConnectionDiagnosis? get() = firstFailure?.let { failure ->
        val stage = when (failure.step) {
            ProbeStep.NETWORK -> CheckupStage.REACH
            ProbeStep.ADB_AUTHORIZATION -> CheckupStage.AUTHORIZE
            ProbeStep.SERVER -> CheckupStage.DEPLOY
        }
        failure.error?.let { ConnectionDiagnoser.diagnose(it, stage) }
    }

    companion object {
        fun initial() = ProbeReport(ProbeStep.entries.map { ProbeStepResult(it, ProbeStatus.PENDING) })
    }
}

/**
 * Runs read-only checks in order and stops at the first that fails; later steps are SKIPPED.
 * The checks themselves are injected so the ordering and reporting are testable on the JVM.
 * Nothing here pushes files or starts a session.
 */
class ConnectionProbe(
    private val network: () -> Unit,
    private val authorization: () -> Unit,
    private val server: () -> Unit,
) {
    fun run(onUpdate: (ProbeReport) -> Unit = {}): ProbeReport {
        val results = ProbeStep.entries.map { ProbeStepResult(it, ProbeStatus.PENDING) }.toMutableList()
        val checks = listOf(network, authorization, server)
        fun publish() = onUpdate(ProbeReport(results.toList()))
        for ((index, check) in checks.withIndex()) {
            results[index] = results[index].copy(status = ProbeStatus.RUNNING)
            publish()
            try {
                check()
                results[index] = results[index].copy(status = ProbeStatus.PASSED)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                results[index] = results[index].copy(status = ProbeStatus.FAILED, error = e)
                for (later in index + 1 until results.size) results[later] = results[later].copy(status = ProbeStatus.SKIPPED)
                publish()
                return ProbeReport(results.toList())
            }
            publish()
        }
        return ProbeReport(results.toList())
    }
}
