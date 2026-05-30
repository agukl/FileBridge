package com.acme.ftpsync.dashboard;

import com.acme.ftpsync.diagnostic.DiagnosticReport;
import com.acme.ftpsync.diagnostic.RemoteDirectoryCacheRepository;
import com.acme.ftpsync.diagnostic.TaskDiagnosticRepository;
import com.acme.ftpsync.event.FtpSyncEvent;
import com.acme.ftpsync.event.FtpSyncEventRepository;
import com.acme.ftpsync.run.SyncRun;
import com.acme.ftpsync.run.SyncRunRepository;
import com.acme.ftpsync.task.FileSourcePermission;
import com.acme.ftpsync.task.SyncTask;
import com.acme.ftpsync.task.TaskRepository;
import com.acme.ftpsync.util.DateTimes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DashboardService {
    private static final int DEFAULT_TASK_LIMIT = 100;
    private static final int DEFAULT_BOARD_HOURS = 24;
    private static final int MAX_BOARD_HOURS = 24 * 14;
    private static final int DEFAULT_BOARD_RUN_LIMIT = 4_000;
    private static final int DEFAULT_BOARD_EVENT_LIMIT = 6_000;
    private static final int MAX_BOARD_SAMPLE_LIMIT = 20_000;
    private static final int BOARD_BREAKDOWN_LIMIT = 6;
    private static final int BOARD_ALERT_LIMIT = 8;
    private static final DateTimeFormatter BOARD_SHORT_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter BOARD_LONG_LABEL = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final TaskRepository taskRepository;
    private final SyncRunRepository runRepository;
    private final FtpSyncEventRepository eventRepository;
    private final TaskDiagnosticRepository diagnosticRepository;
    private final RemoteDirectoryCacheRepository remoteDirectoryCacheRepository;

    public DashboardService(
            TaskRepository taskRepository,
            SyncRunRepository runRepository,
            FtpSyncEventRepository eventRepository,
            TaskDiagnosticRepository diagnosticRepository,
            RemoteDirectoryCacheRepository remoteDirectoryCacheRepository) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.diagnosticRepository = diagnosticRepository;
        this.remoteDirectoryCacheRepository = remoteDirectoryCacheRepository;
    }

    public DashboardOverview overview(int taskLimit) throws SQLException {
        int safeLimit = safeLimit(taskLimit, DEFAULT_TASK_LIMIT, 500);
        List<TaskCard> cards = new ArrayList<>();

        for (SyncTask task : taskRepository.list(safeLimit)) {
            cards.add(buildCard(task));
        }

        return new DashboardOverview(DateTimes.nowDatabase(), cards);
    }

    public BoardOverview board(int hours, int runLimit, int eventLimit) throws SQLException {
        int safeHours = safePositiveLimit(hours, DEFAULT_BOARD_HOURS, MAX_BOARD_HOURS);
        int safeRunLimit = safePositiveLimit(runLimit, DEFAULT_BOARD_RUN_LIMIT, MAX_BOARD_SAMPLE_LIMIT);
        int safeEventLimit = safePositiveLimit(eventLimit, DEFAULT_BOARD_EVENT_LIMIT, MAX_BOARD_SAMPLE_LIMIT);

        LocalDateTime endedAt = LocalDateTime.now();
        LocalDateTime startedAt = endedAt.minusHours(safeHours);
        String startedAtText = DateTimes.formatDatabase(startedAt);
        String endedAtText = DateTimes.formatDatabase(endedAt);

        List<SyncRun> sampledRuns = runRepository.listSince(startedAtText, safeRunLimit + 1);
        boolean truncatedRuns = sampledRuns.size() > safeRunLimit;
        if (truncatedRuns) {
            sampledRuns = new ArrayList<>(sampledRuns.subList(0, safeRunLimit));
        }

        List<FtpSyncEvent> sampledEvents = eventRepository.listSince(startedAtText, safeEventLimit + 1);
        boolean truncatedEvents = sampledEvents.size() > safeEventLimit;
        if (truncatedEvents) {
            sampledEvents = new ArrayList<>(sampledEvents.subList(0, safeEventLimit));
        }

        Map<String, String> taskLabels = taskLabels();
        List<SyncRun> operationRuns = new ArrayList<>();
        for (SyncRun run : sampledRuns) {
            if (isVisibleOperationRecord(run.operationType())) {
                operationRuns.add(run);
            }
        }

        SummaryAccumulator summary = new SummaryAccumulator();
        Map<String, Long> operationTypeCounts = new LinkedHashMap<>();
        Map<String, Long> taskCounts = new LinkedHashMap<>();
        Map<String, Long> eventLevelCounts = new LinkedHashMap<>();
        Map<String, Long> errorCategoryCounts = new LinkedHashMap<>();
        Map<String, Long> eventTypeCounts = new LinkedHashMap<>();

        List<TrendAccumulator> trendAccumulators = createTrendAccumulators(startedAt, endedAt, safeHours);
        long bucketMinutes = resolveBucketMinutes(startedAt, endedAt, trendAccumulators.size());

        for (SyncRun run : operationRuns) {
            summary.addRun(run);
            increment(operationTypeCounts, value(run.operationType(), "FILE_OPERATION").toUpperCase(Locale.ROOT));
            increment(taskCounts, value(run.taskId(), "UNKNOWN"));

            LocalDateTime runTime = DateTimes.parseDatabase(run.startedAt());
            if (runTime != null) {
                TrendAccumulator bucket = resolveTrendBucket(trendAccumulators, startedAt, runTime, bucketMinutes);
                if (bucket != null) {
                    bucket.addRun(run);
                }
            }
        }

        for (FtpSyncEvent event : sampledEvents) {
            summary.addEvent(event);
            String level = value(event.level(), "INFO").toUpperCase(Locale.ROOT);
            increment(eventLevelCounts, level);
            increment(eventTypeCounts, value(event.eventType(), "UNKNOWN").toUpperCase(Locale.ROOT));
            if (notBlank(event.errorCategory())) {
                increment(errorCategoryCounts, event.errorCategory().trim().toUpperCase(Locale.ROOT));
            }

            LocalDateTime eventTime = DateTimes.parseDatabase(event.occurredAt());
            if (eventTime != null) {
                TrendAccumulator bucket = resolveTrendBucket(trendAccumulators, startedAt, eventTime, bucketMinutes);
                if (bucket != null) {
                    bucket.addEvent(event);
                }
            }
        }

        List<BoardTrendBucket> trend = new ArrayList<>();
        for (TrendAccumulator accumulator : trendAccumulators) {
            trend.add(accumulator.snapshot(safeHours));
        }

        return new BoardOverview(
                DateTimes.nowDatabase(),
                safeHours,
                startedAtText,
                endedAtText,
                truncatedRuns,
                truncatedEvents,
                summary.snapshot(),
                trend,
                topItems(operationTypeCounts, summary.totalRuns, Map.of()),
                topItems(eventLevelCounts, summary.totalEvents, Map.of()),
                topItems(errorCategoryCounts, summary.totalEvents, Map.of()),
                topItems(eventTypeCounts, summary.totalEvents, Map.of()),
                topItems(taskCounts, summary.totalRuns, taskLabels),
                recentAlerts(sampledEvents, taskLabels)
        );
    }

    private TaskCard buildCard(SyncTask task) throws SQLException {
        List<SyncRun> latestRuns = runRepository.listByTask(task.taskId(), 1);
        SyncRun latestRun = latestRuns.isEmpty() ? null : latestRuns.get(0);
        SyncRun latestCacheRefresh = runRepository.findLatestByTaskAndOperation(
                task.taskId(),
                "DIRECTORY_CACHE_REFRESH"
        ).orElse(null);
        DiagnosticReport latestDiagnostic = latestDiagnostic(task.taskId());
        String state = displayState(latestRun);
        String health = displayHealth(latestRun);
        return new TaskCard(
                toProfile(task),
                false,
                state,
                health,
                latestDiagnostic,
                latestRun,
                latestCacheRefresh
        );
    }

    private DiagnosticReport latestDiagnostic(String taskId) throws SQLException {
        List<DiagnosticReport> diagnostics = diagnosticRepository.listByTask(taskId, 1);
        return diagnostics.isEmpty() ? null : diagnostics.get(0);
    }

    private TaskProfile toProfile(SyncTask task) {
        return new TaskProfile(
                task.taskId(),
                task.taskName(),
                task.sourceType(),
                task.ftpHost(),
                task.ftpPort(),
                task.ftpUsername(),
                task.secureMode(),
                task.tlsFingerprint(),
                task.tlsFingerprintHash(),
                task.sourcePath(),
                task.remoteDirectoryCacheEnabled(),
                task.permission() == null ? FileSourcePermission.unknown() : task.permission(),
                cacheStats(task)
        );
    }

    private RemoteDirectoryCacheRepository.CacheStats cacheStats(SyncTask task) {
        try {
            return remoteDirectoryCacheRepository.stats(task);
        } catch (Exception ex) {
            return RemoteDirectoryCacheRepository.CacheStats.failed(ex.getMessage());
        }
    }

    private static String displayState(SyncRun latestRun) {
        if (latestRun == null) {
            return "NEVER_RUN";
        }
        return value(latestRun.state(), "UNKNOWN").toUpperCase();
    }

    private static String displayHealth(SyncRun latestRun) {
        if (latestRun == null) {
            return "UNKNOWN";
        }
        if (notBlank(latestRun.finalHealth())) {
            return latestRun.finalHealth().toUpperCase();
        }
        if ("FAILED".equalsIgnoreCase(latestRun.state())) {
            return value(latestRun.lastErrorCategory(), "FAILED").toUpperCase();
        }
        return value(latestRun.state(), "UNKNOWN").toUpperCase();
    }

    private static int safeLimit(int requested, int fallback, int max) {
        if (requested <= 0) {
            return fallback;
        }
        return Math.max(1, Math.min(requested, max));
    }

    private static int safePositiveLimit(int requested, int fallback, int max) {
        if (requested <= 0) {
            return fallback;
        }
        return Math.max(1, Math.min(requested, max));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Map<String, String> taskLabels() throws SQLException {
        Map<String, String> labels = new HashMap<>();
        for (SyncTask task : taskRepository.list(500)) {
            labels.put(task.taskId(), value(task.taskName(), task.taskId()));
        }
        return labels;
    }

    private static boolean isVisibleOperationRecord(String operationType) {
        String normalized = value(operationType, "").toUpperCase(Locale.ROOT);
        return !"CONNECTION_TEST".equals(normalized)
                && !"FILE_BROWSE".equals(normalized)
                && !"REMOTE_BROWSE".equals(normalized);
    }

    private static void increment(Map<String, Long> counts, String key) {
        counts.merge(value(key, "UNKNOWN"), 1L, Long::sum);
    }

    private static List<TrendAccumulator> createTrendAccumulators(
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            int hours
    ) {
        int bucketCount = Math.max(1, Math.min(8, hours));
        long bucketMinutes = resolveBucketMinutes(startedAt, endedAt, bucketCount);
        List<TrendAccumulator> buckets = new ArrayList<>();
        for (int index = 0; index < bucketCount; index++) {
            LocalDateTime bucketStart = startedAt.plusMinutes(bucketMinutes * index);
            LocalDateTime bucketEnd = index == bucketCount - 1
                    ? endedAt
                    : bucketStart.plusMinutes(bucketMinutes);
            buckets.add(new TrendAccumulator(bucketStart, bucketEnd));
        }
        return buckets;
    }

    private static long resolveBucketMinutes(LocalDateTime startedAt, LocalDateTime endedAt, int bucketCount) {
        long totalMinutes = Math.max(1L, Duration.between(startedAt, endedAt).toMinutes());
        return Math.max(1L, (long) Math.ceil(totalMinutes / (double) Math.max(1, bucketCount)));
    }

    private static TrendAccumulator resolveTrendBucket(
            List<TrendAccumulator> buckets,
            LocalDateTime startedAt,
            LocalDateTime value,
            long bucketMinutes
    ) {
        if (buckets.isEmpty() || value == null || value.isBefore(startedAt)) {
            return null;
        }
        long offsetMinutes = Math.max(0L, Duration.between(startedAt, value).toMinutes());
        int index = (int) Math.min(buckets.size() - 1, offsetMinutes / Math.max(1L, bucketMinutes));
        return index >= 0 && index < buckets.size() ? buckets.get(index) : null;
    }

    private static List<BoardBreakdownItem> topItems(
            Map<String, Long> counts,
            long total,
            Map<String, String> labels
    ) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator
                .<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey));

        List<BoardBreakdownItem> items = new ArrayList<>();
        int size = Math.min(entries.size(), BOARD_BREAKDOWN_LIMIT);
        for (int index = 0; index < size; index++) {
            Map.Entry<String, Long> entry = entries.get(index);
            String key = entry.getKey();
            long count = entry.getValue();
            items.add(new BoardBreakdownItem(
                    key,
                    labels.getOrDefault(key, key),
                    count,
                    total <= 0 ? 0D : count / (double) total
            ));
        }
        return items;
    }

    private static List<BoardAlert> recentAlerts(List<FtpSyncEvent> events, Map<String, String> taskLabels) {
        List<BoardAlert> alerts = new ArrayList<>();
        for (FtpSyncEvent event : events) {
            if (!isAlertEvent(event)) {
                continue;
            }
            alerts.add(new BoardAlert(
                    event.id(),
                    event.occurredAt(),
                    event.taskId(),
                    taskLabels.getOrDefault(event.taskId(), event.taskId()),
                    value(event.level(), "INFO").toUpperCase(Locale.ROOT),
                    value(event.eventType(), "UNKNOWN").toUpperCase(Locale.ROOT),
                    value(event.errorCategory(), ""),
                    value(event.message(), value(event.detailsText(), "No details")),
                    alertPath(event),
                    event.runId(),
                    event.replyCode()
            ));
            if (alerts.size() >= BOARD_ALERT_LIMIT) {
                break;
            }
        }
        return alerts;
    }

    private static boolean isAlertEvent(FtpSyncEvent event) {
        String level = value(event.level(), "").toUpperCase(Locale.ROOT);
        return level.contains("WARN")
                || "ERROR".equals(level)
                || notBlank(event.errorCategory());
    }

    private static String alertPath(FtpSyncEvent event) {
        if (notBlank(event.itemPath())) {
            return event.itemPath().trim();
        }
        return value(event.sourcePath(), "-");
    }

    public record DashboardOverview(
            String checkedAt,
            List<TaskCard> tasks
    ) {
    }

    public record TaskCard(
            TaskProfile task,
            boolean active,
            String state,
            String health,
            DiagnosticReport latestDiagnostic,
            SyncRun latestRun,
            SyncRun latestCacheRefresh
    ) {
    }

    public record TaskProfile(
            String taskId,
            String taskName,
            String sourceType,
            String ftpHost,
            int ftpPort,
            String ftpUsername,
            String secureMode,
            String tlsFingerprint,
            String tlsFingerprintHash,
            String sourcePath,
            boolean remoteDirectoryCacheEnabled,
            FileSourcePermission permission,
            RemoteDirectoryCacheRepository.CacheStats cacheStats
    ) {
    }

    public record BoardOverview(
            String generatedAt,
            int rangeHours,
            String startedAt,
            String endedAt,
            boolean truncatedRuns,
            boolean truncatedEvents,
            BoardSummary summary,
            List<BoardTrendBucket> trend,
            List<BoardBreakdownItem> operationTypes,
            List<BoardBreakdownItem> eventLevels,
            List<BoardBreakdownItem> errorCategories,
            List<BoardBreakdownItem> eventTypes,
            List<BoardBreakdownItem> taskHotspots,
            List<BoardAlert> alerts
    ) {
    }

    public record BoardSummary(
            long totalRuns,
            long successRuns,
            long failedRuns,
            long cancelledRuns,
            long runningRuns,
            long warningRuns,
            long totalEvents,
            long warningEvents,
            long errorEvents,
            long totalFiles,
            long totalDirectories,
            long totalBytes,
            long averageDurationMs,
            double successRate
    ) {
    }

    public record BoardTrendBucket(
            String label,
            String startedAt,
            String endedAt,
            long totalRuns,
            long successRuns,
            long failedRuns,
            long warningRuns,
            long errorEvents,
            long totalBytes
    ) {
    }

    public record BoardBreakdownItem(
            String key,
            String label,
            long count,
            double share
    ) {
    }

    public record BoardAlert(
            long id,
            String occurredAt,
            String taskId,
            String taskName,
            String level,
            String eventType,
            String errorCategory,
            String message,
            String path,
            Long runId,
            Integer replyCode
    ) {
    }

    private static final class SummaryAccumulator {
        private long totalRuns;
        private long successRuns;
        private long failedRuns;
        private long cancelledRuns;
        private long runningRuns;
        private long warningRuns;
        private long totalEvents;
        private long warningEvents;
        private long errorEvents;
        private long totalFiles;
        private long totalDirectories;
        private long totalBytes;
        private long totalDurationMs;
        private long durationSamples;

        private void addRun(SyncRun run) {
            totalRuns++;
            String state = value(run.state(), "UNKNOWN").toUpperCase(Locale.ROOT);
            switch (state) {
                case "SUCCESS" -> successRuns++;
                case "FAILED" -> failedRuns++;
                case "CANCELLED" -> cancelledRuns++;
                case "RUNNING" -> runningRuns++;
                default -> {
                }
            }
            if (run.warningCount() > 0
                    || "COMPLETED_WITH_WARNINGS".equalsIgnoreCase(run.finalHealth())) {
                warningRuns++;
            }
            totalFiles += Math.max(0L, run.fileCount());
            totalDirectories += Math.max(0L, run.directoryCount());
            totalBytes += Math.max(0L, run.totalBytes());
            if (run.durationMs() != null && run.durationMs() > 0) {
                totalDurationMs += run.durationMs();
                durationSamples++;
            }
        }

        private void addEvent(FtpSyncEvent event) {
            totalEvents++;
            String level = value(event.level(), "").toUpperCase(Locale.ROOT);
            if ("ERROR".equals(level)) {
                errorEvents++;
            } else if (level.contains("WARN")) {
                warningEvents++;
            }
        }

        private BoardSummary snapshot() {
            long finishedRuns = Math.max(0L, totalRuns - runningRuns);
            long averageDurationMs = durationSamples <= 0 ? 0L : Math.round(totalDurationMs / (double) durationSamples);
            double successRate = finishedRuns <= 0 ? 0D : successRuns / (double) finishedRuns;
            return new BoardSummary(
                    totalRuns,
                    successRuns,
                    failedRuns,
                    cancelledRuns,
                    runningRuns,
                    warningRuns,
                    totalEvents,
                    warningEvents,
                    errorEvents,
                    totalFiles,
                    totalDirectories,
                    totalBytes,
                    averageDurationMs,
                    successRate
            );
        }
    }

    private static final class TrendAccumulator {
        private final LocalDateTime startedAt;
        private final LocalDateTime endedAt;
        private long totalRuns;
        private long successRuns;
        private long failedRuns;
        private long warningRuns;
        private long errorEvents;
        private long totalBytes;

        private TrendAccumulator(LocalDateTime startedAt, LocalDateTime endedAt) {
            this.startedAt = startedAt;
            this.endedAt = endedAt;
        }

        private void addRun(SyncRun run) {
            totalRuns++;
            if ("SUCCESS".equalsIgnoreCase(run.state())) {
                successRuns++;
            }
            if ("FAILED".equalsIgnoreCase(run.state())) {
                failedRuns++;
            }
            if (run.warningCount() > 0
                    || "COMPLETED_WITH_WARNINGS".equalsIgnoreCase(run.finalHealth())) {
                warningRuns++;
            }
            totalBytes += Math.max(0L, run.totalBytes());
        }

        private void addEvent(FtpSyncEvent event) {
            if ("ERROR".equalsIgnoreCase(value(event.level(), ""))) {
                errorEvents++;
            }
        }

        private BoardTrendBucket snapshot(int rangeHours) {
            return new BoardTrendBucket(
                    trendLabel(startedAt, rangeHours),
                    DateTimes.formatDatabase(startedAt),
                    DateTimes.formatDatabase(endedAt),
                    totalRuns,
                    successRuns,
                    failedRuns,
                    warningRuns,
                    errorEvents,
                    totalBytes
            );
        }
    }

    private static String trendLabel(LocalDateTime startedAt, int rangeHours) {
        return (rangeHours <= 24 ? BOARD_SHORT_LABEL : BOARD_LONG_LABEL).format(startedAt);
    }
}

