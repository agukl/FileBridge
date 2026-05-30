package com.acme.ftpsync.license;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LicenseEnvelope(
        String keyId,
        String payload,
        String signature
) {
}
