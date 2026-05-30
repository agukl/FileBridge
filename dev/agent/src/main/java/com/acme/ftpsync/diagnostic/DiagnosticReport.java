package com.acme.ftpsync.diagnostic;

import java.util.List;

public record DiagnosticReport(
        long id,
        String taskId,
        String checkType,
        String state,
        String startedAt,
        String finishedAt,
        Long durationMs,
        String errorCategory,
        String handlingAction,
        String healthImpact,
        String message,
        String detailsText,
        List<DiagnosticStep> steps
) {
}
