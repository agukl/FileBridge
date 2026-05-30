package com.acme.ftpsync.event;

import com.acme.ftpsync.db.Jdbc;
import com.acme.ftpsync.util.DateTimes;
import com.acme.ftpsync.util.SecretRedactor;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FtpSyncEventRepository {
    private static final long COUNT_CACHE_TTL_MILLIS = 10_000L;
    private final Jdbc jdbc;
    private final ConcurrentMap<String, CountCacheEntry> countCache = new ConcurrentHashMap<>();

    public FtpSyncEventRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public FtpSyncEvent write(FtpSyncEvent event) throws SQLException {
        String sql = """
                INSERT INTO file_operation_log (
                  task_id, run_id, seq, occurred_at, level, phase, operation_name, event_type,
                  error_category, retryable, reply_code, reply_text, source_path, item_path,
                  item_type, item_size, duration_ms, handling_action, health_impact, message, details_text
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        FtpSyncEvent normalized = normalize(event);
        try (Connection connection = jdbc.open();
             PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindEvent(ps, normalized);
            ps.executeUpdate();
            countCache.clear();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return withId(normalized, keys.getLong(1));
                }
            }
            return normalized;
        }
    }

    public long nextSeq(Long runId) throws SQLException {
        if (runId == null) {
            return 1L;
        }
        String sql = "SELECT COALESCE(MAX(seq), 0) + 1 FROM file_operation_log WHERE run_id=?";
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 1L;
            }
        }
    }

    public List<FtpSyncEvent> listRunEvents(long runId, int limit) throws SQLException {
        String sql = "SELECT * FROM file_operation_log WHERE run_id=? ORDER BY seq ASC LIMIT ?";
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, runId);
            ps.setInt(2, Math.max(1, Math.min(limit, 2000)));
            try (ResultSet rs = ps.executeQuery()) {
                List<FtpSyncEvent> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toEvent(rs));
                }
                return result;
            }
        }
    }

    public List<FtpSyncEvent> listRecent(int limit) throws SQLException {
        String sql = "SELECT * FROM file_operation_log ORDER BY occurred_at DESC, id DESC LIMIT ?";
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, Math.min(limit, 5000)));
            try (ResultSet rs = ps.executeQuery()) {
                List<FtpSyncEvent> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toEvent(rs));
                }
                return result;
            }
        }
    }

    public List<FtpSyncEvent> listSince(String occurredAtInclusive, int limit) throws SQLException {
        String sql = """
                SELECT * FROM file_operation_log
                WHERE occurred_at >= ?
                ORDER BY occurred_at DESC, id DESC
                LIMIT ?
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, value(occurredAtInclusive, "1970-01-01 00:00:00.000"));
            ps.setInt(2, Math.max(1, Math.min(limit, 20_000)));
            try (ResultSet rs = ps.executeQuery()) {
                List<FtpSyncEvent> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toEvent(rs));
                }
                return result;
            }
        }
    }

    public EventPage listRecentPage(EventQuery query) throws SQLException {
        EventQuery normalized = query.normalize();
        QueryParts parts = buildQueryParts(normalized);
        String countSql = "SELECT COUNT(*) " + parts.fromSql() + parts.whereSql();
        Cursor cursor = decodeCursor(normalized.cursor());
        String listWhereSql = parts.whereSql();
        List<Object> listParams = new ArrayList<>(parts.params());
        if (cursor != null) {
            listWhereSql = appendWhere(listWhereSql, "(e.occurred_at < ? OR (e.occurred_at = ? AND e.id < ?))");
            listParams.add(cursor.sortValue());
            listParams.add(cursor.sortValue());
            listParams.add(cursor.id());
        }
        String listSql = "SELECT e.* " + parts.fromSql() + listWhereSql
                + " ORDER BY e.occurred_at DESC, e.id DESC LIMIT ?"
                + (cursor == null ? " OFFSET ?" : "");
        try (Connection connection = jdbc.open()) {
            Long total = countTotal(connection, countSql, parts.params(), normalized.includeTotal());

            List<FtpSyncEvent> events = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(listSql)) {
                int index = bindParams(ps, listParams);
                ps.setInt(index++, normalized.pageSize() + 1);
                if (cursor == null) {
                    ps.setLong(index, normalized.offset());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        events.add(toEvent(rs));
                    }
                }
            }
            boolean hasMore = events.size() > normalized.pageSize();
            if (hasMore) {
                events = new ArrayList<>(events.subList(0, normalized.pageSize()));
            }
            String nextCursor = hasMore && !events.isEmpty()
                    ? encodeCursor(events.get(events.size() - 1).occurredAt(), events.get(events.size() - 1).id())
                    : null;
            return new EventPage(events, total, normalized.page(), normalized.pageSize(), nextCursor, hasMore);
        }
    }

    private static QueryParts buildQueryParts(EventQuery query) {
        String fromSql = "FROM file_operation_log e";
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (!isAll(query.taskId())) {
            conditions.add("e.task_id=?");
            params.add(query.taskId().trim());
        }
        if (!isAll(query.state())) {
            fromSql += " JOIN file_operation r ON e.run_id=r.id";
            conditions.add("r.state=?");
            params.add(query.state().trim().toUpperCase());
        }
        if (!query.text().isBlank()) {
            List<String> textConditions = new ArrayList<>();
            Long numericText = parseLong(query.text());
            if (numericText != null) {
                textConditions.add("e.id=?");
                params.add(numericText);
                textConditions.add("e.run_id=?");
                params.add(numericText);
            }
            String textQuery = textQuery(query.text());
            if (!textQuery.isBlank()) {
                textConditions.add("e.id IN (SELECT rowid FROM file_operation_log_fts WHERE file_operation_log_fts MATCH ?)");
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

    private static void bindEvent(PreparedStatement ps, FtpSyncEvent event) throws SQLException {
        ps.setString(1, event.taskId());
        if (event.runId() == null) {
            ps.setNull(2, java.sql.Types.BIGINT);
        } else {
            ps.setLong(2, event.runId());
        }
        ps.setLong(3, event.seq());
        ps.setString(4, event.occurredAt());
        ps.setString(5, event.level());
        ps.setString(6, event.phase());
        ps.setString(7, event.operationName());
        ps.setString(8, event.eventType());
        ps.setString(9, event.errorCategory());
        ps.setBoolean(10, event.retryable());
        setNullableInt(ps, 11, event.replyCode());
        ps.setString(12, event.replyText());
        ps.setString(13, event.sourcePath());
        ps.setString(14, event.itemPath());
        ps.setString(15, event.itemType());
        setNullableLong(ps, 16, event.itemSize());
        setNullableLong(ps, 17, event.durationMs());
        ps.setString(18, event.handlingAction());
        ps.setString(19, event.healthImpact());
        ps.setString(20, event.message());
        ps.setString(21, event.detailsText());
    }

    private static FtpSyncEvent normalize(FtpSyncEvent event) {
        return new FtpSyncEvent(
                event.id(),
                require(event.taskId(), "taskId"),
                event.runId(),
                Math.max(1L, event.seq()),
                value(event.occurredAt(), DateTimes.nowDatabase()),
                value(event.level(), "INFO").toUpperCase(),
                value(event.phase(), "UNKNOWN").toUpperCase(),
                value(event.operationName(), "UNKNOWN").toUpperCase(),
                require(event.eventType(), "eventType").toUpperCase(),
                value(event.errorCategory(), ""),
                event.retryable(),
                event.replyCode(),
                SecretRedactor.redact(value(event.replyText(), "")),
                SecretRedactor.redact(value(event.sourcePath(), "")),
                SecretRedactor.redact(value(event.itemPath(), "")),
                value(event.itemType(), ""),
                event.itemSize(),
                event.durationMs(),
                value(event.handlingAction(), ""),
                value(event.healthImpact(), ""),
                SecretRedactor.redact(value(event.message(), "")),
                SecretRedactor.redact(value(event.detailsText(), ""))
        );
    }

    private static FtpSyncEvent toEvent(ResultSet rs) throws SQLException {
        return new FtpSyncEvent(
                rs.getLong("id"),
                rs.getString("task_id"),
                nullableLong(rs, "run_id"),
                rs.getLong("seq"),
                value(rs, "occurred_at"),
                rs.getString("level"),
                rs.getString("phase"),
                rs.getString("operation_name"),
                rs.getString("event_type"),
                rs.getString("error_category"),
                rs.getBoolean("retryable"),
                nullableInt(rs, "reply_code"),
                rs.getString("reply_text"),
                rs.getString("source_path"),
                rs.getString("item_path"),
                rs.getString("item_type"),
                nullableLong(rs, "item_size"),
                nullableLong(rs, "duration_ms"),
                rs.getString("handling_action"),
                rs.getString("health_impact"),
                rs.getString("message"),
                rs.getString("details_text")
        );
    }

    private static FtpSyncEvent withId(FtpSyncEvent event, long id) {
        return new FtpSyncEvent(id, event.taskId(), event.runId(), event.seq(), event.occurredAt(), event.level(),
                event.phase(), event.operationName(), event.eventType(), event.errorCategory(), event.retryable(),
                event.replyCode(), event.replyText(), event.sourcePath(), event.itemPath(), event.itemType(),
                event.itemSize(), event.durationMs(), event.handlingAction(),
                event.healthImpact(), event.message(), event.detailsText());
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String value(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? "" : value.toString();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String require(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required event field: " + fieldName);
        }
        return value.trim();
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

    public record EventQuery(int page, int pageSize, String state, String taskId, String text,
                             String cursor, boolean includeTotal) {
        private static final int MAX_PAGE_SIZE = 500;

        EventQuery normalize() {
            return new EventQuery(
                    Math.max(1, page),
                    Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE)),
                    value(state, "ALL"),
                    value(taskId, "ALL"),
                    value(text, ""),
                    value(cursor, ""),
                    includeTotal
            );
        }

        long offset() {
            return (long) (page - 1) * pageSize;
        }
    }

    public record EventPage(List<FtpSyncEvent> events, Long total, int page, int pageSize,
                            String nextCursor, boolean hasMore) {
    }
}


