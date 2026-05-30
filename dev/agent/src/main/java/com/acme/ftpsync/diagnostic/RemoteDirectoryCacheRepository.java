package com.acme.ftpsync.diagnostic;

import com.acme.ftpsync.db.Jdbc;
import com.acme.ftpsync.task.SyncTask;
import com.acme.ftpsync.util.DateTimes;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class RemoteDirectoryCacheRepository {
    private static final TypeReference<List<FtpPreflightService.RemoteDirectoryEntry>> DIRECTORY_LIST_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<List<FtpPreflightService.RemoteFileEntry>> FILE_LIST_TYPE =
            new TypeReference<>() {
            };

    private final Jdbc jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public RemoteDirectoryCacheRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CachedDirectoryListing> find(SyncTask task, String path, int requestedLimit) throws Exception {
        String normalizedPath = normalizeRemote(path);
        String sql = """
                SELECT path_text, parent_path, directory_modified_at, directories_json,
                       reply_code, reply_text, limit_used, truncated, scanned_at
                FROM file_source_directory_cache
                WHERE task_id=? AND path_hash=? AND source_signature=?
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, task.taskId());
            ps.setString(2, sha256Hex(normalizedPath));
            ps.setString(3, sourceSignature(task));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                int limitUsed = rs.getInt("limit_used");
                boolean truncated = rs.getBoolean("truncated");
                if (requestedLimit > limitUsed && truncated) {
                    return Optional.empty();
                }
                List<FtpPreflightService.RemoteDirectoryEntry> entries =
                        mapper.readValue(rs.getString("directories_json"), DIRECTORY_LIST_TYPE);
                int effectiveLimit = Math.min(requestedLimit, entries.size());
                List<FtpPreflightService.RemoteDirectoryEntry> visibleEntries =
                        new ArrayList<>(entries.subList(0, effectiveLimit));
                return Optional.of(new CachedDirectoryListing(
                        rs.getString("path_text"),
                        rs.getString("parent_path"),
                        value(rs.getString("directory_modified_at"), ""),
                        visibleEntries,
                        rs.getInt("reply_code"),
                        value(rs.getString("reply_text"), ""),
                        limitUsed,
                        truncated,
                        value(rs.getString("scanned_at"), "")
                ));
            }
        }
    }

    public Optional<CachedFileListing> findFileListing(SyncTask task, String path, int requestedLimit) throws Exception {
        String normalizedPath = normalizeRemote(path);
        String sql = """
                SELECT path_text, parent_path, entries_json,
                       reply_code, reply_text, limit_used, truncated, scanned_at
                FROM file_source_directory_cache
                WHERE task_id=? AND path_hash=? AND source_signature=?
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, task.taskId());
            ps.setString(2, sha256Hex(normalizedPath));
            ps.setString(3, sourceSignature(task));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String entriesJson = rs.getString("entries_json");
                if (entriesJson == null || entriesJson.isBlank()) {
                    return Optional.empty();
                }
                int limitUsed = rs.getInt("limit_used");
                boolean truncated = rs.getBoolean("truncated");
                if (requestedLimit > limitUsed && truncated) {
                    return Optional.empty();
                }
                List<FtpPreflightService.RemoteFileEntry> entries =
                        mapper.readValue(entriesJson, FILE_LIST_TYPE);
                int effectiveLimit = Math.min(requestedLimit, entries.size());
                List<FtpPreflightService.RemoteFileEntry> visibleEntries =
                        new ArrayList<>(entries.subList(0, effectiveLimit));
                return Optional.of(new CachedFileListing(
                        rs.getString("path_text"),
                        rs.getString("parent_path"),
                        visibleEntries,
                        rs.getInt("reply_code"),
                        value(rs.getString("reply_text"), ""),
                        limitUsed,
                        truncated,
                        value(rs.getString("scanned_at"), "")
                ));
            }
        }
    }

    public Optional<CachedFileListing> findFileListingSnapshot(SyncTask task, String path) throws Exception {
        String normalizedPath = normalizeRemote(path);
        String sql = """
                SELECT path_text, parent_path, entries_json,
                       reply_code, reply_text, limit_used, truncated, scanned_at
                FROM file_source_directory_cache
                WHERE task_id=? AND path_hash=? AND source_signature=?
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, task.taskId());
            ps.setString(2, sha256Hex(normalizedPath));
            ps.setString(3, sourceSignature(task));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String entriesJson = rs.getString("entries_json");
                if (entriesJson == null || entriesJson.isBlank()) {
                    return Optional.empty();
                }
                List<FtpPreflightService.RemoteFileEntry> entries =
                        mapper.readValue(entriesJson, FILE_LIST_TYPE);
                return Optional.of(new CachedFileListing(
                        rs.getString("path_text"),
                        rs.getString("parent_path"),
                        entries,
                        rs.getInt("reply_code"),
                        value(rs.getString("reply_text"), ""),
                        rs.getInt("limit_used"),
                        rs.getBoolean("truncated"),
                        value(rs.getString("scanned_at"), "")
                ));
            }
        }
    }

    public void save(SyncTask task,
                     FtpPreflightService.RemoteDirectoryListing listing,
                     String directoryModifiedAt,
                     int limitUsed,
                     boolean truncated) throws Exception {
        String normalizedPath = normalizeRemote(listing.path());
        String sql = """
                INSERT INTO file_source_directory_cache (
                  task_id, path_hash, source_signature, path_text, parent_path, directory_modified_at,
                  directories_json, reply_code, reply_text, limit_used, truncated, scanned_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime'))
                ON CONFLICT(task_id, path_hash) DO UPDATE SET
                  source_signature=excluded.source_signature,
                  path_text=excluded.path_text,
                  parent_path=excluded.parent_path,
                  directory_modified_at=excluded.directory_modified_at,
                  directories_json=excluded.directories_json,
                  reply_code=excluded.reply_code,
                  reply_text=excluded.reply_text,
                  limit_used=excluded.limit_used,
                  truncated=excluded.truncated,
                  scanned_at=excluded.scanned_at,
                  updated_at=excluded.updated_at
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, task.taskId());
            ps.setString(2, sha256Hex(normalizedPath));
            ps.setString(3, sourceSignature(task));
            ps.setString(4, normalizedPath);
            ps.setString(5, value(listing.parentPath(), ""));
            ps.setString(6, value(directoryModifiedAt, ""));
            ps.setString(7, mapper.writeValueAsString(listing.directories()));
            ps.setInt(8, listing.replyCode());
            ps.setString(9, value(listing.replyText(), ""));
            ps.setInt(10, limitUsed);
            ps.setBoolean(11, truncated);
            ps.setString(12, DateTimes.nowDatabase());
            ps.executeUpdate();
        }
    }

    public void saveFileListing(SyncTask task,
                                FtpPreflightService.RemoteFileListing listing,
                                int limitUsed,
                                boolean truncated) throws Exception {
        String normalizedPath = normalizeRemote(listing.path());
        String sql = """
                INSERT INTO file_source_directory_cache (
                  task_id, path_hash, source_signature, path_text, parent_path,
                  directories_json, entries_json, reply_code, reply_text, limit_used, truncated, scanned_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime'))
                ON CONFLICT(task_id, path_hash) DO UPDATE SET
                  source_signature=excluded.source_signature,
                  path_text=excluded.path_text,
                  parent_path=excluded.parent_path,
                  directories_json=excluded.directories_json,
                  entries_json=excluded.entries_json,
                  reply_code=excluded.reply_code,
                  reply_text=excluded.reply_text,
                  limit_used=excluded.limit_used,
                  truncated=excluded.truncated,
                  scanned_at=excluded.scanned_at,
                  updated_at=excluded.updated_at
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, task.taskId());
            ps.setString(2, sha256Hex(normalizedPath));
            ps.setString(3, sourceSignature(task));
            ps.setString(4, normalizedPath);
            ps.setString(5, value(listing.parentPath(), ""));
            ps.setString(6, mapper.writeValueAsString(toDirectoryEntries(listing.entries())));
            ps.setString(7, mapper.writeValueAsString(listing.entries()));
            ps.setInt(8, listing.replyCode());
            ps.setString(9, value(listing.replyText(), ""));
            ps.setInt(10, limitUsed);
            ps.setBoolean(11, truncated);
            ps.setString(12, DateTimes.nowDatabase());
            ps.executeUpdate();
        }
    }

    public int deleteForTask(SyncTask task) throws SQLException {
        String sql = """
                DELETE FROM file_source_directory_cache
                WHERE task_id=? AND source_signature=?
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, task.taskId());
            ps.setString(2, sourceSignature(task));
            return ps.executeUpdate();
        }
    }

    public CacheStats stats(SyncTask task) throws Exception {
        boolean remote = "REMOTE_FTP".equalsIgnoreCase(value(task.sourceType(), "REMOTE_FTP"));
        boolean enabled = !remote || task.remoteDirectoryCacheEnabled();
        if (remote && !enabled) {
            return CacheStats.disabled();
        }

        String sql = """
                SELECT directories_json, entries_json, truncated, scanned_at
                FROM file_source_directory_cache
                WHERE task_id=? AND source_signature=?
                """;
        long cachedPathCount = 0L;
        long cachedFileCount = 0L;
        long cachedDirectoryCount = 0L;
        long cachedOtherCount = 0L;
        long cachedTotalBytes = 0L;
        long truncatedPathCount = 0L;
        String oldestScannedAt = "";
        String latestScannedAt = "";
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, task.taskId());
            ps.setString(2, sourceSignature(task));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cachedPathCount++;
                    if (rs.getBoolean("truncated")) {
                        truncatedPathCount++;
                    }
                    String scannedAt = value(rs.getString("scanned_at"), "");
                    if (!scannedAt.isBlank()) {
                        if (oldestScannedAt.isBlank() || scannedAt.compareTo(oldestScannedAt) < 0) {
                            oldestScannedAt = scannedAt;
                        }
                        if (latestScannedAt.isBlank() || scannedAt.compareTo(latestScannedAt) > 0) {
                            latestScannedAt = scannedAt;
                        }
                    }
                    String entriesJson = rs.getString("entries_json");
                    if (entriesJson != null && !entriesJson.isBlank()) {
                        List<FtpPreflightService.RemoteFileEntry> entries =
                                mapper.readValue(entriesJson, FILE_LIST_TYPE);
                        for (FtpPreflightService.RemoteFileEntry entry : entries) {
                            String type = value(entry.type(), "").toUpperCase(Locale.ROOT);
                            if ("FILE".equals(type)) {
                                cachedFileCount++;
                                cachedTotalBytes += Math.max(0L, entry.size());
                            } else if ("DIRECTORY".equals(type)) {
                                cachedDirectoryCount++;
                            } else {
                                cachedOtherCount++;
                            }
                        }
                        continue;
                    }
                    String directoriesJson = rs.getString("directories_json");
                    if (directoriesJson != null && !directoriesJson.isBlank()) {
                        List<FtpPreflightService.RemoteDirectoryEntry> directories =
                                mapper.readValue(directoriesJson, DIRECTORY_LIST_TYPE);
                        cachedDirectoryCount += directories.size();
                    }
                }
            }
        }
        if (cachedPathCount == 0L) {
            return remote ? CacheStats.emptyRemote() : CacheStats.emptyLocal();
        }
        CacheRefreshStatus refreshStatus = latestDirectoryCacheRefreshStatus(task);
        return new CacheStats(
                true,
                true,
                cachedPathCount,
                cachedFileCount,
                cachedDirectoryCount,
                cachedOtherCount,
                cachedTotalBytes,
                truncatedPathCount,
                oldestScannedAt,
                latestScannedAt,
                refreshStatus.partial() ? "PARTIAL" : "READY",
                refreshStatus.partial()
                        ? refreshStatus.message(cachedPathCount)
                        : remote ? "Remote directory cache is ready." : "Local stats cache is ready."
        );
    }

    private CacheRefreshStatus latestDirectoryCacheRefreshStatus(SyncTask task) throws SQLException {
        String sql = """
                SELECT final_health, warning_count, error_count
                FROM file_operation
                WHERE task_id=? AND operation_type='DIRECTORY_CACHE_REFRESH'
                ORDER BY started_at DESC, id DESC
                LIMIT 1
                """;
        try (Connection connection = jdbc.open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, task.taskId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return CacheRefreshStatus.empty();
                }
                String finalHealth = value(rs.getString("final_health"), "").toUpperCase(Locale.ROOT);
                long warningCount = rs.getLong("warning_count");
                long errorCount = rs.getLong("error_count");
                boolean partial = errorCount > 0L
                        || warningCount > 0L
                        || "COMPLETED_WITH_WARNINGS".equals(finalHealth);
                return new CacheRefreshStatus(partial, errorCount, warningCount);
            }
        }
    }


    private static String sourceSignature(SyncTask task) {
        return sha256Hex(String.join("\n",
                value(task.taskId(), ""),
                value(task.ftpHost(), ""),
                String.valueOf(task.ftpPort() <= 0 ? 21 : task.ftpPort()),
                value(task.ftpUsername(), ""),
                value(task.secureMode(), "NONE").toUpperCase(Locale.ROOT),
                value(task.tlsFingerprint(), ""),
                value(task.tlsFingerprintHash(), "SHA256").toUpperCase(Locale.ROOT),
                normalizeRemote(task.sourcePath())
        ));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value(value, "").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private static String normalizeRemote(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.replace('\\', '/').trim().replaceAll("/+$", "");
        if (normalized.isBlank()) {
            return "/";
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<FtpPreflightService.RemoteDirectoryEntry> toDirectoryEntries(
            List<FtpPreflightService.RemoteFileEntry> entries) {
        List<FtpPreflightService.RemoteDirectoryEntry> directories = new ArrayList<>();
        for (FtpPreflightService.RemoteFileEntry entry : entries) {
            if (!"DIRECTORY".equalsIgnoreCase(value(entry.type(), ""))) {
                continue;
            }
            directories.add(new FtpPreflightService.RemoteDirectoryEntry(
                    entry.name(),
                    entry.path(),
                    entry.modifiedAt()
            ));
        }
        return directories;
    }

    public record CachedDirectoryListing(
            String path,
            String parentPath,
            String directoryModifiedAt,
            List<FtpPreflightService.RemoteDirectoryEntry> directories,
            int replyCode,
            String replyText,
            int limitUsed,
            boolean truncated,
            String scannedAt
    ) {
    }

    public record CachedFileListing(
            String path,
            String parentPath,
            List<FtpPreflightService.RemoteFileEntry> entries,
            int replyCode,
            String replyText,
            int limitUsed,
            boolean truncated,
            String scannedAt
    ) {
    }

    public record CacheStats(
            boolean enabled,
            boolean applicable,
            long cachedPathCount,
            long cachedFileCount,
            long cachedDirectoryCount,
            long cachedOtherCount,
            long cachedTotalBytes,
            long truncatedPathCount,
            String oldestScannedAt,
            String latestScannedAt,
            String state,
            String message
    ) {
        public static CacheStats notApplicable() {
            return emptyState(false, false, "NOT_APPLICABLE", "Directory cache stats are not applicable for this file source.");
        }

        public static CacheStats disabled() {
            return emptyState(false, true, "DISABLED", "Remote directory cache is disabled for this file source.");
        }

        public static CacheStats emptyRemote() {
            return emptyState(true, true, "EMPTY", "Remote directory cache is enabled, but no cached directories are available yet.");
        }

        public static CacheStats emptyLocal() {
            return emptyState(true, true, "EMPTY", "Local stats cache is created automatically when browsing local directories.");
        }

        public static CacheStats failed(String message) {
            return emptyState(true, true, "FAILED", value(message, "Failed to read directory cache stats."));
        }
        private static CacheStats emptyState(boolean enabled, boolean applicable, String state, String message) {
            return new CacheStats(enabled, applicable, 0L, 0L, 0L, 0L, 0L, 0L,
                    "", "", state, message);
        }
    }

    private record CacheRefreshStatus(
            boolean partial,
            long errorCount,
            long warningCount
    ) {
        private static CacheRefreshStatus empty() {
            return new CacheRefreshStatus(false, 0L, 0L);
        }

        private String message(long cachedPathCount) {
            if (errorCount > 0L) {
                return "Directory cache is partial: indexed " + cachedPathCount
                        + " paths, but " + errorCount + " directories failed to read.";
            }
            if (warningCount > 0L) {
                return "Directory cache did not finish completely; current stats may be incomplete.";
            }
            return "Directory cache is incomplete.";
        }
    }
}


