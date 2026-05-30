package com.acme.ftpsync.diagnostic;

import com.acme.ftpsync.db.Jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TaskDiagnosticRepository {
    private final Jdbc jdbc;

    public TaskDiagnosticRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public DiagnosticReport save(DiagnosticReport report) throws SQLException {
        String reportSql = """
                INSERT INTO file_source_diagnostic (
                  task_id, check_type, state, started_at, finished_at, duration_ms,
                  error_category, handling_action, health_impact, message, details_text
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        String stepSql = """
                INSERT INTO file_source_diagnostic_step (
                  diagnostic_id, seq, step_name, state, error_category, retryable, reply_code, reply_text,
                  remote_path, local_path, duration_ms, handling_action, health_impact, message, details_text
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = jdbc.open()) {
            connection.setAutoCommit(false);
            try {
                long reportId;
                try (PreparedStatement ps = connection.prepareStatement(reportSql, Statement.RETURN_GENERATED_KEYS)) {
                    bindReport(ps, report);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Failed to create file_source_diagnostic, generated key missing.");
                        }
                        reportId = keys.getLong(1);
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(stepSql, Statement.RETURN_GENERATED_KEYS)) {
                    for (DiagnosticStep step : report.steps()) {
                        bindStep(ps, reportId, step);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                connection.commit();
                return find(connection, reportId).orElseThrow(() ->
                        new SQLException("Saved diagnostic report is not readable: " + reportId));
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Optional<DiagnosticReport> find(long id) throws SQLException {
        try (Connection connection = jdbc.open()) {
            return find(connection, id);
        }
    }

    public List<DiagnosticReport> listByTask(String taskId, int limit) throws SQLException {
        String sql = """
                SELECT *
                FROM file_source_diagnostic
                WHERE task_id=?
                ORDER BY started_at DESC, id DESC
                LIMIT ?
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setInt(2, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                List<DiagnosticReport> result = new ArrayList<>();
                while (rs.next()) {
                    DiagnosticReport report = toReport(rs, List.of());
                    result.add(report);
                }
                return result;
            }
        }
    }

    private Optional<DiagnosticReport> find(Connection connection, long id) throws SQLException {
        String sql = "SELECT * FROM file_source_diagnostic WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(toReport(rs, listSteps(connection, id)));
            }
        }
    }

    private List<DiagnosticStep> listSteps(Connection connection, long diagnosticId) throws SQLException {
        String sql = "SELECT * FROM file_source_diagnostic_step WHERE diagnostic_id=? ORDER BY seq ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, diagnosticId);
            try (ResultSet rs = ps.executeQuery()) {
                List<DiagnosticStep> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toStep(rs));
                }
                return result;
            }
        }
    }

    private static void bindReport(PreparedStatement ps, DiagnosticReport report) throws SQLException {
        ps.setString(1, report.taskId());
        ps.setString(2, report.checkType());
        ps.setString(3, report.state());
        ps.setString(4, report.startedAt());
        ps.setString(5, report.finishedAt());
        setNullableLong(ps, 6, report.durationMs());
        ps.setString(7, report.errorCategory());
        ps.setString(8, report.handlingAction());
        ps.setString(9, report.healthImpact());
        ps.setString(10, report.message());
        ps.setString(11, report.detailsText());
    }

    private static void bindStep(PreparedStatement ps, long diagnosticId, DiagnosticStep step) throws SQLException {
        ps.setLong(1, diagnosticId);
        ps.setInt(2, step.seq());
        ps.setString(3, step.stepName());
        ps.setString(4, step.state());
        ps.setString(5, step.errorCategory());
        ps.setBoolean(6, step.retryable());
        setNullableInt(ps, 7, step.replyCode());
        ps.setString(8, step.replyText());
        ps.setString(9, step.remotePath());
        ps.setString(10, step.localPath());
        setNullableLong(ps, 11, step.durationMs());
        ps.setString(12, step.handlingAction());
        ps.setString(13, step.healthImpact());
        ps.setString(14, step.message());
        ps.setString(15, step.detailsText());
    }

    private static DiagnosticReport toReport(ResultSet rs, List<DiagnosticStep> steps) throws SQLException {
        return new DiagnosticReport(
                rs.getLong("id"),
                rs.getString("task_id"),
                rs.getString("check_type"),
                rs.getString("state"),
                value(rs, "started_at"),
                value(rs, "finished_at"),
                nullableLong(rs, "duration_ms"),
                rs.getString("error_category"),
                rs.getString("handling_action"),
                rs.getString("health_impact"),
                rs.getString("message"),
                rs.getString("details_text"),
                steps
        );
    }

    private static DiagnosticStep toStep(ResultSet rs) throws SQLException {
        return new DiagnosticStep(
                rs.getLong("id"),
                rs.getLong("diagnostic_id"),
                rs.getInt("seq"),
                rs.getString("step_name"),
                rs.getString("state"),
                rs.getString("error_category"),
                rs.getBoolean("retryable"),
                nullableInt(rs, "reply_code"),
                rs.getString("reply_text"),
                rs.getString("remote_path"),
                rs.getString("local_path"),
                nullableLong(rs, "duration_ms"),
                rs.getString("handling_action"),
                rs.getString("health_impact"),
                rs.getString("message"),
                rs.getString("details_text")
        );
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
}

