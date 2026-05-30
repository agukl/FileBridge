package com.acme.ftpsync.files;

import com.acme.ftpsync.run.SyncRunRepository;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DirectoryCopyScheduler implements AutoCloseable {
    private final DirectoryCopyTaskRepository taskRepository;
    private final FileCopyService fileCopyService;
    private final SyncRunRepository runRepository;
    private final Logger logger;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "directory-copy-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    public DirectoryCopyScheduler(DirectoryCopyTaskRepository taskRepository,
                                  FileCopyService fileCopyService,
                                  SyncRunRepository runRepository,
                                  Logger logger) {
        this.taskRepository = taskRepository;
        this.fileCopyService = fileCopyService;
        this.runRepository = runRepository;
        this.logger = logger;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::scanDueTasks, 10, 60, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void scanDueTasks() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            for (DirectoryCopyTask task : taskRepository.listDue(20)) {
                submitIfIdle(task);
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Directory copy scheduler scan failed: " + ex.getMessage(), ex);
        } finally {
            scanning.set(false);
        }
    }

    private void submitIfIdle(DirectoryCopyTask task) {
        try {
            DirectoryCopyTask current = taskRepository.find(task.id()).orElse(null);
            if (current == null || !current.enabled() || !current.scheduleEnabled()) {
                return;
            }
            if (current.lastOperationId() != null
                    && runRepository.find(current.lastOperationId())
                    .map(run -> "RUNNING".equalsIgnoreCase(run.state()))
                    .orElse(false)) {
                taskRepository.markSkipped(current.id(), "Previous directory copy operation is still running.");
                return;
            }
            FileCopyService.CopyResult result = fileCopyService.copyCompared(toRequest(current));
            taskRepository.markSubmitted(current.id(), result.operationId(), "Directory copy task submitted by scheduler.");
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Directory copy task submit failed. id=" + task.id()
                    + ", message=" + ex.getMessage(), ex);
            try {
                taskRepository.markSkipped(task.id(), ex.getMessage());
            } catch (Exception ignored) {
                // The scheduler will try again on the next scan.
            }
        }
    }

    public static FileCopyService.ComparedCopyRequest toRequest(DirectoryCopyTask task) {
        return new FileCopyService.ComparedCopyRequest(
                task.sourceFileSourceId(),
                task.sourcePaths(),
                task.targetFileSourceId(),
                task.targetDirectory(),
                task.compareMode(),
                task.mtimeToleranceSeconds(),
                task.conflictPolicy()
        );
    }
}
