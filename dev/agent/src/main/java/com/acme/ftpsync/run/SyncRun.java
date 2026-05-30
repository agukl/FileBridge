package com.acme.ftpsync.run;

public record SyncRun(
        long id,
        String taskId,
        String operationType,
        String sourcePath,
        String targetTaskId,
        String targetPath,
        String conflictPolicy,
        String state,
        String finalHealth,
        String startedAt,
        String finishedAt,
        Long durationMs,
        long itemCount,
        long fileCount,
        long directoryCount,
        long totalBytes,
        long warningCount,
        long errorCount,
        String lastErrorCategory,
        String lastErrorMessage,
        String summaryEventType,
        String summaryLevel,
        String summaryMessage,
        String summarySourcePath,
        String summaryTargetPath,
        String summaryDetailsText,
        String alertEventType,
        String alertMessage
) {
    public SyncRun(
            long id,
            String taskId,
            String operationType,
            String sourcePath,
            String targetTaskId,
            String targetPath,
            String conflictPolicy,
            String state,
            String finalHealth,
            String startedAt,
            String finishedAt,
            Long durationMs,
            long itemCount,
            long fileCount,
            long directoryCount,
            long totalBytes,
            long warningCount,
            long errorCount,
            String lastErrorCategory,
            String lastErrorMessage
    ) {
        this(
                id,
                taskId,
                operationType,
                sourcePath,
                targetTaskId,
                targetPath,
                conflictPolicy,
                state,
                finalHealth,
                startedAt,
                finishedAt,
                durationMs,
                itemCount,
                fileCount,
                directoryCount,
                totalBytes,
                warningCount,
                errorCount,
                lastErrorCategory,
                lastErrorMessage,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );
    }

    public SyncRun withEventSummary(OperationEventSummary summary) {
        if (summary == null) {
            return this;
        }
        return new SyncRun(
                id,
                taskId,
                operationType,
                sourcePath,
                targetTaskId,
                targetPath,
                conflictPolicy,
                state,
                finalHealth,
                startedAt,
                finishedAt,
                durationMs,
                itemCount,
                fileCount,
                directoryCount,
                totalBytes,
                warningCount,
                errorCount,
                lastErrorCategory,
                lastErrorMessage,
                summary.summaryEventType(),
                summary.summaryLevel(),
                summary.summaryMessage(),
                summary.summarySourcePath(),
                summary.summaryTargetPath(),
                summary.summaryDetailsText(),
                summary.alertEventType(),
                summary.alertMessage()
        );
    }

    public record OperationEventSummary(
            String summaryEventType,
            String summaryLevel,
            String summaryMessage,
            String summarySourcePath,
            String summaryTargetPath,
            String summaryDetailsText,
            String alertEventType,
            String alertMessage
    ) {
    }
}
