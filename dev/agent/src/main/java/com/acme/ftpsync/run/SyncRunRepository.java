package com.acme.ftpsync.run;

import com.acme.ftpsync.db.Jdbc;
import com.acme.ftpsync.util.DateTimes;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class SyncRunRepository {
    private static final long COUNT_CACHE_TTL_MILLIS = 10_000L;
    private final Jdbc jdbc;
    private final ConcurrentMap<String, CountCacheEntry> countCache = new ConcurrentHashMap<>();

    public SyncRunRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String taskId, String operationType) throws SQLException {
        return create(taskId, operationType, "");
    }

    public long create(String taskId, String operationType, String sourcePath) throws SQLException {
        return create(taskId, operationType, sourcePath, "", "", "");
    }

    public long create(String taskId, String operationType, String sourcePath,
                       String targetTaskId, String targetPath, String conflictPolicy) throws SQLException {
        String sql = """
                INSERT INTO file_operation
                  (task_id, operation_type, source_path, target_task_id, target_path, conflict_policy, state, started_at)
                VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?)
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, taskId);
            ps.setString(2, operationType == null || operationType.isBlank() ? "FILE_OPERATION" : operationType.trim().toUpperCase());
            ps.setString(3, sourcePath == null ? "" : sourcePath.trim());
            ps.setString(4, targetTaskId == null ? "" : targetTaskId.trim());
            ps.setString(5, targetPath == null ? "" : targetPath.trim());
            ps.setString(6, conflictPolicy == null ? "" : conflictPolicy.trim().toUpperCase());
            ps.setString(7, DateTimes.nowDatabase());
            ps.executeUpdate();
            countCache.clear();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to create file_operation, generated key missing.");
    }

    public Optional<SyncRun> find(long runId) throws SQLException {
        String sql = "SELECT * FROM file_operation WHERE id=?";
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toRun(rs)) : Optional.empty();
            }
        }
    }

    public List<SyncRun> listByTask(String taskId, int limit) throws SQLException {
        String sql = "SELECT * FROM file_operation WHERE task_id=? ORDER BY started_at DESC, id DESC LIMIT ?";
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setInt(2, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                List<SyncRun> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toRun(rs));
                }
                return result;
            }
        }
    }

    public Optional<SyncRun> findLatestByTaskAndOperation(String taskId, String operationType) throws SQLException {
        String sql = """
                SELECT * FROM file_operation
                WHERE task_id=? AND operation_type=?
                ORDER BY started_at DESC, id DESC
                LIMIT 1
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setString(2, operationType == null || operationType.isBlank()
                    ? "FILE_OPERATION"
                    : operationType.trim().toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toRun(rs)) : Optional.empty();
            }
        }
    }

    public List<SyncRun> listRecent(int limit) throws SQLException {
        String sql = "SELECT * FROM file_operation ORDER BY started_at DESC, id DESC LIMIT ?";
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, Math.min(limit, 1000)));
            try (ResultSet rs = ps.executeQuery()) {
                List<SyncRun> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toRun(rs));
                }
                return result;
            }
        }
    }

    public List<SyncRun> listSince(String startedAtInclusive, int limit) throws SQLException {
        String sql = """
                SELECT * FROM file_operation
                WHERE started_at >= ?
                ORDER BY started_at DESC, id DESC
                LIMIT ?
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, value(startedAtInclusive, "1970-01-01 00:00:00.000"));
            ps.setInt(2, Math.max(1, Math.min(limit, 20_000)));
            try (ResultSet rs = ps.executeQuery()) {
                List<SyncRun> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toRun(rs));
                }
                return result;
            }
        }
    }

    public RunPage listRecentPage(RunQuery query) throws SQLException {
        RunQuery normalized = query.normalize();
        QueryParts parts = buildQueryParts(normalized);
        String countSql = "SELECT COUNT(*) " + parts.fromSql() + parts.whereSql();
        Cursor cursor = decodeCursor(normalized.cursor());
        String listWhereSql = parts.whereSql();
        List<Object> listParams = new ArrayList<>(parts.params());
        if (cursor != null) {
            listWhereSql = appendWhere(listWhereSql, "(r.started_at < ? OR (r.started_at = ? AND r.id < ?))");
            listParams.add(cursor.sortValue());
            listParams.add(cursor.sortValue());
            listParams.add(cursor.id());
        }
        String listSql = "SELECT r.* " + parts.fromSql() + listWhereSql
                + " ORDER BY r.started_at DESC, r.id DESC LIMIT ?"
                + (cursor == null ? " OFFSET ?" : "");
        try (Connection connection = jdbc.open()) {
            Long total = countTotal(connection, countSql, parts.params(), normalized.includeTotal());

            List<SyncRun> runs = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(listSql)) {
                int index = bindParams(ps, listParams);
                ps.setInt(index++, normalized.pageSize() + 1);
                if (cursor == null) {
                    ps.setLong(index, normalized.offset());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        runs.add(toRun(rs));
                    }
                }
            }
            boolean hasMore = runs.size() > normalized.pageSize();
            if (hasMore) {
                runs = new ArrayList<>(runs.subList(0, normalized.pageSize()));
            }
            runs = withEventSummaries(connection, runs);
            String nextCursor = hasMore && !runs.isEmpty()
                    ? encodeCursor(runs.get(runs.size() - 1).startedAt(), runs.get(runs.size() - 1).id())
                    : null;
            return new RunPage(runs, total, normalized.page(), normalized.pageSize(), nextCursor, hasMore);
        }
    }

    private static QueryParts buildQueryParts(RunQuery query) {
        String fromSql = "FROM file_operation r";
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (query.operationRecordsOnly()) {
            conditions.add("r.operation_type NOT IN ('CONNECTION_TEST', 'FILE_BROWSE', 'REMOTE_BROWSE')");
        }
        if (!isAll(query.state())) {
            conditions.add("r.state=?");
            params.add(query.state().trim().toUpperCase());
        }
        if (!isAll(query.taskId())) {
            conditions.add("r.task_id=?");
            params.add(query.taskId().trim());
        }
        if (!query.text().isBlank()) {
            List<String> textConditions = new ArrayList<>();
            Long numericText = parseLong(query.text());
            if (numericText != null) {
                textConditions.add("r.id=?");
                params.add(numericText);
            }
            String textQuery = textQuery(query.text());
            if (!textQuery.isBlank()) {
                textConditions.add("r.id IN (SELECT rowid FROM file_operation_fts WHERE file_operation_fts MATCH ?)");
                params.add(textQuery);
            }
            if (!textConditions.isEmpty()) {
                conditions.add("(" + String.join(" OR ", textConditions) + ")");
            }
        }

        String whereSql = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        return new QueryParts(fromSql, whereSql, params);
    }

    private Long countTotal(Connection connection, String countSql, List<Object> params, boolean includeTotal)
            throws SQLException {
        if (!includeTotal) {
            return null;
        }
        String cacheKey = countSql + "\u001F" + params;
        long now = System.currentTimeMillis();
        CountCacheEntry cached = countCache.get(cacheKey);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.total();
        }
        long total;
        try (PreparedStatement ps = connection.prepareStatement(countSql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                total = rs.next() ? rs.getLong(1) : 0L;
            }
        }
        countCache.put(cacheKey, new CountCacheEntry(total, now + COUNT_CACHE_TTL_MILLIS));
        return total;
    }

    private static int bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        int index = 1;
        for (Object param : params) {
            if (param instanceof Long longValue) {
                ps.setLong(index++, longValue);
            } else {
                ps.setString(index++, param == null ? "" : param.toString());
            }
        }
        return index;
    }

    private static String appendWhere(String whereSql, String condition) {
        return whereSql == null || whereSql.isBlank()
                ? " WHERE " + condition
                : whereSql + " AND " + condition;
    }

    public List<SyncRun> listRunning(int limit) throws SQLException {
        String sql = "SELECT * FROM file_operation WHERE state='RUNNING' ORDER BY started_at ASC, id ASC LIMIT ?";
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, Math.min(limit, 5000)));
            try (ResultSet rs = ps.executeQuery()) {
                List<SyncRun> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toRun(rs));
                }
                return result;
            }
        }
    }

    public void markSuccess(long runId, SyncRunSummary summary) throws SQLException {
        String sql = """
                UPDATE file_operation
                SET state='SUCCESS', final_health=?, finished_at=?,
                    duration_ms=CAST((julianday(?) - julianday(started_at)) * 86400000 AS INTEGER),
                    item_count=?, file_count=?, directory_count=?, total_bytes=?,
                    warning_count=?, error_count=?,
                    last_error_category=NULL, last_error_message=NULL,
                    updated_at=strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')
                WHERE id=?
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            bindSummary(ps, summary, runId, false);
            ps.executeUpdate();
            countCache.clear();
        }
    }

    public void updateProgress(long runId, SyncRunSummary summary) throws SQLException {
        String sql = """
                UPDATE file_operation
                SET final_health=?, item_count=?, file_count=?, directory_count=?, total_bytes=?,
                    warning_count=?, error_count=?, last_error_category=?, last_error_message=?,
                    updated_at=strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')
                WHERE id=? AND state='RUNNING'
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, summary.finalHealth());
            ps.setLong(2, summary.itemCount());
            ps.setLong(3, summary.fileCount());
            ps.setLong(4, summary.directoryCount());
            ps.setLong(5, summary.totalBytes());
            ps.setLong(6, summary.warningCount());
            ps.setLong(7, summary.errorCount());
            ps.setString(8, summary.lastErrorCategory());
            ps.setString(9, summary.lastErrorMessage());
            ps.setLong(10, runId);
            ps.executeUpdate();
            countCache.clear();
        }
    }

    public void markFailure(long runId, SyncRunSummary summary) throws SQLException {
        String sql = """
                UPDATE file_operation
                SET state='FAILED', final_health=?, finished_at=?,
                    duration_ms=CAST((julianday(?) - julianday(started_at)) * 86400000 AS INTEGER),
                    item_count=?, file_count=?, directory_count=?, total_bytes=?,
                    warning_count=?, error_count=?,
                    last_error_category=?, last_error_message=?,
                    updated_at=strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')
                WHERE id=?
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            bindSummary(ps, summary, runId, true);
            ps.executeUpdate();
            countCache.clear();
        }
    }

    public void markCancelled(long runId, SyncRunSummary summary) throws SQLException {
        String sql = """
                UPDATE file_operation
                SET state='CANCELLED', final_health=?, finished_at=?,
                    duration_ms=CAST((julianday(?) - julianday(started_at)) * 86400000 AS INTEGER),
                    item_count=?, file_count=?, directory_count=?, total_bytes=?,
                    warning_count=?, error_count=?,
                    last_error_category=?, last_error_message=?,
                    updated_at=strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')
                WHERE id=?
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            bindSummary(ps, summary, runId, true);
            ps.executeUpdate();
            countCache.clear();
        }
    }

    private static void bindSummary(PreparedStatement ps, SyncRunSummary summary, long runId, boolean includeError) throws SQLException {
        String finishedAt = DateTimes.nowDatabase();
        ps.setString(1, summary.finalHealth());
        ps.setString(2, finishedAt);
        ps.setString(3, finishedAt);
        ps.setLong(4, summary.itemCount());
        ps.setLong(5, summary.fileCount());
        ps.setLong(6, summary.directoryCount());
        ps.setLong(7, summary.totalBytes());
        ps.setLong(8, summary.warningCount());
        ps.setLong(9, summary.errorCount());
        if (includeError) {
            ps.setString(10, summary.lastErrorCategory());
            ps.setString(11, summary.lastErrorMessage());
            ps.setLong(12, runId);
        } else {
            ps.setLong(10, runId);
        }
    }

    private static SyncRun toRun(ResultSet rs) throws SQLException {
        return new SyncRun(
                rs.getLong("id"),
                rs.getString("task_id"),
                rs.getString("operation_type"),
                value(rs, "source_path"),
                value(rs, "target_task_id"),
                value(rs, "target_path"),
                value(rs, "conflict_policy"),
                rs.getString("state"),
                rs.getString("final_health"),
                value(rs, "started_at"),
                value(rs, "finished_at"),
                nullableLong(rs, "duration_ms"),
                rs.getLong("item_count"),
                rs.getLong("file_count"),
                rs.getLong("directory_count"),
                rs.getLong("total_bytes"),
                rs.getLong("warning_count"),
                rs.getLong("error_count"),
                rs.getString("last_error_category"),
                rs.getString("last_error_message")
        );
    }

    private static List<SyncRun> withEventSummaries(Connection connection, List<SyncRun> runs) throws SQLException {
        if (runs.isEmpty()) {
            return runs;
        }
        String summarySql = """
                SELECT event_type, level, message, source_path, item_path, details_text
                FROM file_operation_log
                WHERE run_id=?
                ORDER BY seq DESC
                LIMIT 1
                """;
        String alertSql = """
                SELECT event_type, message
                FROM file_operation_log
                WHERE run_id=? AND level IN ('ERROR', 'WARN', 'WARNING')
                ORDER BY seq DESC
                LIMIT 1
                """;
        List<SyncRun> enriched = new ArrayList<>(runs.size());
        try (PreparedStatement summaryStatement = connection.prepareStatement(summarySql);
             PreparedStatement alertStatement = connection.prepareStatement(alertSql)) {
            for (SyncRun run : runs) {
                enriched.add(run.withEventSummary(readEventSummary(summaryStatement, alertStatement, run.id())));
            }
        }
        return enriched;
    }

    private static SyncRun.OperationEventSummary readEventSummary(
            PreparedStatement summaryStatement,
            PreparedStatement alertStatement,
            long runId
    ) throws SQLException {
        String summaryEventType = "";
        String summaryLevel = "";
        String summaryMessage = "";
        String summarySourcePath = "";
        String summaryTargetPath = "";
        String summaryDetailsText = "";
        summaryStatement.setLong(1, runId);
        try (ResultSet rs = summaryStatement.executeQuery()) {
            if (rs.next()) {
                summaryEventType = value(rs, "event_type");
                summaryLevel = value(rs, "level");
                summaryMessage = value(rs, "message");
                summarySourcePath = value(rs, "source_path");
                summaryTargetPath = value(rs, "item_path");
                summaryDetailsText = value(rs, "details_text");
            }
        }

        String alertEventType = "";
        String alertMessage = "";
        alertStatement.setLong(1, runId);
        try (ResultSet rs = alertStatement.executeQuery()) {
            if (rs.next()) {
                alertEventType = value(rs, "event_type");
                alertMessage = value(rs, "message");
            }
        }

        if (summaryEventType.isBlank() && alertEventType.isBlank()) {
            return null;
        }
        return new SyncRun.OperationEventSummary(
                summaryEventType,
                summaryLevel,
                summaryMessage,
                summarySourcePath,
                summaryTargetPath,
                summaryDetailsText,
                alertEventType,
                alertMessage
        );
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String value(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? "" : value.toString();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean isAll(String value) {
        return value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim());
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String textQuery(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] rawTerms = text.trim().split("[^\\p{L}\\p{N}_]+");
        List<String> terms = new ArrayList<>();
        for (String rawTerm : rawTerms) {
            if (rawTerm.isBlank()) {
                continue;
            }
            String term = rawTerm.toLowerCase(Locale.ROOT);
            if (term.length() > 64) {
                term = term.substring(0, 64);
            }
            terms.add(term + "*");
            if (terms.size() >= 8) {
                break;
            }
        }
        return String.join(" ", terms);
    }

    private static Cursor decodeCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            if (separator <= 0 || separator == decoded.length() - 1) {
                return null;
            }
            String sortValue = normalizeDateTime(decoded.substring(0, separator));
            long id = Long.parseLong(decoded.substring(separator + 1));
            return new Cursor(sortValue, id);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String encodeCursor(String sortValue, long id) {
        if (sortValue == null || sortValue.isBlank() || id <= 0) {
            return null;
        }
        String raw = normalizeDateTime(sortValue) + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeDateTime(String value) {
        return value == null ? "" : value.trim().replace('T', ' ');
    }

    private record QueryParts(String fromSql, String whereSql, List<Object> params) {
    }

    private record Cursor(String sortValue, long id) {
    }

    private record CountCacheEntry(long total, long expiresAtMillis) {
    }

    public record RunQuery(int page, int pageSize, String state, String taskId, String text,
                           boolean operationRecordsOnly, String cursor, boolean includeTotal) {
        private static final int MAX_PAGE_SIZE = 1000;

        public RunQuery normalize() {
            return new RunQuery(
                    Math.max(1, page),
                    Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE)),
                    value(state, "ALL"),
                    value(taskId, "ALL"),
                    value(text, ""),
                    operationRecordsOnly,
                    value(cursor, ""),
                    includeTotal
            );
        }

        public long offset() {
            return (long) (page - 1) * pageSize;
        }
    }

    public record RunPage(List<SyncRun> runs, Long total, int page, int pageSize,
                          String nextCursor, boolean hasMore) {
    }
}


