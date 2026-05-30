package com.acme.ftpsync.config;

public enum FingerprintHash {
    SHA1("SHA-1", 40),
    SHA256("SHA-256", 64);

    private final String digestName;
    private final int hexLength;

    FingerprintHash(String digestName, int hexLength) {
        this.digestName = digestName;
        this.hexLength = hexLength;
    }

    public String digestName() {
        return digestName;
    }

    public int hexLength() {
        return hexLength;
    }

    public static FingerprintHash parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SHA256;
        }
        return switch (raw.trim().toUpperCase()) {
            case "SHA1" -> SHA1;
            case "SHA256" -> SHA256;
            default -> throw new IllegalArgumentException(
                    "Invalid tlsFingerprintHash: '" + raw + "'. Allowed: SHA1, SHA256.");
        };
    }
}
