package com.acme.ftpsync.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Files;
import java.nio.file.Path;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentConfig(Api api, Sqlite sqlite, Paths paths, Retry retry,
                          @JsonIgnore Path sourceFile) {
    public AgentConfig withSourceFile(Path sourceFile) {
        return new AgentConfig(api, sqlite, paths, retry, sourceFile);
    }

    public Path resolvePath(String configuredPath) {
        Path path = Path.of(configuredPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path cwdResolved = path.toAbsolutePath().normalize();
        if (Files.exists(cwdResolved)) {
            return cwdResolved;
        }
        Path configParent = sourceFile == null || sourceFile.getParent() == null
                ? Path.of(".").toAbsolutePath()
                : sourceFile.getParent().toAbsolutePath();
        return configParent.resolve(path).normalize();
    }

    public record Api(String host, int port, String token) {
    }

    public record Sqlite(String databasePath) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Paths(String initSqlPath, String logFile, String checkpointDir) {
    }

    public record Retry(int maxAttempts, int backoffMillis) {
    }
}
