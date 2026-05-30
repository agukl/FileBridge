package com.acme.ftpsync.license;

import java.util.List;

public record LicenseStatus(
        boolean required,
        boolean valid,
        LicenseState state,
        String message,
        String deviceId,
        String licenseId,
        String customer,
        String edition,
        String issuedAt,
        String expiresAt,
        int graceDays,
        List<String> features,
        LicenseLimit limits,
        String licensePath
) {
}
