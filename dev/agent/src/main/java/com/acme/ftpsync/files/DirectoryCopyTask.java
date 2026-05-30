package com.acme.ftpsync.files;

import java.util.List;

public record DirectoryCopyTask(
        long id,
        String name,
        String sourceFileSourceId,
        List<String> sourcePaths,
        String targetFileSourceId,
        String targetDirectory,
        String compareMode,
        int mtimeToleranceSeconds,
        String conflictPolicy,
        boolean scheduleEnabled,
        String scheduleType,
        int scheduleIntervalMinutes,
        String scheduleTimeOfDay,
        String scheduleTimezone,
        String nextRunAt,
        boolean enabled,
        Long lastOperationId,
        String lastStatus,
        String lastStartedAt,
        String lastFinishedAt,
        String lastMessage
) {
}
