package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.MediaProtocolException
import com.scrcpy.remote.scrcpy.ScrcpyServerRefusedException
import com.scrcpy.remote.session.CheckupStage
import com.scrcpy.remote.session.ConnectionDiagnoser
import com.scrcpy.remote.session.ConnectionProbe
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind
import com.scrcpy.remote.session.DiagnosisAction
import com.scrcpy.remote.session.DiagnosisCategory
import com.scrcpy.remote.session.DiagnosticReport
import com.scrcpy.remote.session.ProbeStatus
import com.scrcpy.remote.session.ProbeStep
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionDiagnosisTest {
    private fun category(error: Throwable, stage: CheckupStage? = null) = ConnectionDiagnoser.diagnose(error, stage).category

    @Test fun everyFailureKindHasADiagnosisWithCauseNextStepAndDetails() {
        for (kind in FailureKind.entries) {
            val d = ConnectionDiagnoser.diagnose(kind)
            assertTrue(kind.name, d.cause != 0 && d.nextStep != 0)
            assertTrue(kind.name, DiagnosisAction.COPY_DETAILS in d.actions && DiagnosisAction.DETAILS in d.actions)
            assertTrue(kind.name, DiagnosisAction.RETRY in d.actions)
        }
    }

    @Test fun authenticationOffersRepairAndHelp() {
        val d = ConnectionDiagnoser.diagnose(AdbAuthenticationFailedException())
        assertEquals(DiagnosisCategory.AUTHENTICATION, d.category)
        assertTrue(DiagnosisAction.REPAIR in d.actions && DiagnosisAction.WIRELESS_DEBUGGING_HELP in d.actions)
        assertEquals(DiagnosisCategory.AUTHENTICATION, category(AdbPairingRequiredException("x")))
    }

    @Test fun networkTimeoutRefusedServerCodecAndOther() {
        assertEquals(DiagnosisCategory.NETWORK, category(java.net.NoRouteToHostException("x")))
        assertTrue(DiagnosisAction.OPEN_SETTINGS in ConnectionDiagnoser.diagnose(java.net.NoRouteToHostException("x")).actions)
        assertEquals(DiagnosisCategory.TIMEOUT, category(SocketTimeoutException("t")))
        assertEquals(DiagnosisCategory.CONNECTION_REFUSED, category(java.net.SocketException("ECONNREFUSED")))
        assertEquals(DiagnosisCategory.SERVER_REFUSED, category(ScrcpyServerRefusedException("bad option")))
        assertEquals(DiagnosisCategory.CODEC_INPUT, category(MediaProtocolException("bad frame")))
        assertEquals(DiagnosisCategory.OTHER, category(java.io.IOException("disk")))
        assertEquals(DiagnosisCategory.OTHER, category(java.util.concurrent.CancellationException()))
    }

    @Test fun transportLossAfterTheDeviceAnsweredIsAConnectionLoss() {
        val early = ConnectionDiagnoser.diagnose(ConnectException("x"), CheckupStage.REACH)
        val late = ConnectionDiagnoser.diagnose(ConnectException("x"), CheckupStage.START)
        assertEquals(DiagnosisCategory.NETWORK, late.category)
        assertTrue(early.cause != late.cause)
    }

    @Test fun reportHasEndpointErrorClassAndServerLogTail() {
        val log = (1..50).joinToString("\n") { "line $it" }
        val text = DiagnosticReport.build("192.168.1.9", 37123, ConnectException("refused"),
            DiagnosisCategory.NETWORK, CheckupStage.REACH, log, appVersion = "3.4.0")
        assertTrue(text.contains("Host: 192.168.1.9"))
        assertTrue(text.contains("Port: 37123"))
        assertTrue(text.contains("Error: java.net.ConnectException"))
        assertTrue(text.contains("line 50") && text.contains("line 31"))
        assertFalse(text.contains("line 30\n") || text.endsWith("line 10"))
    }

    @Test fun redactionRemovesKeysCodesAndTokens() {
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----"
        val text = DiagnosticReport.build("h", 1, RuntimeException("pairing code: 123456 password=hunter2"),
            DiagnosisCategory.OTHER, null,
            "$pem\ntoken=abcdef\nsha 764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536\nkeep this")
        for (secret in listOf("BEGIN PRIVATE", "MIIEvQ", "123456", "hunter2", "abcdef", "764eb6f798")) {
            assertFalse(secret, text.contains(secret))
        }
        assertTrue(text.contains("keep this"))
    }

    @Test fun redactionCoversSchemesMultiWordValuesAndKeepsExitCodes() {
        val text = DiagnosticReport.redact(listOf(
            "Authorization: Bearer abc123",
            "Authorization: Basic dXNlcjpw",
            "got Bearer shorttok here",
            "password: correct horse battery staple",
            "api_key=xyz and more",
            "exit code: 1",
            "pairing code: 654321",
            "key frame interval: 5",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIabc\n-----END RSA PRIVATE KEY-----",
        ).joinToString("\n"))
        for (secret in listOf("abc123", "dXNlcjpw", "shorttok", "horse", "staple", "xyz", "654321", "MIIabc")) {
            assertFalse(secret, text.contains(secret))
        }
        assertTrue(text.contains("exit code: 1"))
        assertTrue(text.contains("key frame interval: 5"))
        assertTrue(text.contains("[redacted key]"))
    }

    @Test fun probeRunsInOrderAndStopsAtFirstFailure() {
        val order = mutableListOf<String>()
        val report = ConnectionProbe(
            network = { order += "net" },
            authorization = { order += "adb"; throw AdbAuthenticationFailedException() },
            server = { order += "server" },
        ).run()
        assertEquals(listOf("net", "adb"), order)
        assertEquals(listOf(ProbeStatus.PASSED, ProbeStatus.FAILED, ProbeStatus.SKIPPED), report.steps.map { it.status })
        assertEquals(ProbeStep.ADB_AUTHORIZATION, report.firstFailure?.step)
        assertEquals(DiagnosisCategory.AUTHENTICATION, report.diagnosis?.category)
    }

    @Test fun probeFailingNetworkSkipsTheRestAndAllPassedIsClean() {
        val failed = ConnectionProbe({ throw SocketTimeoutException("t") }, { error("no") }, { error("no") }).run()
        assertEquals(ProbeStep.NETWORK, failed.firstFailure?.step)
        assertEquals(DiagnosisCategory.TIMEOUT, failed.diagnosis?.category)
        val ok = ConnectionProbe({}, {}, {}).run()
        assertTrue(ok.passed)
        assertNull(ok.firstFailure)
        assertNull(ok.diagnosis)
        assertNotNull(failed.diagnosis)
    }

    @Test fun probeReportsProgress() {
        val updates = mutableListOf<List<ProbeStatus>>()
        ConnectionProbe({}, {}, {}).run { updates += it.steps.map { s -> s.status } }
        assertEquals(ProbeStatus.RUNNING, updates.first().first())
        assertTrue(updates.last().all { it == ProbeStatus.PASSED })
    }
}
