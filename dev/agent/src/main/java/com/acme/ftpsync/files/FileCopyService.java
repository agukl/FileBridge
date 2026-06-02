package com.acme.ftpsync.files;

import com.acme.ftpsync.diagnostic.FtpPreflightService;
import com.acme.ftpsync.diagnostic.RemoteDirectoryCacheRepository;
import com.acme.ftpsync.event.FtpSyncEvent;
import com.acme.ftpsync.event.FtpSyncEventRepository;
import com.acme.ftpsync.run.SyncRun;
import com.acme.ftpsync.run.SyncRunSummary;
import com.acme.ftpsync.run.SyncRunRepository;
import com.acme.ftpsync.task.FileSourcePermission;
import com.acme.ftpsync.task.SyncTask;
import com.acme.ftpsync.task.TaskRepository;
import com.acme.ftpsync.util.DateTimes;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class FileCopyService implements AutoCloseable {
    private static final int COPY_BUFFER_BYTES = 128 * 1024;

    private final TaskRepository taskRepository;
    private final SyncRunRepository runRepository;
    private final FtpSyncEventRepository eventRepository;
    private final FtpPreflightService ftpPreflightService;
    private final RemoteDirectoryCacheRepository cacheRepository;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, copyThreadFactory());
    private final ConcurrentMap<Long, CopyControl> controls = new ConcurrentHashMap<>();

    public FileCopyService(TaskRepository taskRepository,
                            SyncRunRepository runRepository,
                            FtpSyncEventRepository eventRepository,
                            FtpPreflightService ftpPreflightService,
                            RemoteDirectoryCacheRepository cacheRepository) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.ftpPreflightService = ftpPreflightService;
        this.cacheRepository = cacheRepository;
    }

    @Override
    public void close() {
        controls.values().forEach(CopyControl::cancel);
        executor.shutdownNow();
    }

    public CopyResult copy(CopyRequest request) throws Exception {
        CopyRequest normalized = normalizeRequest(request);
        SyncTask source = taskRepository.find(normalized.sourceId())
                .orElseThrow(() -> new IllegalArgumentException("Source file source not found: " + normalized.sourceId()));
        SyncTask target = taskRepository.find(normalized.targetId())
                .orElseThrow(() -> new IllegalArgumentException("Target file source not found: " + normalized.targetId()));

        String targetDirectory = normalized.targetDirectory().isBlank()
                ? target.sourcePath()
                : normalized.targetDirectory();
        String sourceSummary = summarizePaths(normalized.sourcePaths());
        requireReadable(source);
        requireWritable(target);
        rejectRecursiveCopy(source, target, normalized.sourcePaths(), targetDirectory);

        long operationId = runRepository.create(
                source.taskId(),
                "FILE_COPY",
                sourceSummary,
                target.taskId(),
                targetDirectory,
                normalized.conflictPolicy()
        );

        CopyControl control = new CopyControl(operationId);
        controls.put(operationId, control);
        recordEvent(source, operationId, "INFO", "COPY", "COPY", "FILE_COPY_STARTED",
                sourceSummary, targetDirectory, null,
                "File copy started.",
                "targetSource=" + target.taskName() + ", policy=" + normalized.conflictPolicy());
        runRepository.updateProgress(operationId, new SyncRunSummary(
                "RUNNING", 0, 0, 0, 0, 0, 0, "", "File copy is running."
        ));
        executor.submit(() -> runCopy(normalized, source, target, targetDirectory, sourceSummary, operationId, control));
        return new CopyResult(true, operationId, 0, 0, 0, 0, 0,
                "RUNNING", "File copy is running.");
    }

    public CopyResult move(MoveRequest request) throws Exception {
        MoveRequest normalized = normalizeMoveRequest(request);
        SyncTask source = taskRepository.find(normalized.sourceId())
                .orElseThrow(() -> new IllegalArgumentException("File source not found: " + normalized.sourceId()));

        String targetDirectory = normalized.targetDirectory().isBlank()
                ? source.sourcePath()
                : normalized.targetDirectory();
        String sourceSummary = summarizePaths(normalized.sourcePaths());
        requireReadable(source);
        requireWritable(source);
        rejectRecursiveMove(source, normalized.sourcePaths(), targetDirectory);

        long operationId = runRepository.create(
                source.taskId(),
                "FILE_MOVE",
                sourceSummary,
                source.taskId(),
                targetDirectory,
                normalized.conflictPolicy()
        );

        CopyControl control = new CopyControl(operationId);
        controls.put(operationId, control);
        recordEvent(source, operationId, "INFO", "MOVE", "MOVE", "FILE_MOVE_STARTED",
                sourceSummary, targetDirectory, null,
                "File move started.",
                "policy=" + normalized.conflictPolicy());
        runRepository.updateProgress(operationId, new SyncRunSummary(
                "RUNNING", 0, 0, 0, 0, 0, 0, "", "File move is running."
        ));
        executor.submit(() -> runMove(normalized, source, targetDirectory, sourceSummary, operationId, control));
        return new CopyResult(true, operationId, 0, 0, 0, 0, 0,
                "RUNNING", "File move is running.");
    }

    public CopyResult copyCompared(ComparedCopyRequest request) throws Exception {
        ComparedCopyRequest normalized = normalizeComparedRequest(request);
        SyncTask source = taskRepository.find(normalized.sourceId())
                .orElseThrow(() -> new IllegalArgumentException("Source file source not found: " + normalized.sourceId()));
        SyncTask target = taskRepository.find(normalized.targetId())
                .orElseThrow(() -> new IllegalArgumentException("Target file source not found: " + normalized.targetId()));
        String targetDirectory = normalized.targetDirectory().isBlank()
                ? target.sourcePath()
                : normalized.targetDirectory();
        String sourceSummary = summarizePaths(normalized.sourcePaths());
        requireReadable(source);
        requireWritable(target);
        rejectRecursiveCopy(source, target, normalized.sourcePaths(), targetDirectory);

        long operationId = runRepository.create(
                source.taskId(),
                "DIRECTORY_COPY_TASK",
                sourceSummary,
                target.taskId(),
                targetDirectory,
                normalized.conflictPolicy()
        );
        CopyControl control = new CopyControl(operationId);
        controls.put(operationId, control);
        recordEvent(source, operationId, "INFO", "COPY", "DIRECTORY_COPY", "DIRECTORY_COPY_TASK_STARTED",
                sourceSummary, targetDirectory, null,
                "Directory copy task started.",
                "targetSource=" + target.taskName() + ", policy=" + normalized.conflictPolicy()
                        + ", compareMode=" + normalized.compareMode());
        runRepository.updateProgress(operationId, new SyncRunSummary(
                "RUNNING", 0, 0, 0, 0, 0, 0, "", "Directory copy task is running."
        ));
        executor.submit(() -> runComparedCopy(normalized, source, target, targetDirectory, sourceSummary, operationId, control));
        return new CopyResult(true, operationId, 0, 0, 0, 0, 0,
                "RUNNING", "Directory copy task is running.");
    }

    public CancelResult cancel(long operationId) throws SQLException {
        CopyControl control = controls.get(operationId);
        if (control != null) {
            control.cancel();
            return new CancelResult(true, operationId, "Cancel requested.");
        }

        Optional<SyncRun> run = runRepository.find(operationId);
        if (run.isEmpty()) {
            return new CancelResult(false, operationId, "File operation not found.");
        }
        SyncRun current = run.get();
        if (!"RUNNING".equalsIgnoreCase(current.state())) {
            return new CancelResult(false, operationId, "File operation is already " + current.state() + ".");
        }

        runRepository.markCancelled(operationId, new SyncRunSummary(
                "USER_CANCELLED",
                current.itemCount(),
                current.fileCount(),
                current.directoryCount(),
                current.totalBytes(),
                current.warningCount(),
                current.errorCount(),
                "USER_CANCELLED",
                "Running operation was not attached to this Agent process and has been marked cancelled."
        ));
        return new CancelResult(true, operationId, "Stale running operation marked cancelled.");
    }

    public List<Long> activeOperationIds() {
        return controls.keySet().stream().sorted().toList();
    }

    private void runCopy(CopyRequest normalized,
                         SyncTask source,
                         SyncTask target,
                         String targetDirectory,
                         String sourceSummary,
                         long operationId,
                         CopyControl control) {
        control.attachWorker(Thread.currentThread());
        CopyStats stats = new CopyStats();
        try {
            control.throwIfCancelled();
            try (FileEndpoint sourceEndpoint = openSourceEndpoint(source);
                 FileEndpoint targetEndpoint = openEndpoint(target)) {
                control.addCancelHook(sourceEndpoint);
                control.addCancelHook(targetEndpoint);
                targetEndpoint.ensureDirectory(targetDirectory);
                for (String sourcePath : normalized.sourcePaths()) {
                    control.throwIfCancelled();
                    try {
                        copyEntry(sourceEndpoint, targetEndpoint, sourcePath, null, targetDirectory,
                                normalized.conflictPolicy(), operationId, source, stats, control);
                    } catch (CopyCancelledException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        recordCopyItemFailure(source, operationId, sourcePath, targetDirectory, stats, ex);
                    }
                }
            }

            runRepository.markSuccess(operationId, new SyncRunSummary(
                    finalCopyHealth(stats),
                    stats.itemCount(),
                    stats.fileCount,
                    stats.directoryCount,
                    stats.totalBytes,
                    stats.warningCount(),
                    stats.errorCount,
                    "",
                    ""
            ));
            recordEvent(source, operationId, "INFO", "DONE", "COPY", "FILE_COPY_FINISHED",
                    sourceSummary, targetDirectory, null,
                    "File copy finished.",
                    "files=" + stats.fileCount + ", directories=" + stats.directoryCount
                            + ", skipped=" + stats.skippedCount + ", errors=" + stats.errorCount
                            + ", bytes=" + stats.totalBytes);
        } catch (CopyCancelledException ex) {
            try {
                recordEvent(source, operationId, "WARN", "CANCELLED", "COPY", "FILE_COPY_CANCELLED",
                        sourceSummary, targetDirectory, null, "File copy cancelled.", "");
                runRepository.markCancelled(operationId, new SyncRunSummary(
                        "USER_CANCELLED",
                        stats.itemCount(),
                        stats.fileCount,
                        stats.directoryCount,
                        stats.totalBytes,
                        stats.warningCount(),
                        stats.errorCount,
                        "USER_CANCELLED",
                        "File copy cancelled by user."
                ));
            } catch (SQLException sqlEx) {
                // The caller can still see the operation as RUNNING if the database is unavailable.
            }
        } catch (Exception ex) {
            if (control.isCancelled()) {
                try {
                    recordEvent(source, operationId, "WARN", "CANCELLED", "COPY", "FILE_COPY_CANCELLED",
                            sourceSummary, targetDirectory, null, "File copy cancelled.", "");
                    runRepository.markCancelled(operationId, new SyncRunSummary(
                            "USER_CANCELLED",
                            stats.itemCount(),
                            stats.fileCount,
                            stats.directoryCount,
                            stats.totalBytes,
                            stats.warningCount(),
                            stats.errorCount,
                            "USER_CANCELLED",
                            "File copy cancelled by user."
                    ));
                } catch (SQLException ignored) {
                    // The operation row already captures the latest progress available.
                }
                return;
            }
            stats.errorCount++;
            String message = safeMessage(ex);
            try {
                recordEvent(source, operationId, "ERROR", "FAILED", "COPY", "FILE_COPY_FAILED",
                        sourceSummary, targetDirectory, null, message, "");
                runRepository.markFailure(operationId, new SyncRunSummary(
                        "FAILED",
                        stats.itemCount(),
                        stats.fileCount,
                        stats.directoryCount,
                        stats.totalBytes,
                        stats.warningCount(),
                        stats.errorCount,
                        "FILE_OPERATION_ERROR",
                        message
                ));
            } catch (SQLException sqlEx) {
                // Keep the worker alive; the original copy failure is already represented in logs.
            }
        } finally {
            control.detachWorker(Thread.currentThread());
            controls.remove(operationId);
        }
    }

    private void runMove(MoveRequest normalized,
                         SyncTask source,
                         String targetDirectory,
                         String sourceSummary,
                         long operationId,
                         CopyControl control) {
        control.attachWorker(Thread.currentThread());
        CopyStats stats = new CopyStats();
        try {
            control.throwIfCancelled();
            try (FileEndpoint endpoint = openEndpoint(source)) {
                control.addCancelHook(endpoint);
                endpoint.ensureDirectory(targetDirectory);
                for (String sourcePath : normalized.sourcePaths()) {
                    control.throwIfCancelled();
                    try {
                        moveEntry(endpoint, sourcePath, targetDirectory, normalized.conflictPolicy(),
                                operationId, source, stats, control);
                    } catch (CopyCancelledException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        recordMoveItemFailure(source, operationId, sourcePath, targetDirectory, stats, ex);
                    }
                }
            }

            runRepository.markSuccess(operationId, new SyncRunSummary(
                    finalCopyHealth(stats),
                    stats.itemCount(),
                    stats.fileCount,
                    stats.directoryCount,
                    stats.totalBytes,
                    stats.warningCount(),
                    stats.errorCount,
                    "",
                    ""
            ));
            recordEvent(source, operationId, "INFO", "DONE", "MOVE", "FILE_MOVE_FINISHED",
                    sourceSummary, targetDirectory, null,
                    "File move finished.",
                    "files=" + stats.fileCount + ", directories=" + stats.directoryCount
                            + ", skipped=" + stats.skippedCount + ", errors=" + stats.errorCount
                            + ", bytes=" + stats.totalBytes);
        } catch (CopyCancelledException ex) {
            try {
                recordEvent(source, operationId, "WARN", "CANCELLED", "MOVE", "FILE_MOVE_CANCELLED",
                        sourceSummary, targetDirectory, null, "File move cancelled.", "");
                runRepository.markCancelled(operationId, new SyncRunSummary(
                        "USER_CANCELLED",
                        stats.itemCount(),
                        stats.fileCount,
                        stats.directoryCount,
                        stats.totalBytes,
                        stats.warningCount(),
                        stats.errorCount,
                        "USER_CANCELLED",
                        "File move cancelled by user."
                ));
            } catch (SQLException sqlEx) {
                // The caller can still see the operation as RUNNING if the database is unavailable.
            }
        } catch (Exception ex) {
            if (control.isCancelled()) {
                try {
                    recordEvent(source, operationId, "WARN", "CANCELLED", "MOVE", "FILE_MOVE_CANCELLED",
                            sourceSummary, targetDirectory, null, "File move cancelled.", "");
                    runRepository.markCancelled(operationId, new SyncRunSummary(
                            "USER_CANCELLED",
                            stats.itemCount(),
                            stats.fileCount,
                            stats.directoryCount,
                            stats.totalBytes,
                            stats.warningCount(),
                            stats.errorCount,
                            "USER_CANCELLED",
                            "File move cancelled by user."
                    ));
                } catch (SQLException ignored) {
                    // The operation row already captures the latest progress available.
                }
                return;
            }
            stats.errorCount++;
            String message = safeMessage(ex);
            try {
                recordEvent(source, operationId, "ERROR", "FAILED", "MOVE", "FILE_MOVE_FAILED",
                        sourceSummary, targetDirectory, null, message, "");
                runRepository.markFailure(operationId, new SyncRunSummary(
                        "FAILED",
                        stats.itemCount(),
                        stats.fileCount,
                        stats.directoryCount,
                        stats.totalBytes,
                        stats.warningCount(),
                        stats.errorCount,
                        "FILE_OPERATION_ERROR",
                        message
                ));
            } catch (SQLException sqlEx) {
                // Keep the worker alive; the original move failure is already represented in logs.
            }
        } finally {
            control.detachWorker(Thread.currentThread());
            controls.remove(operationId);
        }
    }

    private void runComparedCopy(ComparedCopyRequest request,
                                 SyncTask source,
                                 SyncTask target,
                                 String targetDirectory,
                                 String sourceSummary,
                                 long operationId,
                                 CopyControl control) {
        control.attachWorker(Thread.currentThread());
        CopyStats stats = new CopyStats();
        try {
            control.throwIfCancelled();
            try (FileEndpoint sourceEndpoint = openSourceEndpoint(source);
                 FileEndpoint targetSnapshotEndpoint = openSourceEndpoint(target);
                 FileEndpoint targetEndpoint = openEndpoint(target)) {
                control.addCancelHook(sourceEndpoint);
                control.addCancelHook(targetSnapshotEndpoint);
                control.addCancelHook(targetEndpoint);
                recordEvent(source, operationId, "INFO", "COMPARE", "DIRECTORY_COPY",
                        "DIRECTORY_COMPARE_STARTED", sourceSummary, targetDirectory, null,
                        "Directory compare started.", "mode=" + request.compareMode());
                CompareResult compare = compareDirectories(
                        sourceEndpoint,
                        targetSnapshotEndpoint,
                        request.sourcePaths(),
                        targetDirectory,
                        request.conflictPolicy(),
                        request.mtimeToleranceSeconds()
                );
                stats.skippedCount += compare.skipCount();
                recordEvent(source, operationId, "INFO", "COMPARE", "DIRECTORY_COPY",
                        "DIRECTORY_COMPARE_FINISHED", sourceSummary, targetDirectory, null,
                        "Directory compare finished.",
                        "directoriesToCreate=" + compare.directoriesToCreate().size()
                                + ", filesToCopy=" + compare.filesToCopy().size()
                                + ", skipped=" + compare.skipCount()
                                + ", conflicts=" + compare.conflicts().size());
                recordProgress(operationId, stats);

                targetEndpoint.ensureDirectory(targetDirectory);
                for (SnapshotEntry directory : compare.directoriesToCreate()) {
                    control.throwIfCancelled();
                    String targetPath = targetEndpoint.childPath(targetDirectory, directory.relativePath());
                    try {
                        targetEndpoint.ensureDirectory(targetPath);
                        stats.directoryCount++;
                        recordEvent(source, operationId, "INFO", "COPY", "MKDIR", "DIRECTORY_CREATED",
                                directory.sourcePath(), targetPath, null,
                                "Directory prepared.", "relativePath=" + directory.relativePath());
                    } catch (Exception ex) {
                        recordCopyItemFailure(source, operationId, directory.sourcePath(), targetPath, stats, ex);
                    }
                    recordProgress(operationId, stats);
                }

                for (CopyCandidate candidate : compare.filesToCopy()) {
                    control.throwIfCancelled();
                    String targetPath = targetEndpoint.childPath(targetDirectory, candidate.source().relativePath());
                    recordEvent(source, operationId, "INFO", "COPY", "COPY", "FILE_COPYING",
                            candidate.source().sourcePath(), targetPath, candidate.source().size(),
                            "Copying file.", "action=" + candidate.action()
                                    + ", relativePath=" + candidate.source().relativePath());
                    try (InputStream input = sourceEndpoint.openRead(candidate.source().sourcePath())) {
                        boolean copied = targetEndpoint.writeFile(targetPath, input, request.conflictPolicy(), control);
                        if (copied) {
                            stats.fileCount++;
                            stats.totalBytes += candidate.source().size();
                            recordEvent(source, operationId, "INFO", "COPY", "COPY", "FILE_COPIED",
                                    candidate.source().sourcePath(), targetPath, candidate.source().size(),
                                    "File copied.", "action=" + candidate.action()
                                            + ", relativePath=" + candidate.source().relativePath());
                        } else {
                            stats.skippedCount++;
                            recordEvent(source, operationId, "INFO", "COPY", "COPY", "FILE_SKIPPED",
                                    candidate.source().sourcePath(), targetPath, candidate.source().size(),
                                    "File skipped because target already exists.",
                                    "policy=SKIP, relativePath=" + candidate.source().relativePath());
                        }
                    } catch (CopyCancelledException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        recordCopyItemFailure(source, operationId, candidate.source().sourcePath(), targetPath, stats, ex);
                    }
                    recordProgress(operationId, stats);
                }

                for (SnapshotEntry conflict : compare.conflicts()) {
                    stats.errorCount++;
                    String targetPath = targetEndpoint.childPath(targetDirectory, conflict.relativePath());
                    recordEvent(source, operationId, "ERROR", "COMPARE", "DIRECTORY_COPY", "TYPE_CONFLICT",
                            conflict.sourcePath(), targetPath, conflict.size(),
                            "Source and target item types conflict.",
                            "relativePath=" + conflict.relativePath());
                    recordProgress(operationId, stats);
                }
            }

            runRepository.markSuccess(operationId, new SyncRunSummary(
                    finalCopyHealth(stats),
                    stats.itemCount(),
                    stats.fileCount,
                    stats.directoryCount,
                    stats.totalBytes,
                    stats.warningCount(),
                    stats.errorCount,
                    "",
                    ""
            ));
            recordEvent(source, operationId, "INFO", "DONE", "DIRECTORY_COPY", "DIRECTORY_COPY_TASK_FINISHED",
                    sourceSummary, targetDirectory, null,
                    "Directory copy task finished.",
                    "files=" + stats.fileCount + ", directories=" + stats.directoryCount
                            + ", skipped=" + stats.skippedCount + ", errors=" + stats.errorCount
                            + ", bytes=" + stats.totalBytes);
        } catch (CopyCancelledException ex) {
            try {
                recordEvent(source, operationId, "WARN", "CANCELLED", "DIRECTORY_COPY",
                        "DIRECTORY_COPY_TASK_CANCELLED", sourceSummary, targetDirectory, null,
                        "Directory copy task cancelled.", "");
                runRepository.markCancelled(operationId, new SyncRunSummary(
                        "USER_CANCELLED",
                        stats.itemCount(),
                        stats.fileCount,
                        stats.directoryCount,
                        stats.totalBytes,
                        stats.warningCount(),
                        stats.errorCount,
                        "USER_CANCELLED",
                        "Directory copy task cancelled by user."
                ));
            } catch (SQLException ignored) {
                // The operation row already captures the latest progress available.
            }
        } catch (Exception ex) {
            if (control.isCancelled()) {
                try {
                    recordEvent(source, operationId, "WARN", "CANCELLED", "DIRECTORY_COPY",
                            "DIRECTORY_COPY_TASK_CANCELLED", sourceSummary, targetDirectory, null,
                            "Directory copy task cancelled.", "");
                    runRepository.markCancelled(operationId, new SyncRunSummary(
                            "USER_CANCELLED",
                            stats.itemCount(),
                            stats.fileCount,
                            stats.directoryCount,
                            stats.totalBytes,
                            stats.warningCount(),
                            stats.errorCount,
                            "USER_CANCELLED",
                            "Directory copy task cancelled by user."
                    ));
                } catch (SQLException ignored) {
                    // The operation row already captures the latest progress available.
                }
                return;
            }
            stats.errorCount++;
            String message = safeMessage(ex);
            try {
                recordEvent(source, operationId, "ERROR", "FAILED", "DIRECTORY_COPY",
                        "DIRECTORY_COPY_TASK_FAILED", sourceSummary, targetDirectory, null, message, "");
                runRepository.markFailure(operationId, new SyncRunSummary(
                        "FAILED",
                        stats.itemCount(),
                        stats.fileCount,
                        stats.directoryCount,
                        stats.totalBytes,
                        stats.warningCount(),
                        stats.errorCount,
                        "FAILED",
                        message
                ));
            } catch (SQLException ignored) {
                // Keep the worker alive; the original copy failure is already represented in logs.
            }
        } finally {
            control.detachWorker(Thread.currentThread());
            controls.remove(operationId);
        }
    }

    private void copyEntry(FileEndpoint sourceEndpoint,
                           FileEndpoint targetEndpoint,
                           String sourcePath,
                           FileNode knownSourceNode,
                           String targetDirectory,
                           String conflictPolicy,
                           long operationId,
                           SyncTask source,
                           CopyStats stats,
                           CopyControl control) throws Exception {
        control.throwIfCancelled();
        FileNode sourceNode = knownSourceNode == null ? sourceEndpoint.stat(sourcePath) : knownSourceNode;
        String targetPath = targetEndpoint.childPath(targetDirectory, sourceNode.name());
        if ("DIRECTORY".equals(sourceNode.type())) {
            targetEndpoint.ensureDirectory(targetPath);
            stats.directoryCount++;
            recordEvent(source, operationId, "INFO", "COPY", "MKDIR", "DIRECTORY_CREATED",
                    sourceNode.path(), targetPath, null,
                    "Directory prepared.", "name=" + sourceNode.name());
            recordProgress(operationId, stats);
            for (FileNode child : sourceEndpoint.list(sourceNode.path())) {
                control.throwIfCancelled();
                try {
                    copyEntry(sourceEndpoint, targetEndpoint, child.path(), child, targetPath, conflictPolicy,
                            operationId, source, stats, control);
                } catch (CopyCancelledException ex) {
                    throw ex;
                } catch (Exception ex) {
                    recordCopyItemFailure(source, operationId, child.path(), targetPath, stats, ex);
                }
            }
            return;
        }
        if (!"FILE".equals(sourceNode.type())) {
            stats.skippedCount++;
            stats.warningCount++;
            recordEvent(source, operationId, "WARN", "COPY", "COPY", "FILE_SKIPPED",
                    sourceNode.path(), targetPath, null,
                    "Unsupported file type skipped.", "type=" + sourceNode.type());
            recordProgress(operationId, stats);
            return;
        }

        recordEvent(source, operationId, "INFO", "COPY", "COPY", "FILE_COPYING",
                sourceNode.path(), targetPath, sourceNode.size(),
                "Copying file.", "bytes=" + sourceNode.size());
        try (InputStream input = sourceEndpoint.openRead(sourceNode.path())) {
            boolean copied = targetEndpoint.writeFile(targetPath, input, conflictPolicy, control);
            if (copied) {
                stats.fileCount++;
                stats.totalBytes += sourceNode.size();
                recordEvent(source, operationId, "INFO", "COPY", "COPY", "FILE_COPIED",
                        sourceNode.path(), targetPath, sourceNode.size(),
                        "File copied.", "bytes=" + sourceNode.size());
            } else {
                stats.skippedCount++;
                stats.warningCount++;
                recordEvent(source, operationId, "WARN", "COPY", "COPY", "FILE_SKIPPED",
                        sourceNode.path(), targetPath, sourceNode.size(),
                        "File skipped because target already exists.", "policy=SKIP");
            }
            recordProgress(operationId, stats);
        }
    }

    private void moveEntry(FileEndpoint endpoint,
                           String sourcePath,
                           String targetDirectory,
                           String conflictPolicy,
                           long operationId,
                           SyncTask source,
                           CopyStats stats,
                           CopyControl control) throws Exception {
        control.throwIfCancelled();
        FileNode sourceNode = endpoint.stat(sourcePath);
        String targetPath = endpoint.childPath(targetDirectory, sourceNode.name());
        if (!"DIRECTORY".equals(sourceNode.type()) && !"FILE".equals(sourceNode.type())) {
            stats.skippedCount++;
            stats.warningCount++;
            recordEvent(source, operationId, "WARN", "MOVE", "MOVE", "FILE_MOVE_SKIPPED",
                    sourceNode.path(), targetPath, null,
                    "Unsupported file type skipped.", "type=" + sourceNode.type());
            recordProgress(operationId, stats);
            return;
        }

        boolean moved = endpoint.moveEntry(sourceNode.path(), targetPath, conflictPolicy, control);
        if (moved) {
            if ("DIRECTORY".equals(sourceNode.type())) {
                stats.directoryCount++;
            } else {
                stats.fileCount++;
                stats.totalBytes += sourceNode.size();
            }
            recordEvent(source, operationId, "INFO", "MOVE", "MOVE", "FILE_MOVED",
                    sourceNode.path(), targetPath, "FILE".equals(sourceNode.type()) ? sourceNode.size() : null,
                    "File moved.", "type=" + sourceNode.type());
        } else {
            stats.skippedCount++;
            stats.warningCount++;
            recordEvent(source, operationId, "WARN", "MOVE", "MOVE", "FILE_MOVE_SKIPPED",
                    sourceNode.path(), targetPath, "FILE".equals(sourceNode.type()) ? sourceNode.size() : null,
                    "File skipped because target already exists.", "policy=SKIP");
        }
        recordProgress(operationId, stats);
    }

    private void recordCopyItemFailure(SyncTask source,
                                        long operationId,
                                        String sourcePath,
                                        String targetPath,
                                        CopyStats stats,
                                       Exception ex) throws SQLException {
        stats.errorCount++;
        recordEvent(source, operationId, "ERROR", "COPY", "COPY", "FILE_COPY_ITEM_FAILED",
                sourcePath, targetPath, null, safeMessage(ex), "");
        recordProgress(operationId, stats);
    }

    private void recordMoveItemFailure(SyncTask source,
                                       long operationId,
                                       String sourcePath,
                                       String targetPath,
                                       CopyStats stats,
                                       Exception ex) throws SQLException {
        stats.errorCount++;
        recordEvent(source, operationId, "ERROR", "MOVE", "MOVE", "FILE_MOVE_ITEM_FAILED",
                sourcePath, targetPath, null, safeMessage(ex), "");
        recordProgress(operationId, stats);
    }

    private CompareResult compareDirectories(FileEndpoint sourceEndpoint,
                                             FileEndpoint targetEndpoint,
                                             List<String> sourcePaths,
                                             String targetDirectory,
                                             String conflictPolicy,
                                             int mtimeToleranceSeconds) throws Exception {
        List<SnapshotEntry> sourceEntries = new ArrayList<>();
        for (String sourcePath : sourcePaths) {
            collectSourceSnapshot(sourceEndpoint, sourcePath, sourceEntries);
        }
        Map<String, SnapshotEntry> targetEntries = collectTargetSnapshot(targetEndpoint, targetDirectory);
        List<SnapshotEntry> directoriesToCreate = new ArrayList<>();
        List<CopyCandidate> filesToCopy = new ArrayList<>();
        List<SnapshotEntry> conflicts = new ArrayList<>();
        long skipCount = 0L;
        for (SnapshotEntry sourceEntry : sourceEntries) {
            SnapshotEntry targetEntry = targetEntries.get(sourceEntry.relativePath());
            if ("DIRECTORY".equals(sourceEntry.type())) {
                if (targetEntry == null) {
                    directoriesToCreate.add(sourceEntry);
                } else if (!"DIRECTORY".equals(targetEntry.type())) {
                    conflicts.add(sourceEntry);
                }
                continue;
            }
            if (!"FILE".equals(sourceEntry.type())) {
                skipCount++;
                continue;
            }
            if (targetEntry == null) {
                filesToCopy.add(new CopyCandidate(sourceEntry, "COPY_NEW"));
                continue;
            }
            if (!"FILE".equals(targetEntry.type())) {
                conflicts.add(sourceEntry);
                continue;
            }
            if (sameFile(sourceEntry, targetEntry, mtimeToleranceSeconds)) {
                skipCount++;
                continue;
            }
            if ("OVERWRITE".equals(conflictPolicy)) {
                filesToCopy.add(new CopyCandidate(sourceEntry, "COPY_CHANGED"));
            } else if ("FAIL".equals(conflictPolicy)) {
                conflicts.add(sourceEntry);
            } else {
                skipCount++;
            }
        }
        directoriesToCreate.sort(Comparator.comparingInt(entry -> pathDepth(entry.relativePath())));
        return new CompareResult(directoriesToCreate, filesToCopy, conflicts, skipCount);
    }

    private void collectSourceSnapshot(FileEndpoint endpoint, String sourcePath, List<SnapshotEntry> entries)
            throws Exception {
        FileNode rootNode = endpoint.stat(sourcePath);
        if ("DIRECTORY".equals(rootNode.type())) {
            // Compared directory tasks sync the contents of the source directory.
            for (FileNode child : endpoint.list(rootNode.path())) {
                collectSnapshotNode(endpoint, child, normalizeRelativePath(child.name()), entries);
            }
            return;
        }
        String rootRelative = normalizeRelativePath(rootNode.name());
        if (rootRelative.isBlank()) {
            rootRelative = normalizeRelativePath(lastPathSegment(rootNode.path()));
        }
        collectSnapshotNode(endpoint, rootNode, rootRelative, entries);
    }

    private Map<String, SnapshotEntry> collectTargetSnapshot(FileEndpoint endpoint, String targetDirectory)
            throws Exception {
        Map<String, SnapshotEntry> entries = new HashMap<>();
        FileNode targetRoot;
        try {
            targetRoot = endpoint.stat(targetDirectory);
        } catch (Exception ex) {
            return entries;
        }
        if (!"DIRECTORY".equals(targetRoot.type())) {
            return entries;
        }
        for (FileNode child : endpoint.list(targetRoot.path())) {
            collectSnapshotNode(endpoint, child, normalizeRelativePath(child.name()), entries);
        }
        return entries;
    }

    private void collectSnapshotNode(FileEndpoint endpoint,
                                     FileNode node,
                                     String relativePath,
                                     List<SnapshotEntry> entries) throws Exception {
        String normalizedRelative = normalizeRelativePath(relativePath);
        if (normalizedRelative.isBlank()) {
            return;
        }
        entries.add(SnapshotEntry.from(node, normalizedRelative));
        if (!"DIRECTORY".equals(node.type())) {
            return;
        }
        for (FileNode child : endpoint.list(node.path())) {
            collectSnapshotNode(endpoint, child, childRelativePath(normalizedRelative, child.name()), entries);
        }
    }

    private void collectSnapshotNode(FileEndpoint endpoint,
                                     FileNode node,
                                     String relativePath,
                                     Map<String, SnapshotEntry> entries) throws Exception {
        String normalizedRelative = normalizeRelativePath(relativePath);
        if (normalizedRelative.isBlank()) {
            return;
        }
        SnapshotEntry entry = SnapshotEntry.from(node, normalizedRelative);
        entries.put(entry.relativePath(), entry);
        if (!"DIRECTORY".equals(node.type())) {
            return;
        }
        for (FileNode child : endpoint.list(node.path())) {
            collectSnapshotNode(endpoint, child, childRelativePath(normalizedRelative, child.name()), entries);
        }
    }

    private static boolean sameFile(SnapshotEntry source, SnapshotEntry target, int toleranceSeconds) {
        if (source.size() != target.size()) {
            return false;
        }
        Instant sourceModified = parseInstant(source.modifiedAt());
        Instant targetModified = parseInstant(target.modifiedAt());
        if (sourceModified == null || targetModified == null) {
            return true;
        }
        long toleranceMillis = Math.max(0, toleranceSeconds) * 1000L;
        long delta = Math.abs(sourceModified.toEpochMilli() - targetModified.toEpochMilli());
        return delta <= toleranceMillis;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String childRelativePath(String parent, String child) {
        String normalizedParent = normalizeRelativePath(parent);
        String normalizedChild = normalizeRelativePath(child);
        return normalizedParent.isBlank() ? normalizedChild : normalizedParent + "/" + normalizedChild;
    }

    private static String normalizeRelativePath(String path) {
        String value = path == null ? "" : path.replace('\\', '/').trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        value = value.replaceAll("/+", "/").replaceAll("/+$", "");
        if (value.equals(".") || value.contains("../") || value.startsWith("..") || value.contains("/..")) {
            throw new IllegalArgumentException("Unsafe relative path: " + path);
        }
        return value;
    }

    private static String lastPathSegment(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/').replaceAll("/+$", "");
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private static int pathDepth(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        return normalized.isBlank() ? 0 : normalized.split("/").length;
    }

    private FileEndpoint openSourceEndpoint(SyncTask source) throws Exception {
        FileEndpoint liveEndpoint = openEndpoint(source);
        if (!isLocalSource(source) && source.remoteDirectoryCacheEnabled()) {
            return new CachedSourceEndpoint(source, liveEndpoint, cacheRepository);
        }
        return liveEndpoint;
    }

    private FileEndpoint openEndpoint(SyncTask source) throws Exception {
        if (isLocalSource(source)) {
            return new LocalEndpoint(source);
        }
        return new FtpEndpoint(source, ftpPreflightService.openPreparedClient(source));
    }

    private void rejectRecursiveCopy(SyncTask source, SyncTask target, List<String> sourcePaths, String targetDirectory) {
        if (isLocalSource(source) && isLocalSource(target)) {
            for (String sourcePath : sourcePaths) {
                Path sourceTarget = resolveLocalWithinRoot(source, sourcePath);
                Path targetDir = resolveLocalWithinRoot(target, targetDirectory);
                Path sourceName = sourceTarget.getFileName();
                Path plannedTarget = sourceName == null ? targetDir : targetDir.resolve(sourceName).normalize();
                if (targetDir.equals(sourceTarget) || targetDir.startsWith(sourceTarget)
                        || plannedTarget.equals(sourceTarget) || plannedTarget.startsWith(sourceTarget)) {
                    throw new IllegalArgumentException("Cannot copy a directory into itself: " + sourcePath);
                }
            }
            return;
        }
        if (!sameRemoteEndpoint(source, target)) {
            return;
        }
        for (String sourcePath : sourcePaths) {
            String sourceTarget = normalizeRemote(sourcePath);
            String targetDir = normalizeRemote(targetDirectory);
            String plannedTarget = childRemotePath(targetDir, lastRemoteSegment(sourceTarget));
            if (targetDir.equals(sourceTarget) || targetDir.startsWith(sourceTarget + "/")
                    || plannedTarget.equals(sourceTarget) || plannedTarget.startsWith(sourceTarget + "/")) {
                throw new IllegalArgumentException("Cannot copy a directory into itself: " + sourcePath);
            }
        }
    }

    private void rejectRecursiveMove(SyncTask source, List<String> sourcePaths, String targetDirectory) {
        if (isLocalSource(source)) {
            Path targetDir = resolveLocalWithinRoot(source, targetDirectory);
            for (String sourcePath : sourcePaths) {
                Path sourceTarget = resolveLocalWithinRoot(source, sourcePath);
                if (targetDir.equals(sourceTarget) || targetDir.startsWith(sourceTarget)) {
                    throw new IllegalArgumentException("Cannot move a directory into itself: " + sourcePath);
                }
            }
            return;
        }
        String targetDir = normalizeRemote(targetDirectory);
        for (String sourcePath : sourcePaths) {
            String sourceTarget = normalizeRemote(sourcePath);
            if (targetDir.equals(sourceTarget) || targetDir.startsWith(sourceTarget + "/")) {
                throw new IllegalArgumentException("Cannot move a directory into itself: " + sourcePath);
            }
        }
    }

    private static Path resolveLocalWithinRoot(SyncTask source, String path) {
        Path root = LocalPathSupport.toAbsolutePath(source.sourcePath());
        Path target = LocalPathSupport.resolveWithinRoot(root, path);
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside file source root: " + target);
        }
        return target;
    }

    private static boolean sameRemoteEndpoint(SyncTask source, SyncTask target) {
        if (isLocalSource(source) || isLocalSource(target)) {
            return false;
        }
        return source.ftpPort() == target.ftpPort()
                && value(source.ftpHost(), "").equalsIgnoreCase(value(target.ftpHost(), ""))
                && value(source.ftpUsername(), "").equalsIgnoreCase(value(target.ftpUsername(), ""))
                && value(source.secureMode(), "").equalsIgnoreCase(value(target.secureMode(), ""));
    }

    private void requireReadable(SyncTask source) {
        FileSourcePermission permission = source.permission();
        if (permission == null || !permission.canRead()) {
            throw new IllegalStateException("Source file source is not readable: " + source.taskName());
        }
    }

    private void requireWritable(SyncTask target) {
        FileSourcePermission permission = target.permission();
        if (permission == null || !permission.canWrite()) {
            throw new IllegalStateException("Target file source is not writable: " + target.taskName());
        }
    }

    private CopyRequest normalizeRequest(CopyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Copy request is empty.");
        }
        String sourceId = required(request.sourceId(), "sourceId");
        String targetId = required(request.targetId(), "targetId");
        List<String> paths = request.sourcePaths() == null ? List.of() : request.sourcePaths().stream()
                .filter(path -> path != null && !path.isBlank())
                .map(String::trim)
                .toList();
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("sourcePaths must contain at least one path.");
        }
        String policy = normalizeConflictPolicy(request.conflictPolicy());
        return new CopyRequest(sourceId, paths, targetId, value(request.targetDirectory(), ""), policy);
    }

    private MoveRequest normalizeMoveRequest(MoveRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Move request is empty.");
        }
        String sourceId = required(request.sourceId(), "sourceId");
        List<String> paths = request.sourcePaths() == null ? List.of() : request.sourcePaths().stream()
                .filter(path -> path != null && !path.isBlank())
                .map(String::trim)
                .toList();
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("sourcePaths must contain at least one path.");
        }
        String policy = normalizeConflictPolicy(request.conflictPolicy());
        return new MoveRequest(sourceId, paths, value(request.targetDirectory(), ""), policy);
    }

    private ComparedCopyRequest normalizeComparedRequest(ComparedCopyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Directory copy request is empty.");
        }
        String sourceId = required(request.sourceId(), "sourceId");
        String targetId = required(request.targetId(), "targetId");
        List<String> paths = request.sourcePaths() == null ? List.of() : request.sourcePaths().stream()
                .filter(path -> path != null && !path.isBlank())
                .map(String::trim)
                .toList();
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("sourcePaths must contain at least one path.");
        }
        String compareMode = normalizeCompareMode(request.compareMode());
        String policy = normalizeConflictPolicy(request.conflictPolicy());
        int tolerance = Math.max(0, Math.min(request.mtimeToleranceSeconds() <= 0 ? 5 : request.mtimeToleranceSeconds(), 3600));
        return new ComparedCopyRequest(sourceId, paths, targetId, value(request.targetDirectory(), ""),
                compareMode, tolerance, policy);
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

    private void recordEvent(SyncTask source, long operationId, String level, String phase, String operationName,
                             String eventType, String sourcePath, String itemPath, Long itemSize,
                             String message, String details) throws SQLException {
        eventRepository.write(new FtpSyncEvent(
                0,
                source.taskId(),
                operationId,
                eventRepository.nextSeq(operationId),
                DateTimes.nowDatabase(),
                level,
                phase,
                operationName,
                eventType,
                "",
                false,
                null,
                "",
                value(sourcePath, ""),
                value(itemPath, ""),
                "",
                itemSize,
                null,
                "",
                "",
                value(message, ""),
                value(details, "")
        ));
    }

    private void recordProgress(long operationId, CopyStats stats) throws SQLException {
        runRepository.updateProgress(operationId, new SyncRunSummary(
                "RUNNING",
                stats.itemCount(),
                stats.fileCount,
                stats.directoryCount,
                stats.totalBytes,
                stats.warningCount(),
                stats.errorCount,
                "",
                "File copy is running."
        ));
    }

    private static String finalCopyHealth(CopyStats stats) {
        if (stats.errorCount > 0) {
            return "COMPLETED_WITH_ERRORS";
        }
        if (stats.warningCount() > 0) {
            return "COMPLETED_WITH_WARNINGS";
        }
        return "HEALTHY";
    }

    private static String summarizePaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return "";
        }
        if (paths.size() == 1) {
            return paths.get(0);
        }
        return paths.get(0) + " +" + (paths.size() - 1);
    }

    private static boolean isLocalSource(SyncTask source) {
        String sourceType = source.sourceType() == null ? "REMOTE_FTP" : source.sourceType().trim().toUpperCase(Locale.ROOT);
        return "LOCAL".equals(sourceType) || "SMB".equals(sourceType);
    }

    private static String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required copy field: " + fieldName);
        }
        return value.trim();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String safeMessage(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
    }

    private static String modifiedAt(FTPFile file) {
        if (file == null || file.getTimestamp() == null) {
            return "";
        }
        try {
            return file.getTimestamp().toInstant().toString();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private interface FileEndpoint extends AutoCloseable {
        FileNode stat(String path) throws Exception;

        List<FileNode> list(String path) throws Exception;

        InputStream openRead(String path) throws Exception;

        void ensureDirectory(String path) throws Exception;

        boolean writeFile(String path, InputStream input, String conflictPolicy, CopyControl control) throws Exception;

        boolean moveEntry(String sourcePath, String targetPath, String conflictPolicy, CopyControl control) throws Exception;

        String childPath(String parent, String child);

        @Override
        void close() throws Exception;
    }

    private static final class LocalEndpoint implements FileEndpoint {
        private final SyncTask source;
        private final Path root;
        private final SmbCredentialSession smbSession;

        private LocalEndpoint(SyncTask source) throws Exception {
            this.source = source;
            SmbCredentialSession session = SmbCredentialSession.open(source);
            try {
                Path resolvedRoot = LocalPathSupport.toAbsolutePath(source.sourcePath());
                if (!Files.exists(resolvedRoot, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(LocalPathSupport.missingPathMessage(source.sourcePath(), resolvedRoot));
                }
                if (!Files.isDirectory(resolvedRoot, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(LocalPathSupport.notDirectoryMessage(source.sourcePath(), resolvedRoot));
                }
                this.root = resolvedRoot;
                this.smbSession = session;
            } catch (Exception ex) {
                session.close();
                throw ex;
            }
        }

        @Override
        public FileNode stat(String path) throws IOException {
            Path target = resolve(path);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Local path does not exist: " + target);
            }
            boolean directory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
            boolean file = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
            return new FileNode(
                    fileName(target),
                    target.toString(),
                    directory ? "DIRECTORY" : file ? "FILE" : "OTHER",
                    file ? Math.max(0L, Files.size(target)) : 0L,
                    Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS).toInstant().toString()
            );
        }

        @Override
        public List<FileNode> list(String path) throws IOException {
            Path target = resolve(path);
            List<FileNode> result = new ArrayList<>();
            try (var stream = Files.list(target)) {
                for (Path child : stream.toList()) {
                    result.add(stat(child.toString()));
                }
            }
            return result;
        }

        @Override
        public InputStream openRead(String path) throws IOException {
            return Files.newInputStream(resolve(path));
        }

        @Override
        public void ensureDirectory(String path) throws IOException {
            Files.createDirectories(resolve(path));
        }

        @Override
        public boolean writeFile(String path, InputStream input, String conflictPolicy, CopyControl control) throws IOException {
            Path target = resolve(path);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if ("SKIP".equals(conflictPolicy)) {
                    return false;
                }
                if ("FAIL".equals(conflictPolicy)) {
                    throw new IOException("Target file already exists: " + target);
                }
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream output = Files.newOutputStream(
                    target,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                copyStream(input, output, control);
            }
            return true;
        }

        @Override
        public boolean moveEntry(String sourcePath, String targetPath, String conflictPolicy, CopyControl control)
                throws IOException {
            control.throwIfCancelled();
            Path sourceTarget = resolve(sourcePath);
            Path target = resolve(targetPath);
            if (sourceTarget.equals(target)) {
                return false;
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if ("SKIP".equals(conflictPolicy)) {
                    return false;
                }
                if ("FAIL".equals(conflictPolicy)) {
                    throw new IOException("Target file already exists: " + target);
                }
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if ("OVERWRITE".equals(conflictPolicy)) {
                Files.move(sourceTarget, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(sourceTarget, target);
            }
            return true;
        }

        @Override
        public String childPath(String parent, String child) {
            return resolve(parent).resolve(child == null ? "" : child).normalize().toString();
        }

        @Override
        public void close() {
            smbSession.close();
        }

        private Path resolve(String path) {
            Path target = LocalPathSupport.resolveWithinRoot(root, path);
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("Path is outside file source root: " + target);
            }
            return target;
        }

        private String fileName(Path path) {
            if (path.equals(root)) {
                return source.taskName() == null || source.taskName().isBlank()
                        ? path.toString()
                        : source.taskName();
            }
            Path name = path.getFileName();
            return name == null ? path.toString() : name.toString();
        }

    }

    private static final class CachedSourceEndpoint implements FileEndpoint {
        private final SyncTask source;
        private final FileEndpoint liveEndpoint;
        private final RemoteDirectoryCacheRepository cacheRepository;
        private final String root;
        private final Map<String, List<FileNode>> cachedListings = new HashMap<>();

        private CachedSourceEndpoint(SyncTask source,
                                     FileEndpoint liveEndpoint,
                                     RemoteDirectoryCacheRepository cacheRepository) {
            this.source = source;
            this.liveEndpoint = liveEndpoint;
            this.cacheRepository = cacheRepository;
            this.root = normalizeRemote(source.sourcePath());
        }

        @Override
        public FileNode stat(String path) throws Exception {
            String target = validate(path);
            if (target.equals(root)) {
                return new FileNode(rootName(), target, "DIRECTORY", 0L, "");
            }
            String parent = parentRemotePath(target);
            String name = lastRemoteSegment(target);
            for (FileNode node : cachedList(parent)) {
                if (target.equals(normalizeRemote(node.path())) || name.equals(node.name())) {
                    return node;
                }
            }
            throw new IOException("Cached remote path does not exist: " + target);
        }

        @Override
        public List<FileNode> list(String path) throws Exception {
            return cachedList(validate(path));
        }

        @Override
        public InputStream openRead(String path) throws Exception {
            return liveEndpoint.openRead(validate(path));
        }

        @Override
        public void ensureDirectory(String path) {
            throw new UnsupportedOperationException("Cached source endpoint is read-only.");
        }

        @Override
        public boolean writeFile(String path, InputStream input, String conflictPolicy, CopyControl control) {
            throw new UnsupportedOperationException("Cached source endpoint is read-only.");
        }

        @Override
        public boolean moveEntry(String sourcePath, String targetPath, String conflictPolicy, CopyControl control) {
            throw new UnsupportedOperationException("Cached source endpoint is read-only.");
        }

        @Override
        public String childPath(String parent, String child) {
            return liveEndpoint.childPath(parent, child);
        }

        @Override
        public void close() throws Exception {
            liveEndpoint.close();
        }

        private List<FileNode> cachedList(String path) throws Exception {
            String target = validate(path);
            List<FileNode> cached = cachedListings.get(target);
            if (cached != null) {
                return cached;
            }
            Optional<RemoteDirectoryCacheRepository.CachedFileListing> listing =
                    cacheRepository.findFileListingSnapshot(source, target);
            if (listing.isEmpty()) {
                throw new IOException("Cached remote directory listing not found: " + target);
            }
            List<FileNode> nodes = listing.get().entries().stream()
                    .map(entry -> new FileNode(
                            value(entry.name(), lastRemoteSegment(entry.path())),
                            normalizeRemote(entry.path()),
                            value(entry.type(), "OTHER").toUpperCase(Locale.ROOT),
                            Math.max(0L, entry.size()),
                            value(entry.modifiedAt(), "")
                    ))
                    .toList();
            cachedListings.put(target, nodes);
            return nodes;
        }

        private String validate(String path) {
            String target = normalizeRemote(path);
            if (!isSameOrChildRemotePath(root, target)) {
                throw new IllegalArgumentException("Path is outside file source root: " + target);
            }
            return target;
        }

        private String rootName() {
            String name = value(source.taskName(), "");
            return name.isBlank() ? lastRemoteSegment(root) : name;
        }
    }

    private static final class FtpEndpoint implements FileEndpoint {
        private final SyncTask source;
        private final FTPClient client;
        private final String root;
        private final Set<String> ensuredDirectories = new HashSet<>();

        private FtpEndpoint(SyncTask source, FTPClient client) {
            this.source = source;
            this.client = client;
            this.root = normalizeRemote(source.sourcePath());
        }

        @Override
        public FileNode stat(String path) throws IOException {
            String target = validate(path);
            if (target.equals(root)) {
                return new FileNode(source.taskName(), target, "DIRECTORY", 0L, "");
            }
            String parent = parentRemotePath(target);
            String name = lastRemoteSegment(target);
            FTPFile[] files = client.listFiles(parent);
            int code = client.getReplyCode();
            if (files == null || code >= 400) {
                throw new IOException("Remote stat failed: FTP " + code + " " + reply(client));
            }
            for (FTPFile file : files) {
                if (file != null && name.equals(file.getName())) {
                    String type = file.isDirectory() ? "DIRECTORY" : file.isFile() ? "FILE" : "OTHER";
                    return new FileNode(name, target, type,
                            file.isFile() ? Math.max(0L, file.getSize()) : 0L,
                            modifiedAt(file));
                }
            }
            throw new IOException("Remote path does not exist: " + target);
        }

        @Override
        public List<FileNode> list(String path) throws IOException {
            String target = validate(path);
            FTPFile[] files = client.listFiles(target);
            int code = client.getReplyCode();
            if (files == null || code >= 400) {
                throw new IOException("Remote list failed: FTP " + code + " " + reply(client));
            }
            List<FileNode> result = new ArrayList<>();
            for (FTPFile file : files) {
                if (file == null || file.getName() == null || file.getName().isBlank()
                        || ".".equals(file.getName()) || "..".equals(file.getName())) {
                    continue;
                }
                String type = file.isDirectory() ? "DIRECTORY" : file.isFile() ? "FILE" : "OTHER";
                result.add(new FileNode(
                        file.getName(),
                        childRemotePath(target, file.getName()),
                        type,
                        file.isFile() ? Math.max(0L, file.getSize()) : 0L,
                        modifiedAt(file)
                ));
            }
            return result;
        }

        @Override
        public InputStream openRead(String path) throws IOException {
            String target = validate(path);
            InputStream raw = client.retrieveFileStream(target);
            int code = client.getReplyCode();
            if (raw == null || code >= 400) {
                throw new IOException("Remote read failed: FTP " + code + " " + reply(client));
            }
            return new FilterInputStream(raw) {
                private boolean closed;

                @Override
                public void close() throws IOException {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    super.close();
                    if (!client.completePendingCommand()) {
                        throw new IOException("Remote read did not complete: FTP "
                                + client.getReplyCode() + " " + reply(client));
                    }
                }
            };
        }

        @Override
        public void ensureDirectory(String path) throws IOException {
            String target = validate(path);
            if ("/".equals(target)) {
                return;
            }
            if (ensuredDirectories.contains(target)) {
                return;
            }
            String current = "";
            for (String part : target.split("/")) {
                if (part.isBlank()) {
                    continue;
                }
                current = current.isBlank() ? "/" + part : current + "/" + part;
                if (client.changeWorkingDirectory(current)) {
                    ensuredDirectories.add(current);
                    continue;
                }
                client.makeDirectory(current);
                if (!client.changeWorkingDirectory(current)) {
                    throw new IOException("Remote directory is not writable: FTP "
                            + client.getReplyCode() + " " + reply(client));
                }
                ensuredDirectories.add(current);
            }
        }

        @Override
        public boolean writeFile(String path, InputStream input, String conflictPolicy, CopyControl control) throws IOException {
            String target = validate(path);
            if (!"OVERWRITE".equals(conflictPolicy) && exists(target)) {
                if ("SKIP".equals(conflictPolicy)) {
                    return false;
                }
                if ("FAIL".equals(conflictPolicy)) {
                    throw new IOException("Target file already exists: " + target);
                }
            }
            ensureDirectory(parentRemotePath(target));
            try (OutputStream output = client.storeFileStream(target)) {
                int streamCode = client.getReplyCode();
                if (output == null || streamCode >= 400) {
                    throw new IOException("Remote write failed: FTP " + streamCode + " " + reply(client));
                }
                copyStream(input, output, control);
            }
            boolean stored = client.completePendingCommand();
            int code = client.getReplyCode();
            if (!stored || code >= 400) {
                throw new IOException("Remote write failed: FTP " + code + " " + reply(client));
            }
            return true;
        }

        @Override
        public boolean moveEntry(String sourcePath, String targetPath, String conflictPolicy, CopyControl control)
                throws IOException {
            control.throwIfCancelled();
            String sourceTarget = validate(sourcePath);
            String target = validate(targetPath);
            if (sourceTarget.equals(target)) {
                return false;
            }
            if (exists(target)) {
                if ("SKIP".equals(conflictPolicy)) {
                    return false;
                }
                if ("FAIL".equals(conflictPolicy)) {
                    throw new IOException("Target file already exists: " + target);
                }
                FileNode targetNode = stat(target);
                if ("DIRECTORY".equals(targetNode.type())) {
                    throw new IOException("Cannot overwrite target directory: " + target);
                }
                if (!client.deleteFile(target)) {
                    throw new IOException("Remote overwrite delete failed: FTP "
                            + client.getReplyCode() + " " + reply(client));
                }
            }
            ensureDirectory(parentRemotePath(target));
            boolean renamed = client.rename(sourceTarget, target);
            int code = client.getReplyCode();
            if (!renamed || code >= 400) {
                throw new IOException("Remote move failed: FTP " + code + " " + reply(client));
            }
            return true;
        }

        @Override
        public String childPath(String parent, String child) {
            return validate(childRemotePath(parent, child));
        }

        @Override
        public void close() {
            FtpPreflightService.closeQuietly(client);
        }

        private boolean exists(String path) {
            try {
                stat(path);
                return true;
            } catch (IOException ex) {
                return false;
            }
        }

        private String validate(String path) {
            String target = normalizeRemote(path);
            if (!isSameOrChildRemotePath(root, target)) {
                throw new IllegalArgumentException("Path is outside file source root: " + target);
            }
            return target;
        }
    }

    private record SnapshotEntry(String relativePath, String sourcePath, String type, long size, String modifiedAt) {
        private static SnapshotEntry from(FileNode node, String relativePath) {
            return new SnapshotEntry(
                    normalizeRelativePath(relativePath),
                    node.path(),
                    value(node.type(), "OTHER").toUpperCase(Locale.ROOT),
                    Math.max(0L, node.size()),
                    value(node.modifiedAt(), "")
            );
        }
    }

    private record CopyCandidate(SnapshotEntry source, String action) {
    }

    private record CompareResult(
            List<SnapshotEntry> directoriesToCreate,
            List<CopyCandidate> filesToCopy,
            List<SnapshotEntry> conflicts,
            long skipCount
    ) {
    }

    private record FileNode(String name, String path, String type, long size, String modifiedAt) {
    }

    private static final class CopyStats {
        private long fileCount;
        private long directoryCount;
        private long skippedCount;
        private long totalBytes;
        private long errorCount;
        private long warningCount;

        private long itemCount() {
            return fileCount + directoryCount + skippedCount + errorCount;
        }

        private long warningCount() {
            return warningCount;
        }
    }

    public record CopyRequest(
            String sourceId,
            List<String> sourcePaths,
            String targetId,
            String targetDirectory,
            String conflictPolicy
    ) {
    }

    public record MoveRequest(
            String sourceId,
            List<String> sourcePaths,
            String targetDirectory,
            String conflictPolicy
    ) {
    }

    public record ComparedCopyRequest(
            String sourceId,
            List<String> sourcePaths,
            String targetId,
            String targetDirectory,
            String compareMode,
            int mtimeToleranceSeconds,
            String conflictPolicy
    ) {
    }

    public record CopyResult(
            boolean ok,
            long operationId,
            long itemCount,
            long fileCount,
            long directoryCount,
            long skippedCount,
            long totalBytes,
            String state,
            String message
    ) {
    }

    public record CancelResult(
            boolean ok,
            long operationId,
            String message
    ) {
    }

    private static ThreadFactory copyThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "ftp-file-copy-" + System.nanoTime());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void copyStream(InputStream input, OutputStream output, CopyControl control) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        while (true) {
            control.throwIfCancelled();
            int read;
            try {
                read = input.read(buffer);
            } catch (IOException ex) {
                control.throwIfCancelled();
                throw ex;
            }
            if (read < 0) {
                return;
            }
            try {
                output.write(buffer, 0, read);
            } catch (IOException ex) {
                control.throwIfCancelled();
                throw ex;
            }
        }
    }

    private static final class CopyControl {
        private final long operationId;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Thread> worker = new AtomicReference<>();
        private final Set<AutoCloseable> cancelHooks = ConcurrentHashMap.newKeySet();

        private CopyControl(long operationId) {
            this.operationId = operationId;
        }

        private void attachWorker(Thread thread) {
            worker.set(thread);
            if (cancelled.get()) {
                thread.interrupt();
            }
        }

        private void detachWorker(Thread thread) {
            worker.compareAndSet(thread, null);
            if (cancelled.get()) {
                Thread.interrupted();
            }
        }

        private void addCancelHook(AutoCloseable hook) {
            if (hook == null) {
                return;
            }
            cancelHooks.add(hook);
            if (cancelled.get()) {
                closeQuietly(hook);
            }
        }

        private void cancel() {
            cancelled.set(true);
            Thread thread = worker.get();
            if (thread != null) {
                thread.interrupt();
            }
            for (AutoCloseable hook : cancelHooks) {
                closeQuietly(hook);
            }
        }

        private boolean isCancelled() {
            return cancelled.get();
        }

        private void throwIfCancelled() {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new CopyCancelledException(operationId);
            }
        }

        private static void closeQuietly(AutoCloseable hook) {
            try {
                hook.close();
            } catch (Exception ignored) {
                // Cancellation is best effort; the worker will record the final operation state.
            }
        }
    }

    private static final class CopyCancelledException extends RuntimeException {
        private CopyCancelledException(long operationId) {
            super("File copy cancelled: " + operationId);
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

    private static String childRemotePath(String parent, String child) {
        String normalizedParent = normalizeRemote(parent);
        String safeChild = child == null ? "" : child.replace('\\', '/').trim();
        if ("/".equals(normalizedParent)) {
            return "/" + safeChild;
        }
        return normalizedParent + "/" + safeChild;
    }

    private static String parentRemotePath(String path) {
        String normalized = normalizeRemote(path);
        if ("/".equals(normalized)) {
            return "/";
        }
        int index = normalized.lastIndexOf('/');
        return index <= 0 ? "/" : normalized.substring(0, index);
    }

    private static String lastRemoteSegment(String path) {
        String normalized = normalizeRemote(path);
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private static boolean isSameOrChildRemotePath(String root, String path) {
        String normalizedRoot = normalizeRemote(root);
        String normalizedPath = normalizeRemote(path);
        if ("/".equals(normalizedRoot)) {
            return true;
        }
        return normalizedPath.equals(normalizedRoot) || normalizedPath.startsWith(normalizedRoot + "/");
    }

    private static String reply(FTPClient client) {
        String reply = client.getReplyString();
        return reply == null ? "" : reply.replace('\r', ' ').replace('\n', ' ').trim();
    }
}

