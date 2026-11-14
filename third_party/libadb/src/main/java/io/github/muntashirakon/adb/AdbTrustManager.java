// SPDX-License-Identifier: Apache-2.0
// Added for Scrcpy Remote; see third_party/libadb/PATCHES.txt.

package io.github.muntashirakon.adb;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;

import javax.net.ssl.X509TrustManager;

/**
 * Decides which certificate an ADB peer may present over TLS, for pairing and for connecting.
 *
 * <p>ADB gives a client no certificate to trust: adbd presents a self-signed certificate for an RSA
 * key it creates when it starts, and Android's own adb host accepts any server certificate. Pairing
 * is authenticated by SPAKE2 over keying material exported from this TLS session, and the device
 * admits only a paired client key. What a client can check is that the peer presents what adbd
 * always presents: one self-signed certificate for a strong key. Anything else, such as a chain an
 * intercepting proxy's authority issued, is refused.
 */
final class AdbTrustManager implements X509TrustManager {
    private static final int MIN_RSA_BITS = 2048;
    private static final int MIN_EC_BITS = 256;

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("An ADB client does not accept client certificates");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length != 1) {
            throw new CertificateException("An ADB peer presents one self-signed certificate, not "
                    + (chain == null ? 0 : chain.length));
        }
        X509Certificate certificate = chain[0];
        PublicKey key = certificate.getPublicKey();
        if (key instanceof RSAPublicKey) {
            if (((RSAPublicKey) key).getModulus().bitLength() < MIN_RSA_BITS) {
                throw new CertificateException("The ADB peer's RSA key is shorter than " + MIN_RSA_BITS + " bits");
            }
        } else if (key instanceof ECPublicKey) {
            if (((ECPublicKey) key).getParams().getOrder().bitLength() < MIN_EC_BITS) {
                throw new CertificateException("The ADB peer's EC key is shorter than " + MIN_EC_BITS + " bits");
            }
        } else {
            throw new CertificateException("The ADB peer's key is neither RSA nor EC: " + key.getAlgorithm());
        }
        try {
            certificate.verify(key);
        } catch (GeneralSecurityException e) {
            throw new CertificateException("The ADB peer's certificate is not signed by its own key", e);
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}