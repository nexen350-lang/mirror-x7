package io.github.muntashirakon.adb;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A fake adbd that refuses the signature, takes the public key, and accepts only after a delay, as a
 * device does while its user reads "Allow debugging?".
 */
public class UserAuthorisationTimeoutTest {

    private static final int API = 30;

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair rsa = generator.generateKeyPair();
        long now = System.currentTimeMillis();
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(new X500Name("CN=test"), BigInteger.ONE, new Date(now - 60_000),
                        new Date(now + 3_600_000), new X500Name("CN=test"), rsa.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withRSA").build(rsa.getPrivate())));
        return new KeyPair(rsa.getPrivate(), cert);
    }

    /** Serves one client; accepts [acceptAfterMs] after receiving the public key, or never when negative. */
    private static Thread fakeDaemon(ServerSocket server, long acceptAfterMs) {
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                int version = AdbProtocol.getProtocolVersion(API);
                int maxData = AdbProtocol.getMaxData(API);
                AdbProtocol.Message.parse(in, version, maxData); // CNXN
                byte[] token = new byte[20];
                out.write(AdbProtocol.generateMessage(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_TOKEN, 0, token));
                AdbProtocol.Message.parse(in, version, maxData); // signature, refused:
                out.write(AdbProtocol.generateMessage(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_TOKEN, 0, token));
                AdbProtocol.Message.parse(in, version, maxData); // public key: the prompt is showing
                if (acceptAfterMs < 0) {
                    Thread.sleep(60_000);
                    return;
                }
                Thread.sleep(acceptAfterMs);
                out.write(AdbProtocol.generateMessage(AdbProtocol.A_CNXN, version, maxData,
                        "device::ro.product.name=fake;".getBytes("UTF-8")));
                out.flush();
                Thread.sleep(2_000);
            } catch (Exception ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static AdbConnection connection(ServerSocket server, KeyPair keys) throws Exception {
        AdbConnection connection = new AdbConnection.Builder("127.0.0.1", server.getLocalPort())
                .setApi(API).setKeyPair(keys).setConnectTimeout(2, TimeUnit.SECONDS).build();
        connection.setDeviceName("test");
        return connection;
    }

    @Test
    public void aSlowUserIsGivenTheExtraTimeOnceThePromptShows() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            fakeDaemon(server, 1_500);
            AdbConnection connection = connection(server, keyPair());
            AtomicInteger prompts = new AtomicInteger();
            connection.setUserAuthorisationListener(prompts::incrementAndGet);
            connection.setUserAuthorisationTimeout(5, TimeUnit.SECONDS);
            try {
                // A 500 ms network timeout alone would give up before the user accepts.
                assertTrue(connection.connect(500, TimeUnit.MILLISECONDS, false));
                assertEquals(1, prompts.get());
                assertTrue(connection.isAwaitingUserAuthorisation());
            } finally {
                connection.close();
            }
        }
    }

    @Test
    public void withoutTheExtensionTheOldTimeoutStillApplies() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            fakeDaemon(server, 1_500);
            AdbConnection connection = connection(server, keyPair());
            try {
                assertFalse(connection.connect(500, TimeUnit.MILLISECONDS, false));
            } finally {
                connection.close();
            }
        }
    }

    @Test
    public void theExtensionIsBoundedWhenNobodyAnswers() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            fakeDaemon(server, -1);
            AdbConnection connection = connection(server, keyPair());
            connection.setUserAuthorisationTimeout(1, TimeUnit.SECONDS);
            long start = System.nanoTime();
            try {
                assertFalse(connection.connect(300, TimeUnit.MILLISECONDS, false));
            } finally {
                connection.close();
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue("waited " + elapsedMs + " ms", elapsedMs < 3_000);
        }
    }
}
