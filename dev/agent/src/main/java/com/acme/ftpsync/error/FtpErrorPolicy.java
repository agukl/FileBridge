package com.acme.ftpsync.error;

public record FtpErrorPolicy(
        String errorCategory,
        String severity,
        boolean defaultRetryable,
        String handlingAction,
        String healthImpact,
        String displayName,
        String description
) {
}
