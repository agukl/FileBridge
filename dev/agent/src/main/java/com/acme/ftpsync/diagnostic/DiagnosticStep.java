package com.acme.ftpsync.diagnostic;

public record DiagnosticStep(
        long id,
        long diagnosticId,
        int seq,
        String stepName,
        String state,
        String errorCategory,
        boolean retryable,
        Integer replyCode,
        String replyText,
        String remotePath,
        String localPath,
        Long durationMs,
        String handlingAction,
        String healthImpact,
        String message,
        String detailsText
) {
}
