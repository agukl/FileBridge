package com.acme.ftpsync.agent;

import com.acme.ftpsync.config.AgentConfig;
import com.acme.ftpsync.config.AgentConfigLoader;
import com.acme.ftpsync.dashboard.DashboardService;
import com.acme.ftpsync.db.DatabaseInitializer;
import com.acme.ftpsync.db.Jdbc;
import com.acme.ftpsync.diagnostic.FtpPreflightService;
import com.acme.ftpsync.diagnostic.RemoteDirectoryCacheRepository;
import com.acme.ftpsync.diagnostic.TaskDiagnosticRepository;
import com.acme.ftpsync.event.FtpSyncEventRepository;
import com.acme.ftpsync.files.FileBrowserService;
import com.acme.ftpsync.files.FileCopyService;
import com.acme.ftpsync.files.DirectoryCopyScheduler;
import com.acme.ftpsync.files.DirectoryCopyTaskRepository;
import com.acme.ftpsync.files.FileSourcePermissionService;
import com.acme.ftpsync.license.LicenseService;
import com.acme.ftpsync.run.SyncRunRepository;
import com.acme.ftpsync.task.TaskRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class AgentMain {
    private AgentMain() {
    }

    public static void main(String[] args) {
        try {
            Path configPath = parseConfigPath(args);
            AgentConfig config = AgentConfigLoader.load(configPath);
            Logger logger = createLogger(config.resolvePath(config.paths().logFile()));
            DatabaseInitializer databaseInitializer = new DatabaseInitializer(config, logger);
            DatabaseInitializer.DatabaseStatus status = databaseInitializer.initialize(config.resolvePath(config.paths().initSqlPath()));
            if (!status.connected() || !status.schemaReady()) {
                logger.warning("Database is not ready. The Agent API will start in degraded mode. error="
                        + status.error());
            }

            Jdbc jdbc = new Jdbc(config);
            FtpSyncEventRepository eventRepository = new FtpSyncEventRepository(jdbc);
            TaskDiagnosticRepository diagnosticRepository = new TaskDiagnosticRepository(jdbc);
            RemoteDirectoryCacheRepository remoteDirectoryCacheRepository = new RemoteDirectoryCacheRepository(jdbc);
            DirectoryCopyTaskRepository directoryCopyTaskRepository = new DirectoryCopyTaskRepository(jdbc);
            TaskRepository taskRepository = new TaskRepository(jdbc);
            SyncRunRepository runRepository = new SyncRunRepository(jdbc);
            DashboardService dashboardService = new DashboardService(
                    taskRepository,
                    runRepository,
                    eventRepository,
                    diagnosticRepository,
                    remoteDirectoryCacheRepository
            );
            FtpPreflightService preflightService = new FtpPreflightService(
                    taskRepository,
                    diagnosticRepository,
                    remoteDirectoryCacheRepository
            );
            FileBrowserService fileBrowserService = new FileBrowserService(
                    preflightService,
                    remoteDirectoryCacheRepository
            );
            FileSourcePermissionService permissionService = new FileSourcePermissionService(preflightService);
            FileCopyService fileCopyService = new FileCopyService(
                    taskRepository,
                    runRepository,
                    eventRepository,
                    preflightService,
                    remoteDirectoryCacheRepository
            );
            DirectoryCopyScheduler directoryCopyScheduler = new DirectoryCopyScheduler(
                    directoryCopyTaskRepository,
                    fileCopyService,
                    runRepository,
                    logger
            );
            LicenseService licenseService = new LicenseService(config);

            try (fileCopyService; directoryCopyScheduler; AgentApiServer server = new AgentApiServer(
                    config,
                    licenseService,
                    databaseInitializer,
                    dashboardService,
                    taskRepository,
                    runRepository,
                    eventRepository,
                    fileBrowserService,
                    fileCopyService,
                    directoryCopyTaskRepository,
                    permissionService,
                    preflightService,
                    logger)) {
                directoryCopyScheduler.start();
                server.start();
                CountDownLatch waitForever = new CountDownLatch(1);
                Runtime.getRuntime().addShutdownHook(new Thread(waitForever::countDown, "filebridge-agent-shutdown"));
                waitForever.await();
            }
        } catch (Exception ex) {
            Logger fallback = Logger.getLogger("com.acme.ftpsync.agent");
            fallback.log(Level.SEVERE, "Agent terminated: " + ex.getMessage(), ex);
            System.exit(1);
        }
    }

    private static Path parseConfigPath(String[] args) {
        Path configPath = Path.of("config", "agent-config.json");
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value for --config");
                    }
                    configPath = Path.of(args[++i]);
                }
                case "--help", "-h" -> {
                    System.out.println("Usage: java -jar filebridge-agent.jar [--config <path>]");
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        return configPath;
    }

    private static Logger createLogger(Path logFile) throws Exception {
        Logger logger = Logger.getLogger("com.acme.ftpsync.agent");
        logger.setLevel(Level.INFO);
        if (logFile.getParent() != null) {
            Files.createDirectories(logFile.getParent());
        }
        FileHandler fileHandler = new FileHandler(logFile.toString(), 10 * 1024 * 1024, 5, true);
        fileHandler.setFormatter(new SimpleFormatter());
        fileHandler.setLevel(Level.INFO);
        logger.addHandler(fileHandler);
        return logger;
    }

}
