package com.acme.ftpsync.security;

import com.acme.ftpsync.config.FingerprintHash;

import javax.net.ssl.X509TrustManager;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;

public final class PinningTrustManager implements X509TrustManager {
    private final String expectedFingerprint;
    private final String digestName;

    public PinningTrustManager(String expectedFingerprint, FingerprintHash hash) {
        this.expectedFingerprint = normalize(expectedFingerprint);
        this.digestName = hash.digestName();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {
        // This FTP client only verifies the server certificate.
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Empty certificate chain from server.");
        }
        String actualFingerprint = fingerprint(chain[0]);
        if (!expectedFingerprint.equals(actualFingerprint)) {
            throw new CertificateException("Server certificate fingerprint mismatch. expected="
                    + expectedFingerprint + ", actual=" + actualFingerprint);
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    private String fingerprint(X509Certificate cert) throws CertificateException {
        try {
            MessageDigest digest = MessageDigest.getInstance(digestName);
            return toUpperHex(digest.digest(cert.getEncoded()));
        } catch (NoSuchAlgorithmException ex) {
            throw new CertificateException("Unsupported hash algorithm: " + digestName, ex);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
    }

    private static String toUpperHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
