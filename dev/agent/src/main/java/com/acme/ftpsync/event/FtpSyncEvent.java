package com.acme.ftpsync.event;

public record FtpSyncEvent(
        long id,
        String taskId,
        Long runId,
        long seq,
        String occurredAt,
        String level,
        String phase,
        String operationName,
        String eventType,
        String errorCategory,
        boolean retryable,
        Integer replyCode,
        String replyText,
        String sourcePath,
        String itemPath,
        String itemType,
        Long itemSize,
        Long durationMs,
        String handlingAction,
        String healthImpact,
        String message,
        String detailsText
) {
}
