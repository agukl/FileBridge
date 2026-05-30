package com.acme.ftpsync.license;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LicenseLimit(
        int maxFileSources,
        int maxConcurrentOperations
) {
    public static LicenseLimit empty() {
        return new LicenseLimit(0, 0);
    }
}
