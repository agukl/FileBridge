package com.acme.ftpsync.files;

import com.acme.ftpsync.diagnostic.FtpPreflightService;
import com.acme.ftpsync.diagnostic.RemoteDirectoryCacheRepository;
import com.acme.ftpsync.task.SyncTask;
import com.acme.ftpsync.util.DateTimes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FileBrowserService {
    private final FtpPreflightService ftpPreflightService;
    private final RemoteDirectoryCacheRepository cacheRepository;

    public FileBrowserService(FtpPreflightService ftpPreflightService,
                              RemoteDirectoryCacheRepository cacheRepository) {
        this.ftpPreflightService = ftpPreflightService;
        this.cacheRepository = cacheRepository;
    }

    public FileListing listFiles(SyncTask source, String path, int limit) throws Exception {
        if (isFileSystemSource(source)) {
            return listFileSystemFiles(source, path, limit);
        }
        return listRemoteFiles(source, path, limit);
    }

    public FtpPreflightService.DirectoryCacheRefreshResult refreshDirectoryCache(SyncTask source) throws Exception {
        if (isFtpSource(source)) {
            return ftpPreflightService.refreshRemoteDirectoryCache(source, 50_000, 5_000);
        }
        return refreshFileSystemDirectoryCache(source, 50_000, 5_000);
    }

    private FileListing listRemoteFiles(SyncTask source, String path, int limit) throws Exception {
        String root = normalizeRemote(source.remotePath());
        String target = normalizeRemote(path == null || path.isBlank() ? root : path);
        if (!isSameOrChildRemotePath(root, target)) {
            throw new IOException("Path is outside file source root: " + target);
        }
        FtpPreflightService.RemoteFileListing listing = ftpPreflightService.listRemoteFiles(source, target, limit);
        String parentPath = isSameOrChildRemotePath(root, listing.parentPath()) ? listing.parentPath() : "";
        return new FileListing(
                listing.path(),
                parentPath,
                listing.entries().stream()
                        .map(entry -> new FileEntry(
                                entry.name(),
                                entry.path(),
                                entry.type(),
                                entry.size(),
                                entry.modifiedAt()
                        ))
                        .toList(),
                listing.replyCode(),
                listing.replyText(),
                listing.cacheUsed(),
                listing.cacheSource(),
                listing.cacheMessage(),
                listing.cacheScannedAt()
        );
    }

    private FileListing listFileSystemFiles(SyncTask source, String path, int limit) throws IOException {
        try (SmbCredentialSession ignored = SmbCredentialSession.open(source)) {
            Path root = localRoot(source);
            Path target = resolveLocalPath(root, path);
            if (!target.startsWith(root)) {
                throw new IOException("Path is outside file source root: " + target);
            }
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Local path does not exist: " + target);
            }
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Local path is not a directory: " + target);
            }

            int safeLimit = Math.max(1, Math.min(limit, 1000));
            List<FileEntry> entries = readLocalEntries(target);
            sortEntries(entries);
            List<FileEntry> visibleEntries = entries.size() > safeLimit
                    ? new ArrayList<>(entries.subList(0, safeLimit))
                    : entries;
            FileListing listing = new FileListing(
                    target.toString(),
                    localParentPath(root, target),
                    visibleEntries,
                    0,
                    "",
                    false,
                    "",
                    "",
                    ""
            );
            writeLocalStatsCache(source, listing, safeLimit, entries.size() > safeLimit);
            return listing;
        }
    }

    private FtpPreflightService.DirectoryCacheRefreshResult refreshFileSystemDirectoryCache(SyncTask source,
                                                                                           int maxDirectories,
                                                                                           int entryLimit) throws Exception {
        try (SmbCredentialSession ignored = SmbCredentialSession.open(source)) {
            return refreshConnectedFileSystemDirectoryCache(source, maxDirectories, entryLimit);
        }
    }

    private FtpPreflightService.DirectoryCacheRefreshResult refreshConnectedFileSystemDirectoryCache(SyncTask source,
                                                                                                    int maxDirectories,
                                                                                                    int entryLimit) throws Exception {
        Path root = localRoot(source);
        int safeMaxDirectories = Math.max(1, Math.min(maxDirectories, 50_000));
        int safeEntryLimit = Math.max(1, Math.min(entryLimit, 5_000));
        String startedAt = DateTimes.nowDatabase();
        ArrayDeque<Path> pending = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        pending.add(root);
        long cachedPathCount = 0L;
        long fileCount = 0L;
        long directoryCount = 0L;
        long otherCount = 0L;
        long totalBytes = 0L;
        long truncatedPathCount = 0L;
        long errorCount = 0L;
        boolean cacheCleared = false;
        while (!pending.isEmpty() && seen.size() < safeMaxDirectories) {
            Path current = pending.removeFirst().toAbsolutePath().normalize();
            String currentKey = current.toString().toLowerCase(Locale.ROOT);
            if (!seen.add(currentKey)) {
                continue;
            }
            try {
                List<FileEntry> entries = readLocalEntries(current);
                sortEntries(entries);
                for (FileEntry entry : entries) {
                    if ("DIRECTORY".equals(entry.type())) {
                        directoryCount++;
                        pending.add(Path.of(entry.path()));
                    } else if ("FILE".equals(entry.type())) {
                        fileCount++;
                        totalBytes += Math.max(0L, entry.size());
                    } else {
                        otherCount++;
                    }
                }
                boolean truncated = entries.size() > safeEntryLimit;
                if (truncated) {
                    truncatedPathCount++;
                }
                List<FileEntry> visibleEntries = truncated
                        ? new ArrayList<>(entries.subList(0, safeEntryLimit))
                        : entries;
                FileListing listing = new FileListing(
                        current.toString(),
                        localParentPath(root, current),
                        visibleEntries,
                        0,
                        "",
                        false,
                        "",
                        "",
                        ""
                );
                if (!cacheCleared) {
                    cacheRepository.deleteForTask(source);
                    cacheCleared = true;
                }
                saveLocalStatsCache(source, listing, safeEntryLimit, truncated);
                cachedPathCount++;
            } catch (IOException | RuntimeException ex) {
                if (current.equals(root) && cachedPathCount == 0L) {
                    throw ex;
                }
                errorCount++;
            }
        }
        boolean maxDirectoriesReached = !pending.isEmpty();
        return new FtpPreflightService.DirectoryCacheRefreshResult(
                true,
                source.taskId(),
                source.sourceType(),
                cachedPathCount,
                fileCount,
                directoryCount,
                otherCount,
                totalBytes,
                truncatedPathCount,
                errorCount,
                maxDirectoriesReached,
                startedAt,
                DateTimes.nowDatabase(),
                errorCount > 0
                        ? "Directory cache refreshed partially; some directories failed to read."
                        : maxDirectoriesReached
                        ? "Directory cache refreshed partially; directory limit reached."
                        : "Directory cache refreshed."
        );
    }

    private void writeLocalStatsCache(SyncTask source, FileListing listing, int limitUsed, boolean truncated) {
        try {
            saveLocalStatsCache(source, listing, limitUsed, truncated);
        } catch (Exception ignored) {
            // Local browsing remains live; cache write failures should not affect the user action.
        }
    }

    private void saveLocalStatsCache(SyncTask source, FileListing listing, int limitUsed, boolean truncated) throws Exception {
        List<FtpPreflightService.RemoteFileEntry> entries = listing.entries().stream()
                .map(entry -> new FtpPreflightService.RemoteFileEntry(
                        entry.name(),
                        entry.path(),
                        entry.type(),
                        entry.size(),
                        entry.modifiedAt()
                ))
                .toList();
        FtpPreflightService.RemoteFileListing cacheListing = new FtpPreflightService.RemoteFileListing(
                listing.path(),
                listing.parentPath(),
                entries,
                listing.replyCode(),
                listing.replyText(),
                false,
                "",
                "",
                ""
        );
        cacheRepository.saveFileListing(source, cacheListing, limitUsed, truncated);
    }

    private static List<FileEntry> readLocalEntries(Path target) throws IOException {
        List<FileEntry> entries = new ArrayList<>();
        try (var stream = Files.list(target)) {
            stream.forEach(child -> {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(
                            child,
                            BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS
                    );
                    String type = attrs.isDirectory() ? "DIRECTORY" : attrs.isRegularFile() ? "FILE" : "OTHER";
                    entries.add(new FileEntry(
                            child.getFileName() == null ? child.toString() : child.getFileName().toString(),
                            child.toAbsolutePath().normalize().toString(),
                            type,
                            attrs.isRegularFile() ? Math.max(0L, attrs.size()) : 0L,
                            attrs.lastModifiedTime().toInstant().toString()
                    ));
                } catch (IOException ex) {
                    entries.add(new FileEntry(
                            child.getFileName() == null ? child.toString() : child.getFileName().toString(),
                            child.toAbsolutePath().normalize().toString(),
                            "OTHER",
                            0L,
                            ""
                    ));
                }
            });
        }
        return entries;
    }

    private static void sortEntries(List<FileEntry> entries) {
        entries.sort((left, right) -> {
            boolean leftDir = "DIRECTORY".equals(left.type());
            boolean rightDir = "DIRECTORY".equals(right.type());
            if (leftDir != rightDir) {
                return leftDir ? -1 : 1;
            }
            return left.name().compareToIgnoreCase(right.name());
        });
    }

    private static Path localRoot(SyncTask source) throws IOException {
        String raw = source.sourcePath();
        if (raw == null || raw.isBlank()) {
            throw new IOException("Local file source path is empty.");
        }
        Path root = LocalPathSupport.toAbsolutePath(raw);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(LocalPathSupport.missingPathMessage(raw, root));
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(LocalPathSupport.notDirectoryMessage(raw, root));
        }
        return root;
    }

    private static Path resolveLocalPath(Path root, String path) {
        return LocalPathSupport.resolveWithinRoot(root, path);
    }

    private static String localParentPath(Path root, Path target) {
        Path parent = target.getParent();
        if (parent == null || target.equals(root) || !parent.startsWith(root)) {
            return "";
        }
        return parent.toString();
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

    private static boolean isSameOrChildRemotePath(String root, String path) {
        String normalizedRoot = normalizeRemote(root);
        String normalizedPath = normalizeRemote(path);
        if ("/".equals(normalizedRoot)) {
            return true;
        }
        return normalizedPath.equals(normalizedRoot) || normalizedPath.startsWith(normalizedRoot + "/");
    }

    private static boolean isFileSystemSource(SyncTask source) {
        String sourceType = source.sourceType() == null ? "REMOTE_FTP" : source.sourceType().trim().toUpperCase(Locale.ROOT);
        return "LOCAL".equals(sourceType) || "SMB".equals(sourceType);
    }

    private static boolean isFtpSource(SyncTask source) {
        String sourceType = source.sourceType() == null ? "REMOTE_FTP" : source.sourceType().trim().toUpperCase(Locale.ROOT);
        return "REMOTE_FTP".equals(sourceType);
    }

    public record FileEntry(
            String name,
            String path,
            String type,
            long size,
            String modifiedAt
    ) {
    }

    public record FileListing(
            String path,
            String parentPath,
            List<FileEntry> entries,
            int replyCode,
            String replyText,
            boolean cacheUsed,
            String cacheSource,
            String cacheMessage,
            String cacheScannedAt
    ) {
    }
}

