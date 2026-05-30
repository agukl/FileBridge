package com.acme.ftpsync.diagnostic;

import com.acme.ftpsync.error.FtpErrorPolicy;
import com.acme.ftpsync.error.FtpErrorPolicyCatalog;
import com.acme.ftpsync.config.FingerprintHash;
import com.acme.ftpsync.security.PinningTrustManager;
import com.acme.ftpsync.task.FileSourcePermission;
import com.acme.ftpsync.task.SyncTask;
import com.acme.ftpsync.task.TaskRepository;
import com.acme.ftpsync.util.DateTimes;
import com.acme.ftpsync.util.PasswordRefs;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

public final class FtpPreflightService {
    private static final Duration PREFLIGHT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REMOTE_DIRECTORY_CACHE_TTL = Duration.ofSeconds(20);
    private static final Duration REMOTE_FILE_LISTING_CACHE_TTL = Duration.ofSeconds(8);
    private static final int REMOTE_LISTING_CACHE_MAX_ENTRIES = 256;
    private static final Duration REMOTE_CLIENT_CACHE_TTL = Duration.ofSeconds(20);
    private static final int REMOTE_CLIENT_CACHE_MAX_ENTRIES = 48;
    private static final int REMOTE_LIST_RETRY_ATTEMPTS = 2;

    private final TaskRepository taskRepository;
    private final TaskDiagnosticRepository diagnosticRepository;
    private final RemoteDirectoryCacheRepository remoteDirectoryCacheRepository;
    private final Object remoteDirectoryCacheLock = new Object();
    private final Object remoteFileCacheLock = new Object();
    private final Object remoteClientCacheLock = new Object();
    private final LinkedHashMap<RemoteListingCacheKey, CachedRemoteDirectoryListing> remoteDirectoryCache =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<RemoteListingCacheKey, CachedRemoteDirectoryListing> eldest) {
                    return size() > REMOTE_LISTING_CACHE_MAX_ENTRIES;
                }
            };
    private final LinkedHashMap<RemoteListingCacheKey, CachedRemoteFileListing> remoteFileCache =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<RemoteListingCacheKey, CachedRemoteFileListing> eldest) {
                    return size() > REMOTE_LISTING_CACHE_MAX_ENTRIES;
                }
            };
    private final LinkedHashMap<RemoteClientCacheKey, CachedRemoteClient> remoteClientCache =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<RemoteClientCacheKey, CachedRemoteClient> eldest) {
                    boolean shouldEvict = size() > REMOTE_CLIENT_CACHE_MAX_ENTRIES;
                    if (shouldEvict) {
                        disconnectQuietly(eldest.getValue().client());
                    }
                    return shouldEvict;
                }
            };

    public FtpPreflightService(TaskRepository taskRepository,
                               TaskDiagnosticRepository diagnosticRepository,
                               RemoteDirectoryCacheRepository remoteDirectoryCacheRepository) {
        this.taskRepository = taskRepository;
        this.diagnosticRepository = diagnosticRepository;
        this.remoteDirectoryCacheRepository = remoteDirectoryCacheRepository;
    }

    public DiagnosticReport runTask(String taskId) throws Exception {
        Optional<SyncTask> task = taskRepository.find(taskId);
        if (task.isEmpty()) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return run(task.get());
    }

    public DiagnosticReport runDraft(SyncTask task) throws Exception {
        return run(withStoredPassword(task));
    }

    public RemoteDirectoryListing listRemoteDirectories(SyncTask task, String path, int limit) throws Exception {
        task = withStoredPassword(task);
        String remotePath = normalizeRemote(path == null || path.isBlank() ? task.remotePath() : path);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        RemoteListingCacheKey cacheKey = RemoteListingCacheKey.of(task, remotePath);
        boolean useDirectoryCache = task.remoteDirectoryCacheEnabled();
        if (useDirectoryCache) {
            Optional<RemoteDirectoryListing> cached = readCachedRemoteDirectories(cacheKey, safeLimit);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        RemoteClientCacheKey remoteClientKey = RemoteClientCacheKey.of(task);
        IOException failure = null;
        for (int attempt = 1; attempt <= REMOTE_LIST_RETRY_ATTEMPTS; attempt++) {
            FTPClient client = borrowRemoteClient(task, remoteClientKey);
            boolean keepClient = false;
            try {
                String directoryModifiedAt = "";
                if (useDirectoryCache) {
                    Optional<RemoteDirectoryListing> persistent = readPersistentRemoteDirectories(
                            task,
                            remotePath,
                            safeLimit,
                            client
                    );
                    if (persistent.isPresent()) {
                        keepClient = true;
                        return persistent.get();
                    }
                    directoryModifiedAt = readRemoteDirectoryModifiedAt(client, remotePath);
                }
                FTPFile[] files = listRemoteDirectoryEntries(client, remotePath);
                int code = client.getReplyCode();
                String reply = reply(client);
                if (files == null || code >= 400) {
                    if (attempt < REMOTE_LIST_RETRY_ATTEMPTS && shouldRetryListReplyCode(code)) {
                        throw new IOException("Remote directory LIST temporary failure: FTP " + code + " " + reply);
                    }
                    throw new IOException("Remote directory LIST failed: FTP " + code + " " + reply);
                }
                Comparator<RemoteDirectoryEntry> order =
                        Comparator.comparing(RemoteDirectoryEntry::name, String.CASE_INSENSITIVE_ORDER);
                PriorityQueue<RemoteDirectoryEntry> topDirectories =
                        new PriorityQueue<>(safeLimit, order.reversed());
                int matchedCount = 0;
                for (FTPFile file : files) {
                    if (file == null || !file.isDirectory()) {
                        continue;
                    }
                    String name = file.getName();
                    if (name == null || name.isBlank() || ".".equals(name) || "..".equals(name)) {
                        continue;
                    }
                    matchedCount++;
                    RemoteDirectoryEntry entry = new RemoteDirectoryEntry(
                            name,
                            childRemotePath(remotePath, name),
                            modifiedAt(file)
                    );
                    if (topDirectories.size() < safeLimit) {
                        topDirectories.offer(entry);
                        continue;
                    }
                    RemoteDirectoryEntry worst = topDirectories.peek();
                    if (worst != null && order.compare(entry, worst) < 0) {
                        topDirectories.poll();
                        topDirectories.offer(entry);
                    }
                }
                List<RemoteDirectoryEntry> directories = new ArrayList<>(topDirectories);
                directories.sort(order);
                boolean truncated = matchedCount > safeLimit;
                RemoteDirectoryListing listing = new RemoteDirectoryListing(
                        remotePath,
                        parentRemotePath(remotePath),
                        directories,
                        code,
                        reply
                );
                if (useDirectoryCache) {
                    writeRemoteDirectoryCache(cacheKey, listing, safeLimit, truncated);
                    writePersistentRemoteDirectoryCache(task, listing, directoryModifiedAt, safeLimit, truncated);
                }
                keepClient = true;
                return listing;
            } catch (IOException ex) {
                failure = ex;
                if (attempt >= REMOTE_LIST_RETRY_ATTEMPTS || !shouldRetryListException(ex, client.getReplyCode())) {
                    throw ex;
                }
            } finally {
                if (keepClient) {
                    releaseRemoteClient(remoteClientKey, client);
                } else {
                    disconnectQuietly(client);
                }
            }
        }
        throw failure == null ? new IOException("Remote directory LIST failed.") : failure;
    }

    public RemoteFileListing listRemoteFiles(SyncTask task, String path, int limit) throws Exception {
        task = withStoredPassword(task);
        String remotePath = normalizeRemote(path == null || path.isBlank() ? task.remotePath() : path);
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        RemoteListingCacheKey cacheKey = RemoteListingCacheKey.of(task, remotePath);
        boolean useDirectoryCache = task.remoteDirectoryCacheEnabled();
        if (useDirectoryCache) {
            Optional<RemoteFileListing> cached = readCachedRemoteFiles(cacheKey, safeLimit);
            if (cached.isPresent()) {
                return cached.get();
            }
            Optional<RemoteFileListing> persistent = readPersistentRemoteFiles(task, remotePath, safeLimit);
            if (persistent.isPresent()) {
                return persistent.get();
            }
        }

        RemoteClientCacheKey remoteClientKey = RemoteClientCacheKey.of(task);
        IOException failure = null;
        for (int attempt = 1; attempt <= REMOTE_LIST_RETRY_ATTEMPTS; attempt++) {
            FTPClient client = borrowRemoteClient(task, remoteClientKey);
            boolean keepClient = false;
            try {
                FTPFile[] files = listRemoteEntries(client, remotePath);
                int code = client.getReplyCode();
                String reply = reply(client);
                if (files == null || code >= 400) {
                    if (attempt < REMOTE_LIST_RETRY_ATTEMPTS && shouldRetryListReplyCode(code)) {
                        throw new IOException("Remote file LIST temporary failure: FTP " + code + " " + reply);
                    }
                    throw new IOException("Remote file LIST failed: FTP " + code + " " + reply);
                }
                Comparator<RemoteFileCandidate> order = Comparator
                        .comparingInt((RemoteFileCandidate entry) -> remoteFileTypeRank(entry.type()))
                        .thenComparing(RemoteFileCandidate::name, String.CASE_INSENSITIVE_ORDER);
                PriorityQueue<RemoteFileCandidate> topEntries =
                        new PriorityQueue<>(safeLimit, order.reversed());
                int matchedCount = 0;
                for (FTPFile file : files) {
                    if (file == null) {
                        continue;
                    }
                    String name = file.getName();
                    if (name == null || name.isBlank() || ".".equals(name) || "..".equals(name)) {
                        continue;
                    }
                    matchedCount++;
                    String type = file.isDirectory() ? "DIRECTORY" : file.isFile() ? "FILE" : "OTHER";
                    RemoteFileCandidate candidate = new RemoteFileCandidate(
                            name,
                            childRemotePath(remotePath, name),
                            type,
                            file
                    );
                    if (topEntries.size() < safeLimit) {
                        topEntries.offer(candidate);
                        continue;
                    }
                    RemoteFileCandidate worst = topEntries.peek();
                    if (worst != null && order.compare(candidate, worst) < 0) {
                        topEntries.poll();
                        topEntries.offer(candidate);
                    }
                }
                List<RemoteFileCandidate> selected = new ArrayList<>(topEntries);
                selected.sort(order);
                List<RemoteFileEntry> entries = new ArrayList<>(selected.size());
                for (RemoteFileCandidate candidate : selected) {
                    entries.add(new RemoteFileEntry(
                            candidate.name(),
                            candidate.path(),
                            candidate.type(),
                            Math.max(0L, candidate.source().getSize()),
                            modifiedAt(candidate.source())
                    ));
                }
                boolean truncated = matchedCount > safeLimit;
                RemoteFileListing listing = new RemoteFileListing(
                        remotePath,
                        parentRemotePath(remotePath),
                        entries,
                        code,
                        reply,
                        false,
                        "",
                        "",
                        ""
                );
                if (useDirectoryCache) {
                    writeRemoteFileCache(cacheKey, listing, safeLimit, truncated);
                    writePersistentRemoteFileCache(task, listing, safeLimit, truncated);
                }
                keepClient = true;
                return listing;
            } catch (IOException ex) {
                failure = ex;
                if (attempt >= REMOTE_LIST_RETRY_ATTEMPTS || !shouldRetryListException(ex, client.getReplyCode())) {
                    throw ex;
                }
            } finally {
                if (keepClient) {
                    releaseRemoteClient(remoteClientKey, client);
                } else {
                    disconnectQuietly(client);
                }
            }
        }
        throw failure == null ? new IOException("Remote file LIST failed.") : failure;
    }

    public DirectoryCacheRefreshResult refreshRemoteDirectoryCache(SyncTask task,
                                                                   int maxDirectories,
                                                                   int entryLimit) throws Exception {
        task = withStoredPassword(task);
        if (!task.remoteDirectoryCacheEnabled()) {
            throw new IllegalStateException("Remote directory cache is disabled for this file source.");
        }
        int safeMaxDirectories = Math.max(1, Math.min(maxDirectories, 50_000));
        int safeEntryLimit = Math.max(1, Math.min(entryLimit, 5_000));
        String startedAt = DateTimes.nowDatabase();
        String rootPath = normalizeRemote(task.remotePath());
        long cachedPathCount = 0L;
        long fileCount = 0L;
        long directoryCount = 0L;
        long otherCount = 0L;
        long totalBytes = 0L;
        long truncatedPathCount = 0L;
        long errorCount = 0L;
        boolean cacheCleared = false;
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        pending.add(rootPath);
        RemoteClientCacheKey remoteClientKey = RemoteClientCacheKey.of(task);
        try {
            while (!pending.isEmpty() && seen.size() < safeMaxDirectories) {
                String currentPath = pending.removeFirst();
                if (!seen.add(currentPath)) {
                    continue;
                }
                try {
                    RemoteDirectoryScanListing scanListing = listRemoteEntriesForRefresh(task, remoteClientKey, currentPath);
                    List<RemoteFileEntry> entries = scanListing.entries();
                    for (RemoteFileEntry entry : entries) {
                        if ("DIRECTORY".equals(entry.type())) {
                            directoryCount++;
                            pending.add(entry.path());
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
                    List<RemoteFileEntry> visibleEntries = truncated
                            ? new ArrayList<>(entries.subList(0, safeEntryLimit))
                            : entries;
                    RemoteFileListing listing = new RemoteFileListing(
                            currentPath,
                            parentRemotePath(currentPath),
                            visibleEntries,
                            scanListing.replyCode(),
                            scanListing.replyText(),
                            false,
                            "",
                            "",
                            ""
                    );
                    writeRemoteFileCache(RemoteListingCacheKey.of(task, currentPath), listing,
                            safeEntryLimit, truncated);
                    if (!cacheCleared) {
                        remoteDirectoryCacheRepository.deleteForTask(task);
                        cacheCleared = true;
                    }
                    remoteDirectoryCacheRepository.saveFileListing(task, listing, safeEntryLimit, truncated);
                    cachedPathCount++;
                } catch (IOException ex) {
                    if (rootPath.equals(currentPath) && cachedPathCount == 0L) {
                        throw ex;
                    }
                    errorCount++;
                }
            }
            boolean maxDirectoriesReached = !pending.isEmpty();
            return new DirectoryCacheRefreshResult(
                    true,
                    task.taskId(),
                    "REMOTE_FTP",
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
        } finally {
            synchronized (remoteClientCacheLock) {
                CachedRemoteClient cached = remoteClientCache.remove(remoteClientKey);
                if (cached != null) {
                    disconnectQuietly(cached.client());
                }
            }
        }
    }

    private RemoteDirectoryScanListing listRemoteEntriesForRefresh(SyncTask task,
                                                                   RemoteClientCacheKey remoteClientKey,
                                                                   String remotePath) throws IOException {
        IOException failure = null;
        for (int attempt = 1; attempt <= REMOTE_LIST_RETRY_ATTEMPTS; attempt++) {
            FTPClient client = borrowRemoteClient(task, remoteClientKey);
            boolean keepClient = false;
            try {
                FTPFile[] files = listRemoteEntries(client, remotePath);
                int code = client.getReplyCode();
                String reply = reply(client);
                if (files == null || code >= 400) {
                    if (attempt < REMOTE_LIST_RETRY_ATTEMPTS && shouldRetryListReplyCode(code)) {
                        throw new IOException("Remote file LIST temporary failure: FTP " + code + " " + reply);
                    }
                    throw new IOException("Remote file LIST failed: FTP " + code + " " + reply);
                }
                List<RemoteFileEntry> entries = new ArrayList<>();
                for (FTPFile file : files) {
                    if (file == null) {
                        continue;
                    }
                    String name = file.getName();
                    if (name == null || name.isBlank() || ".".equals(name) || "..".equals(name)) {
                        continue;
                    }
                    String type = file.isDirectory() ? "DIRECTORY" : file.isFile() ? "FILE" : "OTHER";
                    long size = file.isFile() ? Math.max(0L, file.getSize()) : 0L;
                    entries.add(new RemoteFileEntry(
                            name,
                            childRemotePath(remotePath, name),
                            type,
                            size,
                            modifiedAt(file)
                    ));
                }
                entries.sort(Comparator
                        .comparingInt((RemoteFileEntry entry) -> remoteFileTypeRank(entry.type()))
                        .thenComparing(RemoteFileEntry::name, String.CASE_INSENSITIVE_ORDER));
                keepClient = true;
                return new RemoteDirectoryScanListing(entries, code, reply);
            } catch (IOException ex) {
                failure = ex;
                if (attempt >= REMOTE_LIST_RETRY_ATTEMPTS || !shouldRetryListException(ex, client.getReplyCode())) {
                    throw ex;
                }
            } finally {
                if (keepClient) {
                    releaseRemoteClient(remoteClientKey, client);
                } else {
                    disconnectQuietly(client);
                }
            }
        }
        throw failure == null ? new IOException("Remote file LIST failed.") : failure;
    }

    public FileSourcePermission checkRemotePermissions(SyncTask task) {
        FTPClient client = null;
        try {
            task = withStoredPassword(task);
            client = connectLoginAndPrepare(task);
            String remotePath = normalizeRemote(task.remotePath());
            List<String> messages = new ArrayList<>();

            boolean canRead = canListRemote(client, remotePath, messages);
            boolean canWrite = canStoreTempFile(client, remotePath, messages);
            if (canRead) {
                messages.add("read=ok");
            }
            if (canWrite) {
                messages.add("write=ok");
            }
            return FileSourcePermission.from(canRead, canWrite, String.join("; ", messages));
        } catch (Exception ex) {
            return FileSourcePermission.failed(safeMessage(ex));
        } finally {
            if (client != null) {
                disconnectQuietly(client);
            }
        }
    }

    public FTPClient openPreparedClient(SyncTask task) throws Exception {
        return connectLoginAndPrepare(withStoredPassword(task));
    }

    public static void closeQuietly(FTPClient client) {
        disconnectQuietly(client);
    }

    private SyncTask withStoredPassword(SyncTask task) throws SQLException {
        if (task.passwordRef() != null && !task.passwordRef().isBlank()) {
            return task;
        }
        if (task.taskId() == null || task.taskId().isBlank()) {
            return task;
        }
        Optional<SyncTask> stored = taskRepository.find(task.taskId());
        if (stored.isEmpty() || stored.get().passwordRef() == null || stored.get().passwordRef().isBlank()) {
            return task;
        }
        return new SyncTask(
                task.taskId(),
                task.taskName(),
                task.sourceType(),
                task.ftpHost(),
                task.ftpPort(),
                task.ftpUsername(),
                stored.get().passwordRef(),
                task.secureMode(),
                task.tlsFingerprint(),
                task.tlsFingerprintHash(),
                task.sourcePath(),
                task.remoteDirectoryCacheEnabled(),
                task.permission()
        );
    }

    private boolean canListRemote(FTPClient client, String remotePath, List<String> messages) {
        try {
            FTPFile[] files = listRemoteEntries(client, remotePath);
            int code = client.getReplyCode();
            if (files == null || code >= 400) {
                messages.add("read=failed: FTP " + code + " " + reply(client));
                return false;
            }
            return true;
        } catch (IOException ex) {
            messages.add("read=failed: " + safeMessage(ex));
            return false;
        }
    }

    private boolean canStoreTempFile(FTPClient client, String remotePath, List<String> messages) {
        String tempPath = childRemotePath(remotePath, ".fm-permission-" + UUID.randomUUID() + ".tmp");
        boolean stored = false;
        try (ByteArrayInputStream input = new ByteArrayInputStream(new byte[0])) {
            stored = client.storeFile(tempPath, input);
            int code = client.getReplyCode();
            if (!stored || code >= 400) {
                messages.add("write=failed: FTP " + code + " " + reply(client));
                return false;
            }
            return true;
        } catch (IOException ex) {
            messages.add("write=failed: " + safeMessage(ex));
            return false;
        } finally {
            if (stored) {
                try {
                    if (!client.deleteFile(tempPath)) {
                        messages.add("cleanup=failed: FTP " + client.getReplyCode() + " " + reply(client));
                    }
                } catch (IOException ex) {
                    messages.add("cleanup=failed: " + safeMessage(ex));
                }
            }
        }
    }

    public List<DiagnosticReport> listTaskDiagnostics(String taskId, int limit) throws SQLException {
        return diagnosticRepository.listByTask(taskId, limit);
    }

    public Optional<DiagnosticReport> find(long id) throws SQLException {
        return diagnosticRepository.find(id);
    }

    private DiagnosticReport run(SyncTask task) throws Exception {
        String startedAt = DateTimes.nowDatabase();
        long started = System.nanoTime();
        List<DiagnosticStep> steps = new ArrayList<>();

        runFtpChecks(task, steps);

        String finishedAt = DateTimes.nowDatabase();
        Long durationMs = elapsedMs(started);
        DiagnosticStep firstFailure = steps.stream()
                .filter(step -> !"SUCCESS".equals(step.state()))
                .findFirst()
                .orElse(null);
        String state = firstFailure == null ? "SUCCESS" : "FAILED";
        DiagnosticReport report = new DiagnosticReport(
                0,
                task.taskId(),
                "FTP_PREFLIGHT",
                state,
                startedAt,
                finishedAt,
                durationMs,
                firstFailure == null ? "" : firstFailure.errorCategory(),
                firstFailure == null ? "" : firstFailure.handlingAction(),
                firstFailure == null ? "HEALTHY" : firstFailure.healthImpact(),
                firstFailure == null ? "FTP preflight passed." : firstFailure.message(),
                "steps=" + steps.size(),
                steps
        );
        return diagnosticRepository.save(report);
    }

    private void runFtpChecks(SyncTask task, List<DiagnosticStep> steps) throws SQLException {
        FTPClient client = createClient(task);
        try {
            client.setDefaultTimeout((int) PREFLIGHT_TIMEOUT.toMillis());
            client.setConnectTimeout((int) PREFLIGHT_TIMEOUT.toMillis());
            client.setDataTimeout(PREFLIGHT_TIMEOUT);

            long connectStarted = System.nanoTime();
            try {
                client.connect(task.ftpHost(), task.ftpPort() <= 0 ? 21 : task.ftpPort());
                int code = client.getReplyCode();
                String reply = reply(client);
                if (!FTPReply.isPositiveCompletion(code)) {
                    String category = FtpErrorPolicyCatalog.classify(code, reply,
                            "FTP connect rejected by server", "FTP_CONNECT_FAILED");
                    steps.add(failure(steps.size() + 1, "FTP_CONNECT", category, retryable(category),
                            code, reply, task.remotePath(), task.localPath(), elapsedMs(connectStarted),
                            "FTP connect rejected by server.", hostDetails(task)));
                    return;
                }
                steps.add(success(steps.size() + 1, "FTP_CONNECT", task.remotePath(), task.localPath(),
                        code, reply, "FTP connected.", hostDetails(task), elapsedMs(connectStarted)));
            } catch (IOException ex) {
                String category = FtpErrorPolicyCatalog.classify(null, "", ex.getMessage(), "FTP_CONNECT_FAILED");
                steps.add(failure(steps.size() + 1, "FTP_CONNECT", category, retryable(category),
                        null, "", task.remotePath(), task.localPath(), elapsedMs(connectStarted),
                        safeMessage(ex), hostDetails(task)));
                return;
            }

            long loginStarted = System.nanoTime();
            try {
                if (!client.login(task.ftpUsername(), resolvePassword(task.passwordRef()))) {
                    int code = client.getReplyCode();
                    String reply = reply(client);
                    String category = FtpErrorPolicyCatalog.classify(code, reply, "FTP login failed", "FTP_LOGIN_FAILED");
                    steps.add(failure(steps.size() + 1, "FTP_LOGIN", category, retryable(category),
                            code, reply, task.remotePath(), task.localPath(), elapsedMs(loginStarted),
                            "FTP login failed.", "username=" + safeValue(task.ftpUsername())));
                    return;
                }
                steps.add(success(steps.size() + 1, "FTP_LOGIN", task.remotePath(), task.localPath(),
                        client.getReplyCode(), reply(client), "FTP login succeeded.",
                        "username=" + safeValue(task.ftpUsername()), elapsedMs(loginStarted)));
            } catch (IOException ex) {
                String category = FtpErrorPolicyCatalog.classify(null, "", ex.getMessage(), "FTP_LOGIN_FAILED");
                steps.add(failure(steps.size() + 1, "FTP_LOGIN", category, retryable(category),
                        null, "", task.remotePath(), task.localPath(), elapsedMs(loginStarted),
                        safeMessage(ex), "username=" + safeValue(task.ftpUsername())));
                return;
            }

            if (client instanceof FTPSClient ftpsClient) {
                long tlsStarted = System.nanoTime();
                try {
                    ftpsClient.execPBSZ(0);
                    ftpsClient.execPROT("P");
                    steps.add(success(steps.size() + 1, "FTP_TLS_PROTECTION", task.remotePath(), task.localPath(),
                            ftpsClient.getReplyCode(), reply(ftpsClient), "FTPS data protection enabled.",
                            "PROT=P", elapsedMs(tlsStarted)));
                } catch (IOException ex) {
                    String category = FtpErrorPolicyCatalog.classify(null, "", ex.getMessage(),
                            "FTP_TLS_PROTECTION_FAILED");
                    steps.add(failure(steps.size() + 1, "FTP_TLS_PROTECTION", category, retryable(category),
                            null, "", task.remotePath(), task.localPath(), elapsedMs(tlsStarted),
                            safeMessage(ex), "PROT=P"));
                    return;
                }
            }

            client.enterLocalPassiveMode();
            long prepareStarted = System.nanoTime();
            try {
                client.setFileType(FTP.BINARY_FILE_TYPE);
            } catch (IOException ex) {
                String category = FtpErrorPolicyCatalog.classify(null, "", ex.getMessage(),
                        "FTP_SESSION_PREPARE_FAILED");
                steps.add(failure(steps.size() + 1, "FTP_SESSION_PREPARE", category, retryable(category),
                        null, "", task.remotePath(), task.localPath(), elapsedMs(prepareStarted),
                        safeMessage(ex), "fileType=BINARY"));
                return;
            }
            tryEnableUtf8(client);
            checkRemoteList(task, steps, client);
        } finally {
            disconnectQuietly(client);
        }
    }

    private void checkRemoteList(SyncTask task, List<DiagnosticStep> steps, FTPClient client) throws SQLException {
        long listStarted = System.nanoTime();
        String remotePath = normalizeRemote(task.remotePath());
        try {
            FTPFile[] entries = listRemoteEntries(client, remotePath);
            int code = client.getReplyCode();
            String reply = reply(client);
            if (entries == null || code >= 400) {
                String category = FtpErrorPolicyCatalog.classify(code, reply, "LIST failed", "FTP_LIST_FAILED");
                steps.add(failure(steps.size() + 1, "FTP_LIST_REMOTE", category, retryable(category),
                        code, reply, remotePath, task.localPath(), elapsedMs(listStarted),
                        "Remote path LIST failed.", "entries=0"));
                return;
            }
            steps.add(success(steps.size() + 1, "FTP_LIST_REMOTE", remotePath, task.localPath(),
                    code, reply, "Remote path LIST succeeded.", "entries=" + entries.length,
                    elapsedMs(listStarted)));
        } catch (IOException ex) {
            String category = FtpErrorPolicyCatalog.classify(client.getReplyCode(), reply(client), ex.getMessage(),
                    "FTP_LIST_FAILED");
            steps.add(failure(steps.size() + 1, "FTP_LIST_REMOTE", category, retryable(category),
                    client.getReplyCode(), reply(client), remotePath, task.localPath(), elapsedMs(listStarted),
                    safeMessage(ex), ""));
        }
    }

    private DiagnosticStep success(int seq, String stepName, String remotePath, String localPath,
                                   Integer replyCode, String replyText, String message, String details,
                                   long durationMs) throws SQLException {
        return step(seq, stepName, "SUCCESS", "", false, replyCode, replyText, remotePath, localPath,
                durationMs, message, details);
    }

    private DiagnosticStep failure(int seq, String stepName, String category, boolean retryable,
                                   Integer replyCode, String replyText, String remotePath, String localPath,
                                   long durationMs, String message, String details) throws SQLException {
        return step(seq, stepName, "FAILED", category, retryable, replyCode, replyText, remotePath, localPath,
                durationMs, message, details);
    }

    private DiagnosticStep step(int seq, String stepName, String state, String category, boolean retryable,
                                Integer replyCode, String replyText, String remotePath, String localPath,
                                long durationMs, String message, String details) throws SQLException {
        FtpErrorPolicy policy = category == null || category.isBlank()
                ? FtpErrorPolicyCatalog.fallback("UNKNOWN_ERROR")
                : FtpErrorPolicyCatalog.fallback(category);
        return new DiagnosticStep(
                0,
                0,
                seq,
                stepName,
                state,
                value(category, ""),
                retryable,
                replyCode,
                value(replyText, ""),
                value(remotePath, ""),
                value(localPath, ""),
                durationMs,
                category == null || category.isBlank() ? "" : policy.handlingAction(),
                category == null || category.isBlank() ? "" : policy.healthImpact(),
                value(message, ""),
                value(details, "")
        );
    }

    private FTPClient createClient(SyncTask task) {
        String mode = task.secureMode() == null ? "NONE" : task.secureMode().trim().toUpperCase(Locale.ROOT);
        FTPClient client = switch (mode) {
            case "EXPLICIT", "FTPS_EXPLICIT" -> new FTPSClient("TLS", false);
            case "IMPLICIT", "FTPS_IMPLICIT" -> new FTPSClient("TLS", true);
            default -> new FTPClient();
        };
        if (client instanceof FTPSClient ftpsClient && task.tlsFingerprint() != null && !task.tlsFingerprint().isBlank()) {
            ftpsClient.setTrustManager(new PinningTrustManager(
                    task.tlsFingerprint(),
                    FingerprintHash.parse(task.tlsFingerprintHash())
            ));
        }
        client.setControlEncoding("UTF-8");
        client.setAutodetectUTF8(true);
        return client;
    }

    private void tryEnableUtf8(FTPClient client) {
        try {
            client.sendCommand("OPTS UTF8", "ON");
        } catch (IOException ignored) {
            // UTF-8 negotiation is useful but not required for a preflight pass.
        }
    }

    private FTPClient connectLoginAndPrepare(SyncTask task) throws IOException {
        FTPClient client = createClient(task);
        boolean ready = false;
        try {
            client.setDefaultTimeout((int) PREFLIGHT_TIMEOUT.toMillis());
            client.setConnectTimeout((int) PREFLIGHT_TIMEOUT.toMillis());
            client.setDataTimeout(PREFLIGHT_TIMEOUT);
            client.connect(task.ftpHost(), task.ftpPort() <= 0 ? 21 : task.ftpPort());
            int connectCode = client.getReplyCode();
            if (!FTPReply.isPositiveCompletion(connectCode)) {
                throw new IOException("FTP connect rejected: FTP " + connectCode + " " + reply(client));
            }
            if (!client.login(task.ftpUsername(), resolvePassword(task.passwordRef()))) {
                throw new IOException("FTP login failed: FTP " + client.getReplyCode() + " " + reply(client));
            }
            if (client instanceof FTPSClient ftpsClient) {
                ftpsClient.execPBSZ(0);
                ftpsClient.execPROT("P");
            }
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            tryEnableUtf8(client);
            ready = true;
            return client;
        } finally {
            if (!ready) {
                disconnectQuietly(client);
            }
        }
    }

    private FTPFile[] listRemoteEntries(FTPClient client, String remotePath) throws IOException {
        try {
            FTPFile[] machineEntries = client.mlistDir(remotePath);
            int code = client.getReplyCode();
            if (machineEntries != null && code < 400) {
                return machineEntries;
            }
            if (!isMlsdUnsupported(code)) {
                return machineEntries;
            }
        } catch (IOException ex) {
            if (!isMlsdUnsupported(client.getReplyCode())) {
                throw ex;
            }
        }
        return client.listFiles(remotePath);
    }

    private FTPFile[] listRemoteDirectoryEntries(FTPClient client, String remotePath) throws IOException {
        try {
            FTPFile[] machineEntries = client.mlistDir(remotePath);
            int code = client.getReplyCode();
            if (machineEntries != null && code < 400) {
                List<FTPFile> directories = new ArrayList<>();
                for (FTPFile entry : machineEntries) {
                    if (entry != null && entry.isDirectory()) {
                        directories.add(entry);
                    }
                }
                return directories.toArray(new FTPFile[0]);
            }
            if (!isMlsdUnsupported(code)) {
                return machineEntries;
            }
        } catch (IOException ex) {
            if (!isMlsdUnsupported(client.getReplyCode())) {
                throw ex;
            }
        }
        return client.listDirectories(remotePath);
    }

    private String readRemoteDirectoryModifiedAt(FTPClient client, String remotePath) {
        try {
            FTPFile file = client.mlistFile(remotePath);
            int code = client.getReplyCode();
            if (file == null || code >= 400) {
                return "";
            }
            return modifiedAt(file);
        } catch (IOException ex) {
            return "";
        }
    }

    private static boolean isMlsdUnsupported(int code) {
        return code == 500 || code == 501 || code == 502 || code == 504;
    }

    private FTPClient borrowRemoteClient(SyncTask task, RemoteClientCacheKey key) throws IOException {
        CachedRemoteClient cached = null;
        Instant now = Instant.now();
        synchronized (remoteClientCacheLock) {
            evictExpiredRemoteClients(now);
            cached = remoteClientCache.remove(key);
        }
        if (cached != null) {
            FTPClient reusable = cached.client();
            if (reusable != null && reusable.isConnected()) {
                return reusable;
            }
            disconnectQuietly(reusable);
        }
        return connectLoginAndPrepare(task);
    }

    private void releaseRemoteClient(RemoteClientCacheKey key, FTPClient client) {
        if (client == null) {
            return;
        }
        if (!client.isConnected()) {
            disconnectQuietly(client);
            return;
        }
        Instant now = Instant.now();
        synchronized (remoteClientCacheLock) {
            evictExpiredRemoteClients(now);
            remoteClientCache.put(key, new CachedRemoteClient(
                    client,
                    now.plus(REMOTE_CLIENT_CACHE_TTL)
            ));
        }
    }

    private void evictExpiredRemoteClients(Instant now) {
        var iterator = remoteClientCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RemoteClientCacheKey, CachedRemoteClient> entry = iterator.next();
            CachedRemoteClient cached = entry.getValue();
            if (cached.expiresAt().isAfter(now)) {
                continue;
            }
            iterator.remove();
            disconnectQuietly(cached.client());
        }
    }

    private static boolean shouldRetryListReplyCode(int code) {
        return code == 421 || code == 425 || code == 426 || code == 450 || code == 451 || code == 452;
    }

    private static boolean shouldRetryListException(IOException ex, int replyCode) {
        if (shouldRetryListReplyCode(replyCode)) {
            return true;
        }
        String text = safeMessage(ex).toUpperCase(Locale.ROOT);
        return text.contains("TIMED OUT")
                || text.contains("CONNECTION RESET")
                || text.contains("CONNECTION CLOSED")
                || text.contains("BROKEN PIPE")
                || text.contains("EOF");
    }

    private Optional<RemoteDirectoryListing> readCachedRemoteDirectories(RemoteListingCacheKey key, int requestedLimit) {
        synchronized (remoteDirectoryCacheLock) {
            CachedRemoteDirectoryListing cached = remoteDirectoryCache.get(key);
            if (cached == null) {
                return Optional.empty();
            }
            if (cached.expiresAt().isBefore(Instant.now())) {
                remoteDirectoryCache.remove(key);
                return Optional.empty();
            }
            if (requestedLimit > cached.limitUsed() && cached.truncated()) {
                return Optional.empty();
            }
            int effectiveLimit = Math.min(requestedLimit, cached.entries().size());
            List<RemoteDirectoryEntry> entries = new ArrayList<>(cached.entries().subList(0, effectiveLimit));
            return Optional.of(new RemoteDirectoryListing(
                    cached.path(),
                    cached.parentPath(),
                    entries,
                    cached.replyCode(),
                    cached.replyText()
            ));
        }
    }

    private Optional<RemoteDirectoryListing> readPersistentRemoteDirectories(SyncTask task,
                                                                            String remotePath,
                                                                            int requestedLimit,
                                                                            FTPClient client) {
        try {
            Optional<RemoteDirectoryCacheRepository.CachedDirectoryListing> cached =
                    remoteDirectoryCacheRepository.find(task, remotePath, requestedLimit);
            if (cached.isEmpty()) {
                return Optional.empty();
            }
            String remoteModifiedAt = readRemoteDirectoryModifiedAt(client, remotePath);
            if (remoteModifiedAt.isBlank() || !remoteModifiedAt.equals(cached.get().directoryModifiedAt())) {
                return Optional.empty();
            }
            RemoteDirectoryListing listing = new RemoteDirectoryListing(
                    cached.get().path(),
                    cached.get().parentPath(),
                    new ArrayList<>(cached.get().directories()),
                    cached.get().replyCode(),
                    cached.get().replyText()
            );
            writeRemoteDirectoryCache(RemoteListingCacheKey.of(task, remotePath), listing,
                    cached.get().limitUsed(), cached.get().truncated());
            return Optional.of(listing);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private void writePersistentRemoteDirectoryCache(SyncTask task,
                                                     RemoteDirectoryListing listing,
                                                     String directoryModifiedAt,
                                                     int limitUsed,
                                                     boolean truncated) {
        if (!task.remoteDirectoryCacheEnabled() || directoryModifiedAt == null || directoryModifiedAt.isBlank()) {
            return;
        }
        try {
            remoteDirectoryCacheRepository.save(task, listing, directoryModifiedAt, limitUsed, truncated);
        } catch (Exception ignored) {
            // The remote listing should still succeed even if the acceleration cache cannot be updated.
        }
    }

    private Optional<RemoteFileListing> readPersistentRemoteFiles(SyncTask task,
                                                                  String remotePath,
                                                                  int requestedLimit) {
        try {
            Optional<RemoteDirectoryCacheRepository.CachedFileListing> cached =
                    remoteDirectoryCacheRepository.findFileListing(task, remotePath, requestedLimit);
            if (cached.isEmpty()) {
                return Optional.empty();
            }
            RemoteFileListing listing = new RemoteFileListing(
                    cached.get().path(),
                    cached.get().parentPath(),
                    new ArrayList<>(cached.get().entries()),
                    cached.get().replyCode(),
                    cached.get().replyText(),
                    true,
                    "PERSISTENT",
                    "\u5f53\u524d\u76ee\u5f55\u6765\u81ea\u8fdc\u7aef\u76ee\u5f55\u7f13\u5b58\uff0c\u53ef\u80fd\u4e0d\u662f\u6700\u65b0\u3002",
                    cached.get().scannedAt()
            );
            writeRemoteFileCache(RemoteListingCacheKey.of(task, remotePath), listing,
                    cached.get().limitUsed(), cached.get().truncated());
            return Optional.of(listing);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private void writePersistentRemoteFileCache(SyncTask task,
                                                RemoteFileListing listing,
                                                int limitUsed,
                                                boolean truncated) {
        if (!task.remoteDirectoryCacheEnabled()) {
            return;
        }
        try {
            remoteDirectoryCacheRepository.saveFileListing(task, listing, limitUsed, truncated);
        } catch (Exception ignored) {
            // Browsing should not fail just because the optional acceleration cache cannot be updated.
        }
    }

    private void writeRemoteDirectoryCache(RemoteListingCacheKey key, RemoteDirectoryListing listing,
                                           int limitUsed, boolean truncated) {
        synchronized (remoteDirectoryCacheLock) {
            remoteDirectoryCache.put(key, new CachedRemoteDirectoryListing(
                    listing.path(),
                    listing.parentPath(),
                    List.copyOf(listing.directories()),
                    listing.replyCode(),
                    listing.replyText(),
                    limitUsed,
                    truncated,
                    Instant.now().plus(REMOTE_DIRECTORY_CACHE_TTL)
            ));
        }
    }

    private Optional<RemoteFileListing> readCachedRemoteFiles(RemoteListingCacheKey key, int requestedLimit) {
        synchronized (remoteFileCacheLock) {
            CachedRemoteFileListing cached = remoteFileCache.get(key);
            if (cached == null) {
                return Optional.empty();
            }
            if (cached.expiresAt().isBefore(Instant.now())) {
                remoteFileCache.remove(key);
                return Optional.empty();
            }
            if (requestedLimit > cached.limitUsed() && cached.truncated()) {
                return Optional.empty();
            }
            int effectiveLimit = Math.min(requestedLimit, cached.entries().size());
            List<RemoteFileEntry> entries = new ArrayList<>(cached.entries().subList(0, effectiveLimit));
            return Optional.of(new RemoteFileListing(
                    cached.path(),
                    cached.parentPath(),
                    entries,
                    cached.replyCode(),
                    cached.replyText(),
                    true,
                    "MEMORY",
                    "\u5f53\u524d\u76ee\u5f55\u6765\u81ea\u77ed\u671f\u5185\u5b58\u7f13\u5b58\uff0c\u53ef\u80fd\u4e0d\u662f\u6700\u65b0\u3002",
                    ""
            ));
        }
    }

    private void writeRemoteFileCache(RemoteListingCacheKey key, RemoteFileListing listing,
                                      int limitUsed, boolean truncated) {
        synchronized (remoteFileCacheLock) {
            remoteFileCache.put(key, new CachedRemoteFileListing(
                    listing.path(),
                    listing.parentPath(),
                    List.copyOf(listing.entries()),
                    listing.replyCode(),
                    listing.replyText(),
                    limitUsed,
                    truncated,
                    Instant.now().plus(REMOTE_FILE_LISTING_CACHE_TTL)
            ));
        }
    }

    private static boolean retryable(String category) {
        if (category == null) {
            return false;
        }
        return switch (category.trim().toUpperCase(Locale.ROOT)) {
            case "CONNECTION_ERROR", "DATA_CHANNEL_ERROR", "REMOTE_TEMPORARY_ERROR" -> true;
            default -> false;
        };
    }

    private static String resolvePassword(String passwordRef) {
        return PasswordRefs.resolve(passwordRef);
    }

    private static String normalizeRemote(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.replace('\\', '/').trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static String childRemotePath(String parent, String child) {
        String normalizedParent = normalizeRemote(parent);
        String safeChild = child == null ? "" : child.replace('\\', '/').trim();
        if ("/".equals(normalizedParent)) {
            return "/" + safeChild;
        }
        return normalizedParent.replaceAll("/+$", "") + "/" + safeChild;
    }

    private static String parentRemotePath(String path) {
        String normalized = normalizeRemote(path).replaceAll("/+$", "");
        if (normalized.isBlank() || "/".equals(normalized)) {
            return "";
        }
        int index = normalized.lastIndexOf('/');
        return index <= 0 ? "/" : normalized.substring(0, index);
    }

    private static String reply(FTPClient client) {
        String reply = client.getReplyString();
        return reply == null ? "" : reply.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String modifiedAt(FTPFile file) {
        Calendar timestamp = file.getTimestamp();
        return timestamp == null ? "" : Instant.ofEpochMilli(timestamp.getTimeInMillis()).toString();
    }

    private static void disconnectQuietly(FTPClient client) {
        try {
            if (client.isConnected()) {
                try {
                    client.logout();
                } finally {
                    client.disconnect();
                }
            }
        } catch (IOException ignored) {
            // Nothing useful to do during cleanup.
        }
    }

    private static String hostDetails(SyncTask task) {
        return "host=" + safeValue(task.ftpHost())
                + ", port=" + (task.ftpPort() <= 0 ? 21 : task.ftpPort())
                + ", secureMode=" + safeValue(task.secureMode());
    }

    private static String safeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String safeMessage(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int remoteFileTypeRank(String type) {
        return switch (type == null ? "" : type.toUpperCase(Locale.ROOT)) {
            case "DIRECTORY" -> 0;
            case "FILE" -> 1;
            default -> 2;
        };
    }

    private record RemoteFileCandidate(
            String name,
            String path,
            String type,
            FTPFile source
    ) {
    }

    private record RemoteDirectoryScanListing(
            List<RemoteFileEntry> entries,
            int replyCode,
            String replyText
    ) {
    }

    private record RemoteListingCacheKey(
            String sourceId,
            String host,
            int port,
            String username,
            String secureMode,
            String fingerprint,
            String fingerprintHash,
            String remotePath
    ) {
        private static RemoteListingCacheKey of(SyncTask task, String remotePath) {
            return new RemoteListingCacheKey(
                    value(task.taskId(), ""),
                    value(task.ftpHost(), ""),
                    task.ftpPort() <= 0 ? 21 : task.ftpPort(),
                    value(task.ftpUsername(), ""),
                    value(task.secureMode(), "NONE").toUpperCase(Locale.ROOT),
                    value(task.tlsFingerprint(), ""),
                    value(task.tlsFingerprintHash(), "SHA256").toUpperCase(Locale.ROOT),
                    value(remotePath, "/")
            );
        }
    }

    private record RemoteClientCacheKey(
            String sourceId,
            String host,
            int port,
            String username,
            String secureMode,
            String fingerprint,
            String fingerprintHash
    ) {
        private static RemoteClientCacheKey of(SyncTask task) {
            return new RemoteClientCacheKey(
                    value(task.taskId(), ""),
                    value(task.ftpHost(), ""),
                    task.ftpPort() <= 0 ? 21 : task.ftpPort(),
                    value(task.ftpUsername(), ""),
                    value(task.secureMode(), "NONE").toUpperCase(Locale.ROOT),
                    value(task.tlsFingerprint(), ""),
                    value(task.tlsFingerprintHash(), "SHA256").toUpperCase(Locale.ROOT)
            );
        }
    }

    private record CachedRemoteClient(
            FTPClient client,
            Instant expiresAt
    ) {
    }

    private record CachedRemoteDirectoryListing(
            String path,
            String parentPath,
            List<RemoteDirectoryEntry> entries,
            int replyCode,
            String replyText,
            int limitUsed,
            boolean truncated,
            Instant expiresAt
    ) {
    }

    private record CachedRemoteFileListing(
            String path,
            String parentPath,
            List<RemoteFileEntry> entries,
            int replyCode,
            String replyText,
            int limitUsed,
            boolean truncated,
            Instant expiresAt
    ) {
    }

    public record RemoteDirectoryEntry(String name, String path, String modifiedAt) {
    }

    public record RemoteDirectoryListing(
            String path,
            String parentPath,
            List<RemoteDirectoryEntry> directories,
            int replyCode,
            String replyText
    ) {
    }

    public record RemoteFileEntry(
            String name,
            String path,
            String type,
            long size,
            String modifiedAt
    ) {
    }

    public record RemoteFileListing(
            String path,
            String parentPath,
            List<RemoteFileEntry> entries,
            int replyCode,
            String replyText,
            boolean cacheUsed,
            String cacheSource,
            String cacheMessage,
            String cacheScannedAt
    ) {
    }

    public record DirectoryCacheRefreshResult(
            boolean ok,
            String taskId,
            String sourceType,
            long cachedPathCount,
            long fileCount,
            long directoryCount,
            long otherCount,
            long totalBytes,
            long truncatedPathCount,
            long errorCount,
            boolean maxDirectoriesReached,
            String startedAt,
            String finishedAt,
            String message
    ) {
    }
}

