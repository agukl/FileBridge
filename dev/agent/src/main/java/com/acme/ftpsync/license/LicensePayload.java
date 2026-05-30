package com.acme.ftpsync.license;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LicensePayload(
        int schema,
        String product,
        String licenseId,
        String customer,
        String edition,
        String deviceId,
        String issuedAt,
        String expiresAt,
        List<String> features,
        LicenseLimit limits,
        int graceDays
) {
}
