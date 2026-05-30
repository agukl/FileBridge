package com.acme.ftpsync.license;

import java.util.Map;

final class LicenseKeys {
    static final String DEFAULT_KEY_ID = "prod-2026-01";

    /*
     * Production builds should replace this development public key with the
     * Ed25519 public key paired with the private key held by the activation site.
     */
    static final Map<String, String> ED25519_PUBLIC_KEYS = Map.of(
            DEFAULT_KEY_ID,
            "MCowBQYDK2VwAyEAkLHA6amP84EdnPIoS/9hek4ZcegMzh9JTWMDNH/1k7M="
    );

    private LicenseKeys() {
    }
}
