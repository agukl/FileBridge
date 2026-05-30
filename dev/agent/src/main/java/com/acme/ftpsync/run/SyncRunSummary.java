package com.acme.ftpsync.run;

public record SyncRunSummary(
        String finalHealth,
        long itemCount,
        long fileCount,
        long directoryCount,
        long totalBytes,
        long warningCount,
        long errorCount,
        String lastErrorCategory,
        String lastErrorMessage
) {
}
