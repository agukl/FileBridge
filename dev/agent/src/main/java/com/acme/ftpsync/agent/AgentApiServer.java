package com.acme.ftpsync.agent;

import com.acme.ftpsync.code.StatusCodeCatalog;
import com.acme.ftpsync.config.AgentConfig;
import com.acme.ftpsync.dashboard.DashboardService;
import com.acme.ftpsync.db.DatabaseInitializer;
import com.acme.ftpsync.diagnostic.FtpPreflightService;
import com.acme.ftpsync.event.FtpSyncEvent;
import com.acme.ftpsync.event.FtpSyncEventRepository;
import com.acme.ftpsync.files.FileBrowserService;
import com.acme.ftpsync.files.FileCopyService;
import com.acme.ftpsync.files.DirectoryCopyScheduler;
import com.acme.ftpsync.files.DirectoryCopyTask;
import com.acme.ftpsync.files.DirectoryCopyTaskRepository;
import com.acme.ftpsync.files.FileSourcePermissionService;
import com.acme.ftpsync.license.LicenseException;
import com.acme.ftpsync.license.LicenseFeature;
import com.acme.ftpsync.license.LicenseService;
import com.acme.ftpsync.run.SyncRunSummary;
import com.acme.ftpsync.run.SyncRunRepository;
import com.acme.ftpsync.task.FileSourcePermission;
import com.acme.ftpsync.task.SyncTask;
import com.acme.ftpsync.task.TaskRepository;
import com.acme.ftpsync.task.TaskView;
import com.acme.ftpsync.util.DateTimes;
import com.acme.ftpsync.util.SecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AgentApiServer implements AutoCloseable {
    private static final Pattern TASK_DETAIL_PATTERN = Pattern.compile("^/api/tasks/([^/]+)$");
    private static final Pattern TASK_ACTION_PATTERN = Pattern.compile("^/api/tasks/([^/]+)/(run|cancel|preflight)$");
    private static final Pattern RUN_EVENTS_PATTERN = Pattern.compile("^/api/runs/(\\d+)/events$");
    private static final Pattern FILE_OPERATION_CANCEL_PATTERN = Pattern.compile("^/api/file-operations/(\\d+)/cancel$");
    private static final Pattern DIRECTORY_COPY_TASK_DETAIL_PATTERN = Pattern.compile("^/api/directory-copy-tasks/(\\d+)$");
    private static final Pattern DIRECTORY_COPY_TASK_RUN_PATTERN = Pattern.compile("^/api/directory-copy-tasks/(\\d+)/run$");
    private static final Pattern DIRECTORY_COPY_TASK_CANCEL_PATTERN = Pattern.compile("^/api/directory-copy-tasks/(\\d+)/cancel$");
    private static final Pattern FILE_SOURCE_DETAIL_PATTERN = Pattern.compile("^/api/file-sources/([^/]+)$");
    private static final Pattern FILE_SOURCE_ACTION_PATTERN =
            Pattern.compile("^/api/file-sources/([^/]+)/(preflight|files|remote-files|refresh-cache)$");

    private final AgentConfig config;
    private final LicenseService licenseService;
    private final DatabaseInitializer databaseInitializer;
    private final DashboardService dashboardService;
    private final TaskRepository taskRepository;
    private final SyncRunRepository runRepository;
    private final FtpSyncEventRepository eventRepository;
    private final FileBrowserService fileBrowserService;
    private final FileCopyService fileCopyService;
    private final DirectoryCopyTaskRepository directoryCopyTaskRepository;
    private final FileSourcePermissionService permissionService;
    private final FtpPreflightService preflightService;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final String startedAt = Instant.now().toString();

    public AgentApiServer(
            AgentConfig config,
            LicenseService licenseService,
            DatabaseInitializer databaseInitializer,
            DashboardService dashboardService,
            TaskRepository taskRepository,
            SyncRunRepository runRepository,
            FtpSyncEventRepository eventRepository,
            FileBrowserService fileBrowserService,
            FileCopyService fileCopyService,
            DirectoryCopyTaskRepository directoryCopyTaskRepository,
            FileSourcePermissionService permissionService,
            FtpPreflightService preflightService,
            Logger logger) throws IOException {
        this.config = config;
        this.licenseService = licenseService;
        this.databaseInitializer = databaseInitializer;
        this.dashboardService = dashboardService;
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.fileBrowserService = fileBrowserService;
        this.fileCopyService = fileCopyService;
        this.directoryCopyTaskRepository = directoryCopyTaskRepository;
        this.permissionService = permissionService;
        this.preflightService = preflightService;
        this.logger = logger;
        validateLoopbackApiHost();
        InetSocketAddress address = new InetSocketAddress(apiHost(), apiPort());
        this.server = HttpServer.create(address, 64);
        this.server.createContext("/", this::handleRequest);
        this.server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "filebridge-agent-api-" + System.nanoTime());
            thread.setDaemon(false);
            return thread;
        }));
    }

    public void start() {
        server.start();
        logger.info("Agent API started at http://" + server.getAddress().getHostString()
                + ":" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!isAuthorized(exchange)) {
                respondJson(exchange, 401, Map.of("ok", false, "message", "Unauthorized"));
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/agent/health".equals(path)) {
                respondJson(exchange, 200, healthBody());
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/license/device-id".equals(path)) {
                respondJson(exchange, 200, Map.of("deviceId", licenseService.deviceId()));
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/license/status".equals(path)) {
                respondJson(exchange, 200, licenseService.status());
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/license/import".equals(path)) {
                LicenseImportRequest request = mapper.readValue(exchange.getRequestBody(), LicenseImportRequest.class);
                respondJson(exchange, 200, licenseService.importLicense(request.licenseText()));
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/file-sources".equals(path)) {
                respondJson(exchange, 200, Map.of(
                        "sources",
                        taskRepository.list(500).stream().map(TaskView::from).toList()
                ));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/file-sources".equals(path)) {
                SyncTask source = mapper.readValue(exchange.getRequestBody(), SyncTask.class);
                requireSourceSave(source, source.taskId());
                taskRepository.upsert(source);
                FileSourcePermission permission = checkAndRecordPermission(source.taskId());
                respondJson(exchange, 200, Map.of("ok", true, "sourceId", source.taskId(), "permission", permission));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/file-operations".equals(path)) {
                respondJson(exchange, 410, Map.of("ok", false, "message",
                        "File source no longer supports sync-to-local operations."));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/file-operations/copy".equals(path)) {
                try {
                    licenseService.requireFeature(LicenseFeature.FILE_COPY);
                    FileCopyService.CopyRequest request =
                            mapper.readValue(exchange.getRequestBody(), FileCopyService.CopyRequest.class);
                    respondJson(exchange, 200, fileCopyService.copy(request));
                } catch (IllegalArgumentException | IllegalStateException | SecurityException | IOException ex) {
                    respondJson(exchange, 400, Map.of("ok", false, "message", safeMessage(ex)));
                }
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/directory-copy-tasks".equals(path)) {
                respondJson(exchange, 200, Map.of(
                        "tasks",
                        directoryCopyTaskRepository.list(intQuery(exchange, "limit", 200, 500))
                ));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/directory-copy-tasks".equals(path)) {
                try {
                    licenseService.requireFeature(LicenseFeature.FILE_COPY);
                    DirectoryCopyTask request = mapper.readValue(exchange.getRequestBody(), DirectoryCopyTask.class);
                    DirectoryCopyTask saved = directoryCopyTaskRepository.create(request);
                    respondJson(exchange, 200, Map.of("ok", true, "task", saved));
                } catch (IllegalArgumentException | IllegalStateException | SecurityException | IOException ex) {
                    respondJson(exchange, 400, Map.of("ok", false, "message", safeMessage(ex)));
                } catch (Exception ex) {
                    throw new IOException(ex);
                }
                return;
            }

            Matcher directoryCopyTaskRunMatcher = DIRECTORY_COPY_TASK_RUN_PATTERN.matcher(path);
            if ("POST".equalsIgnoreCase(method) && directoryCopyTaskRunMatcher.matches()) {
                try {
                    licenseService.requireFeature(LicenseFeature.FILE_COPY);
                    long taskId = Long.parseLong(directoryCopyTaskRunMatcher.group(1));
                    DirectoryCopyTask task = directoryCopyTaskRepository.find(taskId)
                            .orElseThrow(() -> new IllegalArgumentException("Directory copy task not found: " + taskId));
                    FileCopyService.CopyResult result = fileCopyService.copyCompared(DirectoryCopyScheduler.toRequest(task));
                    directoryCopyTaskRepository.markSubmitted(task.id(), result.operationId(),
                            "Directory copy task submitted manually.");
                    respondJson(exchange, 200, result);
                } catch (IllegalArgumentException | IllegalStateException | SecurityException | IOException ex) {
                    respondJson(exchange, 400, Map.of("ok", false, "message", safeMessage(ex)));
                } catch (Exception ex) {
                    throw new IOException(ex);
                }
                return;
            }

            Matcher directoryCopyTaskCancelMatcher = DIRECTORY_COPY_TASK_CANCEL_PATTERN.matcher(path);
            if ("POST".equalsIgnoreCase(method) && directoryCopyTaskCancelMatcher.matches()) {
                try {
                    licenseService.requireFeature(LicenseFeature.FILE_COPY);
                    long taskId = Long.parseLong(directoryCopyTaskCancelMatcher.group(1));
                    DirectoryCopyTask task = directoryCopyTaskRepository.find(taskId)
                            .orElseThrow(() -> new IllegalArgumentException("Directory copy task not found: " + taskId));
                    if (task.lastOperationId() == null) {
                        respondJson(exchange, 200, new FileCopyService.CancelResult(
                                false,
                                0L,
                                "Directory copy task has no operation to cancel."
                        ));
                    } else {
                        respondJson(exchange, 200, fileCopyService.cancel(task.lastOperationId()));
                    }
                } catch (IllegalArgumentException | IllegalStateException | SecurityException | IOException ex) {
                    respondJson(exchange, 400, Map.of("ok", false, "message", safeMessage(ex)));
                } catch (Exception ex) {
                    throw new IOException(ex);
                }
                return;
            }

            Matcher directoryCopyTaskMatcher = DIRECTORY_COPY_TASK_DETAIL_PATTERN.matcher(path);
            if (directoryCopyTaskMatcher.matches()) {
                long taskId = Long.parseLong(directoryCopyTaskMatcher.group(1));
                if ("GET".equalsIgnoreCase(method)) {
                    try {
                        var task = directoryCopyTaskRepository.find(taskId);
                        if (task.isPresent()) {
                            respondJson(exchange, 200, task.get());
                        } else {
                            respondJson(exchange, 404, Map.of("ok", false, "message",
                                    "Directory copy task not found: " + taskId));
                        }
                    } catch (Exception ex) {
                        throw new IOException(ex);
                    }
                    return;
                }
                if ("PUT".equalsIgnoreCase(method)) {
                    try {
                        licenseService.requireFeature(LicenseFeature.FILE_COPY);
                        DirectoryCopyTask request = mapper.readValue(exchange.getRequestBody(), DirectoryCopyTask.class);
                        DirectoryCopyTask saved = directoryCopyTaskRepository.update(taskId, request);
                        respondJson(exchange, 200, Map.of("ok", true, "task", saved));
                    } catch (IllegalArgumentException | IllegalStateException | SecurityException | IOException ex) {
                        respondJson(exchange, 400, Map.of("ok", false, "message", safeMessage(ex)));
                    } catch (Exception ex) {
                        throw new IOException(ex);
                    }
                    return;
                }
                if ("DELETE".equalsIgnoreCase(method)) {
                    try {
                        licenseService.requireFeature(LicenseFeature.FILE_COPY);
                        boolean deleted = directoryCopyTaskRepository.delete(taskId);
                        if (deleted) {
                            respondJson(exchange, 200, Map.of("ok", true, "taskId", taskId));
                        } else {
                            respondJson(exchange, 404, Map.of("ok", false, "message",
                                    "Directory copy task not found: " + taskId));
                        }
                    } catch (IllegalArgumentException | IllegalStateException | SecurityException | IOException ex) {
                        respondJson(exchange, 400, Map.of("ok", false, "message", safeMessage(ex)));
                    } catch (Exception ex) {
                        throw new IOException(ex);
                    }
                    return;
                }
            }

            Matcher fileOperationCancelMatcher = FILE_OPERATION_CANCEL_PATTERN.matcher(path);
            if ("POST".equalsIgnoreCase(method) && fileOperationCancelMatcher.matches()) {
                long operationId = Long.parseLong(fileOperationCancelMatcher.group(1));
                respondJson(exchange, 200, fileCopyService.cancel(operationId));
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/tasks".equals(path)) {
                respondJson(exchange, 200, Map.of(
                        "tasks",
                        taskRepository.list(500).stream().map(TaskView::from).toList()
                ));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/tasks".equals(path)) {
                SyncTask task = mapper.readValue(exchange.getRequestBody(), SyncTask.class);
                requireSourceSave(task, task.taskId());
                taskRepository.upsert(task);
                FileSourcePermission permission = checkAndRecordPermission(task.taskId());
                respondJson(exchange, 200, Map.of("ok", true, "taskId", task.taskId(), "permission", permission));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/tasks/preflight-draft".equals(path)) {
                SyncTask task = mapper.readValue(exchange.getRequestBody(), SyncTask.class);
                if (isRemoteFtpSource(task)) {
                    licenseService.requireFeature(LicenseFeature.REMOTE_FTP);
                }
                respondJson(exchange, 200, Map.of("ok", true, "diagnostic", preflightService.runDraft(task)));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/ftp/remote-directories".equals(path)) {
                RemoteDirectoryRequest request = mapper.readValue(exchange.getRequestBody(), RemoteDirectoryRequest.class);
                licenseService.requireFeature(LicenseFeature.REMOTE_FTP);
                respondJson(exchange, 200, Map.of(
                        "ok", true,
                        "listing", preflightService.listRemoteDirectories(
                                request.task(),
                                request.path(),
                                intQuery(exchange, "limit", 200, 500)
                        )
                ));
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/status-codes".equals(path)) {
                respondJson(exchange, 200, StatusCodeCatalog.all());
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/dashboard/overview".equals(path)) {
                respondJson(exchange, 200, dashboardService.overview(intQuery(exchange, "limit", 100, 500)));
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/dashboard/board".equals(path)) {
                respondJson(exchange, 200, dashboardService.board(
                        intQuery(exchange, "hours", 24, 24 * 14),
                        intQuery(exchange, "runLimit", 4000, 20_000),
                        intQuery(exchange, "eventLimit", 6000, 20_000)
                ));
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/runs".equals(path)) {
                int legacyLimit = intQuery(exchange, "limit", 0, 1000);
                int pageSizeFallback = legacyLimit > 0 ? legacyLimit : 100;
                respondJson(exchange, 200, runRepository.listRecentPage(new SyncRunRepository.RunQuery(
                        intQuery(exchange, "page", 1, Integer.MAX_VALUE),
                        intQuery(exchange, "pageSize", pageSizeFallback, 1000),
                        queryParam(exchange, "state"),
                        queryParam(exchange, "taskId"),
                        queryParam(exchange, "q"),
                        "operations".equalsIgnoreCase(value(queryParam(exchange, "scope"), "")),
                        queryParam(exchange, "cursor"),
                        booleanQuery(exchange, "includeTotal", true)
                )));
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/events".equals(path)) {
                int legacyLimit = intQuery(exchange, "limit", 0, 500);
                int pageSizeFallback = legacyLimit > 0 ? legacyLimit : 100;
                respondJson(exchange, 200, eventRepository.listRecentPage(new FtpSyncEventRepository.EventQuery(
                        intQuery(exchange, "page", 1, Integer.MAX_VALUE),
                        intQuery(exchange, "pageSize", pageSizeFallback, 500),
                        queryParam(exchange, "state"),
                        queryParam(exchange, "taskId"),
                        queryParam(exchange, "q"),
                        queryParam(exchange, "cursor"),
                        booleanQuery(exchange, "includeTotal", true)
                )));
                return;
            }

            Matcher runEventsMatcher = RUN_EVENTS_PATTERN.matcher(path);
            if ("GET".equalsIgnoreCase(method) && runEventsMatcher.matches()) {
                long runId = Long.parseLong(runEventsMatcher.group(1));
                respondJson(exchange, 200, Map.of("events", eventRepository.listRunEvents(runId, 1000)));
                return;
            }

            Matcher fileSourceActionMatcher = FILE_SOURCE_ACTION_PATTERN.matcher(path);
            if (fileSourceActionMatcher.matches()) {
                String sourceId = decode(fileSourceActionMatcher.group(1));
                String action = fileSourceActionMatcher.group(2);
                var source = taskRepository.find(sourceId);
                if (source.isEmpty()) {
                    respondJson(exchange, 404, Map.of("ok", false, "message", "File source not found: " + sourceId));
                    return;
                }
                if ("POST".equalsIgnoreCase(method) && "preflight".equals(action)) {
                    if (!isRemoteFtpSource(source.get())) {
                        respondJson(exchange, 400, Map.of("ok", false, "message",
                                "Local file source does not need FTP preflight."));
                        return;
                    }
                    licenseService.requireFeature(LicenseFeature.REMOTE_FTP);
                    long operationId = beginOperation(source.get(), "CONNECTION_TEST", source.get().sourcePath());
                    try {
                        var diagnostic = preflightService.runTask(sourceId);
                        if ("SUCCESS".equalsIgnoreCase(diagnostic.state())) {
                            finishOperation(operationId, "HEALTHY", 0, 0, 0, 0, 0, "",
                                    diagnostic.message());
                        } else {
                            runRepository.markFailure(operationId, new SyncRunSummary(
                                    "FAILED", 0, 0, 0, 0,
                                    0, 1, diagnostic.errorCategory(), diagnostic.message()));
                        }
                        recordEvent(source.get(), operationId, "INFO", "CHECK", "CONNECTION_TEST",
                                diagnostic.state(), source.get().sourcePath(), source.get().sourcePath(),
                                diagnostic.durationMs(), diagnostic.message(), diagnostic.detailsText());
                        respondJson(exchange, 200, Map.of("ok", true, "diagnostic", diagnostic));
                    } catch (Exception ex) {
                        failOperation(operationId, source.get(), "CHECK", "CONNECTION_TEST",
                                source.get().sourcePath(), ex);
                        throw ex;
                    }
                    return;
                }
                if ("POST".equalsIgnoreCase(method) && "refresh-cache".equals(action)) {
                    licenseService.requireFeature(LicenseFeature.DIRECTORY_CACHE);
                    long operationId = beginOperation(source.get(), "DIRECTORY_CACHE_REFRESH", source.get().sourcePath());
                    try {
                        var result = fileBrowserService.refreshDirectoryCache(source.get());
                        boolean partial = result.errorCount() > 0 || result.maxDirectoriesReached();
                        finishOperation(
                                operationId,
                                partial ? "COMPLETED_WITH_WARNINGS" : "HEALTHY",
                                result.fileCount(),
                                result.directoryCount(),
                                result.totalBytes(),
                                result.maxDirectoriesReached() ? 1 : 0,
                                result.errorCount(),
                                partial ? "DIRECTORY_CACHE_PARTIAL" : "",
                                result.message()
                        );
                        recordEvent(source.get(), operationId, partial ? "WARN" : "INFO",
                                "CACHE", "DIRECTORY_CACHE_REFRESH", "DIRECTORY_CACHE_REFRESHED",
                                source.get().sourcePath(), source.get().sourcePath(), null,
                                result.message(),
                                "paths=" + result.cachedPathCount()
                                        + ", files=" + result.fileCount()
                                        + ", directories=" + result.directoryCount()
                                        + ", errors=" + result.errorCount());
                        respondJson(exchange, 200, Map.of("ok", true, "result", result));
                    } catch (IllegalArgumentException | IllegalStateException | SecurityException | IOException ex) {
                        failOperation(operationId, source.get(), "CACHE", "DIRECTORY_CACHE_REFRESH",
                                source.get().sourcePath(), ex);
                        respondJson(exchange, 400, Map.of("ok", false, "message", safeMessage(ex)));
                    } catch (Exception ex) {
                        failOperation(operationId, source.get(), "CACHE", "DIRECTORY_CACHE_REFRESH",
                                source.get().sourcePath(), ex);
                        throw ex;
                    }
                    return;
                }
                if ("GET".equalsIgnoreCase(method) && ("files".equals(action) || "remote-files".equals(action))) {
                    if ("remote-files".equals(action) && !isRemoteFtpSource(source.get())) {
                        respondJson(exchange, 400, Map.of("ok", false, "message",
                                "Use /api/file-sources/{sourceId}/files to browse local file sources."));
                        return;
                    }
                    if (isRemoteFtpSource(source.get())) {
                        licenseService.requireFeature(LicenseFeature.REMOTE_FTP);
                    }
                    String requestedPath = queryParam(exchange, "path");
                    long operationId = beginOperation(source.get(), "FILE_BROWSE",
                            requestedPath == null || requestedPath.isBlank() ? source.get().sourcePath() : requestedPath);
                    try {
                        FileBrowserService.FileListing listing = fileBrowserService.listFiles(
                                source.get(),
                                requestedPath,
                                intQuery(exchange, "limit", 300, 1000)
                        );
                        long fileCount = listing.entries().stream().filter(entry -> "FILE".equals(entry.type())).count();
                        long directoryCount = listing.entries().stream().filter(entry -> "DIRECTORY".equals(entry.type())).count();
                        long totalBytes = listing.entries().stream().mapToLong(FileBrowserService.FileEntry::size).sum();
                        finishOperation(operationId, "HEALTHY", fileCount, directoryCount, totalBytes,
                                0, 0, "", "Files listed.");
                        recordEvent(source.get(), operationId, "INFO", "BROWSE", "LIST",
                                "FILES_LISTED", listing.path(), listing.path(), null,
                                "Files listed.", "items=" + listing.entries().size());
                        respondJson(exchange, 200, Map.of("ok", true, "listing", listing));
                    } catch (Exception ex) {
                        failOperation(operationId, source.get(), "BROWSE", "LIST",
                                requestedPath == null || requestedPath.isBlank() ? source.get().sourcePath() : requestedPath, ex);
                        respondJson(exchange, fileBrowseErrorStatus(ex), Map.of("ok", false, "message", safeMessage(ex)));
                    }
                    return;
                }
            }

            Matcher fileSourceMatcher = FILE_SOURCE_DETAIL_PATTERN.matcher(path);
            if (fileSourceMatcher.matches()) {
                String sourceId = decode(fileSourceMatcher.group(1));
                if ("GET".equalsIgnoreCase(method)) {
                    var source = taskRepository.find(sourceId);
                    if (source.isPresent()) {
                        respondJson(exchange, 200, TaskView.from(source.get()));
                    } else {
                        respondJson(exchange, 404, Map.of("ok", false, "message", "File source not found: " + sourceId));
                    }
                    return;
                }
                if ("PUT".equalsIgnoreCase(method)) {
                    SyncTask input = mapper.readValue(exchange.getRequestBody(), SyncTask.class);
                    requireSourceSave(input, sourceId);
                    String passwordRef = input.passwordRef();
                    if (passwordRef == null || passwordRef.isBlank()) {
                        passwordRef = taskRepository.find(sourceId)
                                .map(SyncTask::passwordRef)
                                .orElse("");
                    }
                    SyncTask source = new SyncTask(
                            sourceId,
                            input.taskName(),
                            input.sourceType(),
                            input.ftpHost(),
                            input.ftpPort(),
                            input.ftpUsername(),
                            passwordRef,
                            input.secureMode(),
                            input.tlsFingerprint(),
                            input.tlsFingerprintHash(),
                            input.sourcePath(),
                            input.remoteDirectoryCacheEnabled(),
                            input.permission()
                    );
                    taskRepository.upsert(source);
                    FileSourcePermission permission = checkAndRecordPermission(sourceId);
                    respondJson(exchange, 200, Map.of("ok", true, "sourceId", sourceId, "permission", permission));
                    return;
                }
                if ("DELETE".equalsIgnoreCase(method)) {
                    try {
                        licenseService.requireFeature(LicenseFeature.FILE_SOURCE_MANAGE);
                        boolean deleted = taskRepository.delete(sourceId);
                        if (deleted) {
                            respondJson(exchange, 200, Map.of("ok", true, "sourceId", sourceId));
                        } else {
                            respondJson(exchange, 404, Map.of("ok", false, "message", "File source not found: " + sourceId));
                        }
                    } catch (IllegalArgumentException | IllegalStateException | SecurityException | IOException ex) {
                        respondJson(exchange, 400, Map.of("ok", false, "message", safeMessage(ex)));
                    }
                    return;
                }
            }

            Matcher taskActionMatcher = TASK_ACTION_PATTERN.matcher(path);
            if ("POST".equalsIgnoreCase(method) && taskActionMatcher.matches()) {
                String taskId = decode(taskActionMatcher.group(1));
                String action = taskActionMatcher.group(2);
                if ("cancel".equals(action)) {
                    respondJson(exchange, 410, Map.of("ok", false, "message",
                            "Running file operations have been removed from file source management."));
                } else if ("preflight".equals(action)) {
                    licenseService.requireFeature(LicenseFeature.REMOTE_FTP);
                    respondJson(exchange, 200, Map.of("ok", true, "diagnostic", preflightService.runTask(taskId)));
                } else {
                    respondJson(exchange, 410, Map.of("ok", false, "message",
                            "Task run has been removed from file source management."));
                }
                return;
            }

            Matcher taskMatcher = TASK_DETAIL_PATTERN.matcher(path);
            if (taskMatcher.matches()) {
                String taskId = decode(taskMatcher.group(1));
                if ("GET".equalsIgnoreCase(method)) {
                    var task = taskRepository.find(taskId);
                    if (task.isPresent()) {
                        respondJson(exchange, 200, TaskView.from(task.get()));
                    } else {
                        respondJson(exchange, 404, Map.of("ok", false, "message", "Task not found: " + taskId));
                    }
                    return;
                }
                if ("PUT".equalsIgnoreCase(method)) {
                    SyncTask input = mapper.readValue(exchange.getRequestBody(), SyncTask.class);
                    requireSourceSave(input, taskId);
                    String passwordRef = input.passwordRef();
                    if (passwordRef == null || passwordRef.isBlank()) {
                        passwordRef = taskRepository.find(taskId)
                                .map(SyncTask::passwordRef)
                                .orElse("");
                    }
                    SyncTask task = new SyncTask(
                            taskId,
                            input.taskName(),
                            input.sourceType(),
                            input.ftpHost(),
                            input.ftpPort(),
                            input.ftpUsername(),
                            passwordRef,
                            input.secureMode(),
                            input.tlsFingerprint(),
                            input.tlsFingerprintHash(),
                            input.sourcePath(),
                            input.remoteDirectoryCacheEnabled(),
                            input.permission()
                    );
                    taskRepository.upsert(task);
                    FileSourcePermission permission = checkAndRecordPermission(taskId);
                    respondJson(exchange, 200, Map.of("ok", true, "taskId", taskId, "permission", permission));
                    return;
                }
                if ("DELETE".equalsIgnoreCase(method)) {
                    try {
                        licenseService.requireFeature(LicenseFeature.FILE_SOURCE_MANAGE);
                        boolean deleted = taskRepository.delete(taskId);
                        if (deleted) {
                            respondJson(exchange, 200, Map.of("ok", true, "taskId", taskId));
                        } else {
                            respondJson(exchange, 404, Map.of("ok", false, "message", "Task not found: " + taskId));
                        }
                    } catch (IllegalArgumentException | IllegalStateException | SecurityException | IOException ex) {
                        respondJson(exchange, 400, Map.of("ok", false, "message", safeMessage(ex)));
                    }
                    return;
                }
            }

            respondJson(exchange, 404, Map.of("ok", false, "message", "Not Found"));
        } catch (LicenseException ex) {
            respondJson(exchange, 403, Map.of("ok", false, "message", ex.getMessage()));
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "API request failed: type=" + ex.getClass().getName()
                    + ", message=" + safeMessage(ex));
            respondJson(exchange, 500, Map.of("ok", false, "message",
                    safeMessage(ex)));
        } finally {
            exchange.close();
        }
    }

    private Map<String, Object> healthBody() {
        DatabaseInitializer.DatabaseStatus db = databaseInitializer.checkStatus();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", db.connected() && db.schemaReady());
        body.put("service", "filebridge-agent");
        body.put("startedAt", startedAt);
        body.put("checkedAt", Instant.now().toString());
        body.put("databaseConnected", db.connected());
        body.put("schemaReady", db.schemaReady());
        body.put("schema", db.schema());
        body.put("tables", db.tables());
        body.put("error", db.error());
        body.put("activeTasks", java.util.List.of());
        return body;
    }

    private void requireSourceSave(SyncTask source, String sourceId) throws SQLException {
        licenseService.requireFeature(LicenseFeature.FILE_SOURCE_MANAGE);
        if (isRemoteFtpSource(source)) {
            licenseService.requireFeature(LicenseFeature.REMOTE_FTP);
        }
        var existing = taskRepository.find(sourceId);
        int currentCount = taskRepository.list(500).size();
        licenseService.requireFileSourceLimit(existing.isPresent() ? currentCount : currentCount + 1);
    }

    private long beginOperation(SyncTask source, String operationType, String sourcePath) throws SQLException {
        return runRepository.create(source.taskId(), operationType, sourcePath);
    }

    private FileSourcePermission checkAndRecordPermission(String sourceId) throws SQLException {
        var source = taskRepository.find(sourceId);
        if (source.isEmpty()) {
            FileSourcePermission permission = FileSourcePermission.failed("File source not found: " + sourceId);
            taskRepository.updatePermission(sourceId, permission);
            return permission;
        }
        FileSourcePermission permission = permissionService.check(source.get());
        taskRepository.updatePermission(sourceId, permission);
        return permission;
    }

    private void finishOperation(long operationId, String finalHealth, long fileCount, long directoryCount,
                                 long totalBytes, long warningCount, long errorCount,
                                 String lastErrorCategory, String message) throws SQLException {
        runRepository.markSuccess(operationId, new SyncRunSummary(
                finalHealth,
                fileCount + directoryCount,
                fileCount,
                directoryCount,
                totalBytes,
                warningCount,
                errorCount,
                lastErrorCategory,
                message
        ));
    }

    private void failOperation(long operationId, SyncTask source, String phase, String operationName,
                               String sourcePath, Exception ex) throws SQLException {
        String message = ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
        recordEvent(source, operationId, "ERROR", phase, operationName, "FILE_OPERATION_FAILED",
                sourcePath, sourcePath, null, message, "");
        runRepository.markFailure(operationId, new SyncRunSummary(
                "FAILED",
                0,
                0,
                0,
                0,
                0,
                1,
                "FILE_OPERATION_ERROR",
                message
        ));
    }

    private void recordEvent(SyncTask source, long operationId, String level, String phase, String operationName,
                             String eventType, String sourcePath, String itemPath, Long durationMs,
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
                sourcePath,
                itemPath,
                "",
                null,
                durationMs,
                "",
                "",
                message,
                details
        ));
    }

    private String apiHost() {
        return config.api() == null || config.api().host() == null || config.api().host().isBlank()
                ? "127.0.0.1"
                : config.api().host();
    }

    private void validateLoopbackApiHost() throws IOException {
        String host = apiHost().trim().toLowerCase(Locale.ROOT);
        if (!List.of("127.0.0.1", "localhost", "::1", "[::1]").contains(host)) {
            throw new IOException("Agent API host must be loopback only. Refusing to listen on: " + apiHost());
        }
    }

    private int apiPort() {
        return config.api() == null || config.api().port() <= 0 ? 18090 : config.api().port();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static int intQuery(HttpExchange exchange, String name, int fallback, int max) {
        String raw = queryParam(exchange, name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(raw), max));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean booleanQuery(HttpExchange exchange, String name, boolean fallback) {
        String raw = queryParam(exchange, name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("0".equals(normalized) || "false".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        return fallback;
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        String[] parts = query.split("&");
        for (String part : parts) {
            int index = part.indexOf('=');
            String key = index < 0 ? part : part.substring(0, index);
            if (name.equals(decode(key))) {
                return index < 0 ? "" : decode(part.substring(index + 1));
            }
        }
        return null;
    }

    private void respondJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] data = mapper.writeValueAsBytes(body);
        Headers headers = exchange.getResponseHeaders();
        applyCorsHeaders(exchange);
        headers.set("Content-Type", "application/json; charset=UTF-8");
        headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
        exchange.sendResponseHeaders(statusCode, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private void applyCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        Headers headers = exchange.getResponseHeaders();
        if (isAllowedCorsOrigin(origin)) {
            headers.set("Access-Control-Allow-Origin", origin);
            headers.set("Vary", "Origin");
        }
        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-FileBridge-Token");
        headers.set("Access-Control-Max-Age", "3600");
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String expected = configuredToken();
        if (expected.isBlank()) {
            return true;
        }
        String bearer = exchange.getRequestHeaders().getFirst("Authorization");
        if (bearer != null && bearer.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return expected.equals(bearer.substring("Bearer ".length()).trim());
        }
        String token = exchange.getRequestHeaders().getFirst("X-FileBridge-Token");
        return expected.equals(token == null ? "" : token.trim());
    }

    private String configuredToken() {
        return config.api() == null || config.api().token() == null ? "" : config.api().token().trim();
    }

    private static boolean isAllowedCorsOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        if ("tauri://localhost".equalsIgnoreCase(origin)) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean localScheme = "http".equals(scheme) || "https".equals(scheme);
            boolean localHost = "localhost".equals(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)
                    || "tauri.localhost".equals(host);
            return localScheme && localHost;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean isRemoteFtpSource(SyncTask source) {
        String sourceType = source.sourceType() == null ? "REMOTE_FTP" : source.sourceType().trim().toUpperCase(Locale.ROOT);
        return "REMOTE_FTP".equals(sourceType);
    }

    private static int fileBrowseErrorStatus(Exception ex) {
        return ex instanceof IOException || ex instanceof IllegalArgumentException || ex instanceof SecurityException
                ? 400
                : 500;
    }

    private static String safeMessage(Throwable ex) {
        return SecretRedactor.redact(ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage());
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record RemoteDirectoryRequest(SyncTask task, String path) {
    }

    private record LicenseImportRequest(String licenseText) {
    }

}

