package com.acme.ftpsync.files;

import com.acme.ftpsync.db.Jdbc;
import com.acme.ftpsync.util.DateTimes;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class DirectoryCopyTaskRepository {
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final DateTimeFormatter DATABASE_DATETIME_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Jdbc jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public DirectoryCopyTaskRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public DirectoryCopyTask create(DirectoryCopyTask task) throws Exception {
        DirectoryCopyTask normalized = normalize(task, 0L);
        String sql = """
                INSERT INTO directory_copy_task (
                  name, source_file_source_id, source_paths_json, target_file_source_id, target_directory,
                  compare_mode, mtime_tolerance_seconds, conflict_policy,
                  schedule_enabled, schedule_type, schedule_interval_minutes, schedule_time_of_day,
                  schedule_timezone, next_run_at, enabled
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindMutable(ps, normalized);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return find(keys.getLong(1)).orElseThrow();
                }
            }
        }
        throw new SQLException("Failed to create directory_copy_task, generated key missing.");
    }

    public DirectoryCopyTask update(long id, DirectoryCopyTask task) throws Exception {
        DirectoryCopyTask normalized = normalize(task, id);
        String sql = """
                UPDATE directory_copy_task
                SET name=?, source_file_source_id=?, source_paths_json=?, target_file_source_id=?, target_directory=?,
                    compare_mode=?, mtime_tolerance_seconds=?, conflict_policy=?,
                    schedule_enabled=?, schedule_type=?, schedule_interval_minutes=?, schedule_time_of_day=?,
                    schedule_timezone=?, next_run_at=?, enabled=?,
                    updated_at=strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')
                WHERE id=?
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = bindMutable(ps, normalized);
            ps.setLong(index, id);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("Directory copy task not found: " + id);
            }
        }
        return find(id).orElseThrow();
    }

    public boolean delete(long id) throws SQLException {
        String selectSql = "SELECT last_status FROM directory_copy_task WHERE id=?";
        String deleteSql = "DELETE FROM directory_copy_task WHERE id=?";
        try (Connection connection = jdbc.open()) {
            refreshLastOperationStatus(connection);
            try (PreparedStatement ps = connection.prepareStatement(selectSql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    String lastStatus = value(rs.getString("last_status"), "");
                    if ("RUNNING".equalsIgnoreCase(lastStatus)) {
                        throw new IllegalStateException("Directory copy task is running and cannot be deleted: " + id);
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(deleteSql)) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        }
    }

    public Optional<DirectoryCopyTask> find(long id) throws Exception {
        String sql = "SELECT * FROM directory_copy_task WHERE id=?";
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            refreshLastOperationStatus(connection);
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toTask(rs)) : Optional.empty();
            }
        }
    }

    public List<DirectoryCopyTask> list(int limit) throws Exception {
        String sql = "SELECT * FROM directory_copy_task ORDER BY updated_at DESC, id DESC LIMIT ?";
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            refreshLastOperationStatus(connection);
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                List<DirectoryCopyTask> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toTask(rs));
                }
                return result;
            }
        }
    }

    public List<DirectoryCopyTask> listDue(int limit) throws Exception {
        String sql = """
                SELECT *
                FROM directory_copy_task
                WHERE enabled=1 AND schedule_enabled=1 AND next_run_at IS NOT NULL AND next_run_at <= ?
                ORDER BY next_run_at ASC, id ASC
                LIMIT ?
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            refreshLastOperationStatus(connection);
            ps.setString(1, DateTimes.nowDatabase());
            ps.setInt(2, Math.max(1, Math.min(limit, 100)));
            try (ResultSet rs = ps.executeQuery()) {
                List<DirectoryCopyTask> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toTask(rs));
                }
                return result;
            }
        }
    }

    public void markSubmitted(long id, long operationId, String message) throws SQLException {
        String sql = """
                UPDATE directory_copy_task
                SET last_operation_id=?, last_status='RUNNING', last_started_at=?, last_finished_at=NULL,
                    last_message=?, next_run_at=?,
                    updated_at=strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')
                WHERE id=?
                """;
        DirectoryCopyTask task;
        try {
            task = find(id).orElse(null);
        } catch (Exception ex) {
            throw new SQLException(ex);
        }
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, operationId);
            ps.setString(2, DateTimes.nowDatabase());
            ps.setString(3, trim(message, 1024));
            ps.setString(4, task == null ? null : nextRunAt(task));
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    public void markSkipped(long id, String message) throws SQLException {
        String sql = """
                UPDATE directory_copy_task
                SET last_status='SKIPPED', last_message=?, next_run_at=?,
                    updated_at=strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')
                WHERE id=?
                """;
        DirectoryCopyTask task;
        try {
            task = find(id).orElse(null);
        } catch (Exception ex) {
            throw new SQLException(ex);
        }
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, trim(message, 1024));
            ps.setString(2, task == null ? null : nextRunAt(task));
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    private int bindMutable(PreparedStatement ps, DirectoryCopyTask task) throws Exception {
        ps.setString(1, task.name());
        ps.setString(2, task.sourceFileSourceId());
        ps.setString(3, mapper.writeValueAsString(task.sourcePaths()));
        ps.setString(4, task.targetFileSourceId());
        ps.setString(5, task.targetDirectory());
        ps.setString(6, task.compareMode());
        ps.setInt(7, task.mtimeToleranceSeconds());
        ps.setString(8, task.conflictPolicy());
        ps.setBoolean(9, task.scheduleEnabled());
        ps.setString(10, task.scheduleType());
        ps.setInt(11, task.scheduleIntervalMinutes());
        ps.setString(12, value(task.scheduleTimeOfDay(), ""));
        ps.setString(13, value(task.scheduleTimezone(), "Asia/Shanghai"));
        ps.setString(14, task.nextRunAt());
        ps.setBoolean(15, task.enabled());
        return 16;
    }

    private void refreshLastOperationStatus(Connection connection) throws SQLException {
        String sql = """
                UPDATE directory_copy_task
                SET last_status=(SELECT o.state FROM file_operation o WHERE o.id=directory_copy_task.last_operation_id),
                    last_finished_at=(SELECT o.finished_at FROM file_operation o WHERE o.id=directory_copy_task.last_operation_id),
                    last_message=CASE (SELECT o.state FROM file_operation o WHERE o.id=directory_copy_task.last_operation_id)
                      WHEN 'SUCCESS' THEN
                        CASE
                          WHEN (SELECT o.error_count FROM file_operation o WHERE o.id=directory_copy_task.last_operation_id) > 0
                            THEN 'Directory copy task completed with errors.'
                          WHEN (SELECT o.warning_count FROM file_operation o WHERE o.id=directory_copy_task.last_operation_id) > 0
                            THEN 'Directory copy task completed with warnings.'
                          ELSE 'Directory copy task completed.'
                        END
                      WHEN 'FAILED' THEN COALESCE(
                        NULLIF((SELECT o.last_error_message FROM file_operation o WHERE o.id=directory_copy_task.last_operation_id), ''),
                        'Directory copy task failed.'
                      )
                      WHEN 'CANCELLED' THEN COALESCE(
                        NULLIF((SELECT o.last_error_message FROM file_operation o WHERE o.id=directory_copy_task.last_operation_id), ''),
                        'Directory copy task cancelled.'
                      )
                      ELSE COALESCE(
                        NULLIF((SELECT o.last_error_message FROM file_operation o WHERE o.id=directory_copy_task.last_operation_id), ''),
                        last_message
                      )
                    END,
                    updated_at=strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')
                WHERE last_operation_id IS NOT NULL
                  AND EXISTS (SELECT 1 FROM file_operation o WHERE o.id=directory_copy_task.last_operation_id)
                  AND (
                    last_status IS NULL OR last_status='' OR last_status='RUNNING'
                    OR last_status<>(SELECT o.state FROM file_operation o WHERE o.id=directory_copy_task.last_operation_id)
                  )
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private DirectoryCopyTask toTask(ResultSet rs) throws Exception {
        Long lastOperationId = nullableLong(rs, "last_operation_id");
        return new DirectoryCopyTask(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("source_file_source_id"),
                mapper.readValue(rs.getString("source_paths_json"), STRING_LIST_TYPE),
                rs.getString("target_file_source_id"),
                rs.getString("target_directory"),
                value(rs.getString("compare_mode"), "FAST"),
                rs.getInt("mtime_tolerance_seconds"),
                value(rs.getString("conflict_policy"), "SKIP"),
                rs.getBoolean("schedule_enabled"),
                value(rs.getString("schedule_type"), "MANUAL_ONLY"),
                rs.getInt("schedule_interval_minutes"),
                value(rs.getString("schedule_time_of_day"), ""),
                value(rs.getString("schedule_timezone"), "Asia/Shanghai"),
                value(rs.getString("next_run_at"), ""),
                rs.getBoolean("enabled"),
                lastOperationId,
                value(rs.getString("last_status"), ""),
                value(rs.getString("last_started_at"), ""),
                value(rs.getString("last_finished_at"), ""),
                value(rs.getString("last_message"), "")
        );
    }

    private DirectoryCopyTask normalize(DirectoryCopyTask task, long id) {
        if (task == null) {
            throw new IllegalArgumentException("Directory copy task is empty.");
        }
        List<String> paths = task.sourcePaths() == null ? List.of() : task.sourcePaths().stream()
                .filter(path -> path != null && !path.isBlank())
                .map(String::trim)
                .toList();
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("sourcePaths must contain at least one path.");
        }
        String scheduleType = normalizeScheduleType(task.scheduleType());
        boolean scheduleEnabled = task.scheduleEnabled() && !"MANUAL_ONLY".equals(scheduleType);
        String nextRunAt = scheduleEnabled ? value(task.nextRunAt(), nextRunAt(scheduleType,
                task.scheduleIntervalMinutes(), task.scheduleTimeOfDay())) : "";
        return new DirectoryCopyTask(
                id,
                required(task.name(), "name"),
                required(task.sourceFileSourceId(), "sourceFileSourceId"),
                paths,
                required(task.targetFileSourceId(), "targetFileSourceId"),
                required(task.targetDirectory(), "targetDirectory"),
                normalizeCompareMode(task.compareMode()),
                Math.max(0, Math.min(task.mtimeToleranceSeconds() <= 0 ? 5 : task.mtimeToleranceSeconds(), 3600)),
                normalizeConflictPolicy(task.conflictPolicy()),
                scheduleEnabled,
                scheduleType,
                Math.max(0, task.scheduleIntervalMinutes()),
                value(task.scheduleTimeOfDay(), ""),
                value(task.scheduleTimezone(), "Asia/Shanghai"),
                nextRunAt,
                task.enabled(),
                task.lastOperationId(),
                value(task.lastStatus(), ""),
                value(task.lastStartedAt(), ""),
                value(task.lastFinishedAt(), ""),
                value(task.lastMessage(), "")
        );
    }

    private static String nextRunAt(DirectoryCopyTask task) {
        if (!task.scheduleEnabled()) {
            return "";
        }
        return nextRunAt(task.scheduleType(), task.scheduleIntervalMinutes(), task.scheduleTimeOfDay());
    }

    private static String nextRunAt(String scheduleType, int intervalMinutes, String timeOfDay) {
        LocalDateTime now = LocalDateTime.now();
        if ("INTERVAL".equals(scheduleType)) {
            return now.plusMinutes(Math.max(1, intervalMinutes)).format(DATABASE_DATETIME_MS);
        }
        if ("DAILY".equals(scheduleType)) {
            LocalTime time = parseTimeOfDay(timeOfDay);
            LocalDateTime next = LocalDateTime.of(LocalDate.now(), time);
            if (!next.isAfter(now)) {
                next = next.plusDays(1);
            }
            return next.format(DATABASE_DATETIME_MS);
        }
        return "";
    }

    private static LocalTime parseTimeOfDay(String value) {
        String normalized = value(value, "02:00");
        try {
            return LocalTime.parse(normalized.length() == 5 ? normalized + ":00" : normalized);
        } catch (RuntimeException ex) {
            return LocalTime.of(2, 0);
        }
    }

    private static String normalizeScheduleType(String raw) {
        String value = value(raw, "MANUAL_ONLY").toUpperCase(Locale.ROOT);
        return switch (value) {
            case "MANUAL_ONLY", "INTERVAL", "DAILY" -> value;
            default -> throw new IllegalArgumentException("Invalid scheduleType: " + raw);
        };
    }

    private static String normalizeCompareMode(String raw) {
        String value = value(raw, "FAST").toUpperCase(Locale.ROOT);
        return switch (value) {
            case "FAST" -> value;
            default -> throw new IllegalArgumentException("Invalid compareMode: " + raw);
        };
    }

    private static String normalizeConflictPolicy(String raw) {
        String value = value(raw, "SKIP").toUpperCase(Locale.ROOT);
        return switch (value) {
            case "SKIP", "OVERWRITE", "FAIL" -> value;
            default -> throw new IllegalArgumentException("Invalid conflictPolicy: " + raw);
        };
    }

    private static String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required directory copy task field: " + fieldName);
        }
        return value.trim();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String trim(String value, int limit) {
        String normalized = value(value, "");
        return normalized.length() > limit ? normalized.substring(0, limit) : normalized;
    }
}


