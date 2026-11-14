// SPDX-License-Identifier: Apache-2.0
// Added for Scrcpy Remote; see third_party/libadb/PATCHES.txt.

package io.github.muntashirakon.adb;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class AdbTrustManagerTest {
    private static final KeyPair DEVICE = rsa(2048);
    private static final KeyPair OTHER = rsa(2048);
    // The subject AOSP's GenerateX509Certificate gives adbd's certificates.
    private static final String ADBD = "C=US, O=Android, CN=Adb";
    private final AdbTrustManager trust = new AdbTrustManager();

    @Test
    public void aCertificateShapedLikeAdbdsIsAccepted() throws Exception {
        X509Certificate[] chain = {certificate(DEVICE.getPublic(), ADBD, DEVICE.getPrivate(), ADBD, "SHA256withRSA")};
        trust.checkServerTrusted(chain, "RSA");
        trust.checkServerTrusted(chain, "GENERIC");
    }

    @Test
    public void anEllipticCurveKeyOfAdequateStrengthIsAccepted() throws Exception {
        KeyPair ec = ec();
        trust.checkServerTrusted(new X509Certificate[]{certificate(ec.getPublic(), ADBD, ec.getPrivate(), ADBD,
                "SHA256withECDSA")}, "EC");
    }

    @Test
    public void aCertificateIssuedByAnAuthorityIsRefused() throws Exception {
        // An intercepting proxy presents a certificate its own authority issued.
        X509Certificate authority = certificate(OTHER.getPublic(), "CN=Proxy CA", OTHER.getPrivate(), "CN=Proxy CA",
                "SHA256withRSA");
        X509Certificate issued = certificate(DEVICE.getPublic(), ADBD, OTHER.getPrivate(), "CN=Proxy CA", "SHA256withRSA");
        assertRefused(new X509Certificate[]{issued, authority});
        assertRefused(new X509Certificate[]{issued});
    }

    @Test
    public void aCertificateNamingItsOwnIssuerButSignedByAnotherKeyIsRefused() throws Exception {
        assertRefused(new X509Certificate[]{certificate(DEVICE.getPublic(), ADBD, OTHER.getPrivate(), ADBD,
                "SHA256withRSA")});
    }

    @Test
    public void aWeakKeyIsRefused() throws Exception {
        KeyPair weak = rsa(1024);
        assertRefused(new X509Certificate[]{certificate(weak.getPublic(), ADBD, weak.getPrivate(), ADBD,
                "SHA256withRSA")});
    }

    @Test
    public void aSecondCertificateBesideAdbdsIsRefused() throws Exception {
        X509Certificate device = certificate(DEVICE.getPublic(), ADBD, DEVICE.getPrivate(), ADBD, "SHA256withRSA");
        X509Certificate other = certificate(OTHER.getPublic(), "CN=Other", OTHER.getPrivate(), "CN=Other", "SHA256withRSA");
        assertRefused(new X509Certificate[]{device, other});
    }

    @Test
    public void aKeyThatIsNeitherRsaNorEcIsRefused() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("DSA");
        generator.initialize(2048);
        KeyPair dsa = generator.generateKeyPair();
        assertRefused(new X509Certificate[]{certificate(dsa.getPublic(), ADBD, dsa.getPrivate(), ADBD, "SHA256withDSA")});
    }

    @Test
    public void aWeakEllipticCurveIsRefused() throws Exception {
        // The JDK no longer makes keys this small, so Bouncy Castle makes and reads this one.
        BouncyCastleProvider bc = new BouncyCastleProvider();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", bc);
        generator.initialize(new ECGenParameterSpec("secp192r1"));
        KeyPair weak = generator.generateKeyPair();
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(new X500Name(ADBD), BigInteger.ONE,
                new Date(now - 60_000), new Date(now + 3_600_000), new X500Name(ADBD), weak.getPublic());
        X509Certificate certificate = new JcaX509CertificateConverter().setProvider(bc).getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider(bc).build(weak.getPrivate())));
        assertRefused(new X509Certificate[]{certificate});
    }
    @Test
    public void noCertificateIsRefused() {
        assertRefused(null);
        assertRefused(new X509Certificate[0]);
    }

    @Test
    public void clientCertificatesAreNeverTrusted() throws Exception {
        X509Certificate[] chain = {certificate(DEVICE.getPublic(), ADBD, DEVICE.getPrivate(), ADBD, "SHA256withRSA")};
        assertThrows(CertificateException.class, () -> trust.checkClientTrusted(chain, "RSA"));
    }

    @Test
    public void noAuthorityIsAdvertised() {
        assertEquals(0, trust.getAcceptedIssuers().length);
    }

    private void assertRefused(X509Certificate[] chain) {
        assertThrows(CertificateException.class, () -> trust.checkServerTrusted(chain, "RSA"));
    }

    private static X509Certificate certificate(PublicKey subjectKey, String subject, PrivateKey signer, String issuer,
                                               String algorithm) throws Exception {
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(new X500Name(issuer), BigInteger.ONE,
                new Date(now - 60_000), new Date(now + 3_600_000), new X500Name(subject), subjectKey);
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder(algorithm).build(signer)));
    }

    private static KeyPair rsa(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static KeyPair ec() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
}
