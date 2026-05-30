package com.acme.ftpsync.task;

import com.acme.ftpsync.db.Jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TaskRepository {
    private final Jdbc jdbc;

    public TaskRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(SyncTask task) throws SQLException {
        SyncTask normalized = normalize(task);
        String sql = """
                INSERT INTO file_source (
                  task_id, task_name, source_type, ftp_host, ftp_port, ftp_username, password_ref,
                  secure_mode, tls_fingerprint, tls_fingerprint_hash, source_path,
                  remote_directory_cache_enabled, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime'))
                ON CONFLICT(task_id) DO UPDATE SET
                  task_name=excluded.task_name,
                  source_type=excluded.source_type,
                  ftp_host=excluded.ftp_host,
                  ftp_port=excluded.ftp_port,
                  ftp_username=excluded.ftp_username,
                  password_ref=excluded.password_ref,
                  secure_mode=excluded.secure_mode,
                  tls_fingerprint=excluded.tls_fingerprint,
                  tls_fingerprint_hash=excluded.tls_fingerprint_hash,
                  source_path=excluded.source_path,
                  remote_directory_cache_enabled=excluded.remote_directory_cache_enabled,
                  updated_at=excluded.updated_at
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, normalized);
            ps.executeUpdate();
        }
    }

    public Optional<SyncTask> find(String taskId) throws SQLException {
        String sql = "SELECT * FROM file_source WHERE task_id=?";
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toTask(rs)) : Optional.empty();
            }
        }
    }

    public List<SyncTask> list(int limit) throws SQLException {
        String sql = "SELECT * FROM file_source ORDER BY updated_at DESC, task_id ASC LIMIT ?";
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                List<SyncTask> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toTask(rs));
                }
                return result;
            }
        }
    }

    public boolean delete(String taskId) throws SQLException {
        String normalizedTaskId = required(taskId, "taskId");
        try (Connection connection = jdbc.open()) {
            connection.setAutoCommit(false);
            try {
                int taskReferences = count(connection,
                        "SELECT COUNT(*) FROM directory_copy_task WHERE source_file_source_id=? OR target_file_source_id=?",
                        normalizedTaskId,
                        normalizedTaskId);
                if (taskReferences > 0) {
                    throw new IllegalStateException("File source is used by " + taskReferences
                            + " directory copy task(s). Delete or update those tasks first.");
                }
                int runningOperations = count(connection,
                        "SELECT COUNT(*) FROM file_operation WHERE state='RUNNING' AND (task_id=? OR target_task_id=?)",
                        normalizedTaskId,
                        normalizedTaskId);
                if (runningOperations > 0) {
                    throw new IllegalStateException("File source has running operation(s) and cannot be deleted.");
                }
                executeUpdate(connection, "DELETE FROM file_source_directory_cache WHERE task_id=?", normalizedTaskId);
                boolean deleted = executeUpdate(connection, "DELETE FROM file_source WHERE task_id=?", normalizedTaskId) > 0;
                connection.commit();
                return deleted;
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void updatePermission(String taskId, FileSourcePermission permission) throws SQLException {
        FileSourcePermission normalized = permission == null ? FileSourcePermission.unknown() : permission;
        String sql = """
                UPDATE file_source
                SET can_read=?, can_write=?, permission_state=?, permission_checked_at=?, permission_message=?,
                    updated_at=strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')
                WHERE task_id=?
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setBoolean(1, normalized.canRead());
            ps.setBoolean(2, normalized.canWrite());
            ps.setString(3, value(normalized.state(), "UNKNOWN"));
            if (normalized.checkedAt() == null || normalized.checkedAt().isBlank()) {
                ps.setNull(4, java.sql.Types.TIMESTAMP);
            } else {
                ps.setString(4, normalized.checkedAt());
            }
            ps.setString(5, trimToLimit(normalized.message(), 1024));
            ps.setString(6, taskId);
            ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, SyncTask task) throws SQLException {
        ps.setString(1, task.taskId());
        ps.setString(2, task.taskName());
        ps.setString(3, task.sourceType());
        ps.setString(4, task.ftpHost());
        ps.setInt(5, task.ftpPort());
        ps.setString(6, task.ftpUsername());
        ps.setString(7, task.passwordRef());
        ps.setString(8, task.secureMode());
        ps.setString(9, task.tlsFingerprint());
        ps.setString(10, task.tlsFingerprintHash());
        ps.setString(11, task.sourcePath());
        ps.setBoolean(12, task.remoteDirectoryCacheEnabled());
    }

    private static SyncTask toTask(ResultSet rs) throws SQLException {
        return new SyncTask(
                rs.getString("task_id"),
                rs.getString("task_name"),
                value(rs, "source_type", "REMOTE_FTP"),
                rs.getString("ftp_host"),
                rs.getInt("ftp_port"),
                rs.getString("ftp_username"),
                rs.getString("password_ref"),
                rs.getString("secure_mode"),
                rs.getString("tls_fingerprint"),
                rs.getString("tls_fingerprint_hash"),
                sourcePath(rs),
                booleanValue(rs, "remote_directory_cache_enabled"),
                permission(rs)
        );
    }

    private static SyncTask normalize(SyncTask task) {
        String sourceType = normalizeSourceType(task.sourceType());
        boolean local = "LOCAL".equals(sourceType);
        boolean smb = "SMB".equals(sourceType);
        boolean ftp = "REMOTE_FTP".equals(sourceType);
        return new SyncTask(
                required(task.taskId(), "taskId"),
                value(task.taskName(), task.taskId()),
                sourceType,
                ftp || smb ? required(task.ftpHost(), smb ? "smbHost" : "ftpHost") : "",
                ftp ? (task.ftpPort() <= 0 ? 21 : task.ftpPort()) : smb ? 445 : 0,
                ftp ? required(task.ftpUsername(), "ftpUsername") : smb ? value(task.ftpUsername(), "") : "",
                local ? "" : value(task.passwordRef(), ""),
                ftp ? value(task.secureMode(), "NONE").toUpperCase() : "NONE",
                ftp ? normalizeFingerprint(task.tlsFingerprint()) : "",
                ftp ? normalizeFingerprintHash(task.tlsFingerprintHash()) : "SHA256",
                required(task.sourcePath(), "sourcePath"),
                ftp && task.remoteDirectoryCacheEnabled(),
                task.permission()
        );
    }

    private static FileSourcePermission permission(ResultSet rs) throws SQLException {
        return new FileSourcePermission(
                booleanValue(rs, "can_read"),
                booleanValue(rs, "can_write"),
                value(rs, "permission_state", "UNKNOWN"),
                value(rs, "permission_checked_at", ""),
                value(rs, "permission_message", "")
        );
    }

    private static int count(Connection connection, String sql, String first, String second) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, first);
            ps.setString(2, second);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static int executeUpdate(Connection connection, String sql, String taskId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, taskId);
            return ps.executeUpdate();
        }
    }

    private static String sourcePath(ResultSet rs) throws SQLException {
        String value = value(rs, "source_path", "");
        if (!value.isBlank()) {
            return value;
        }
        String sourceType = value(rs, "source_type", "REMOTE_FTP");
        if ("LOCAL".equalsIgnoreCase(sourceType)) {
            return value(rs, "local_path", "");
        }
        return value(rs, "remote_path", "");
    }

    private static String normalizeSourceType(String raw) {
        String value = value(raw, "REMOTE_FTP").toUpperCase();
        return switch (value) {
            case "LOCAL", "LOCAL_FILE", "LOCAL_FILES" -> "LOCAL";
            case "REMOTE", "FTP", "REMOTE_FTP" -> "REMOTE_FTP";
            case "SMB", "WINDOWS_SHARE", "UNC" -> "SMB";
            default -> throw new IllegalArgumentException(
                    "Invalid sourceType: " + raw + ". Allowed: REMOTE_FTP, LOCAL, SMB.");
        };
    }

    private static String normalizeFingerprint(String raw) {
        return raw == null ? "" : raw.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
    }

    private static String normalizeFingerprintHash(String raw) {
        String value = value(raw, "SHA256").toUpperCase();
        return switch (value) {
            case "SHA1", "SHA256" -> value;
            default -> throw new IllegalArgumentException(
                    "Invalid tlsFingerprintHash: " + raw + ". Allowed: SHA1, SHA256.");
        };
    }

    private static String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required task field: " + fieldName);
        }
        return value.trim();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String value(ResultSet rs, String column, String fallback) throws SQLException {
        try {
            Object value = rs.getObject(column);
            return value == null ? fallback : value.toString();
        } catch (SQLException ex) {
            return fallback;
        }
    }

    private static boolean booleanValue(ResultSet rs, String column) throws SQLException {
        try {
            return rs.getBoolean(column);
        } catch (SQLException ex) {
            return false;
        }
    }

    private static String trimToLimit(String value, int limit) {
        String normalized = value(value, "");
        return normalized.length() > limit ? normalized.substring(0, limit) : normalized;
    }
}

