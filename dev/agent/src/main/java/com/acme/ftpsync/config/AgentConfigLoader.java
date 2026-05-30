package com.acme.ftpsync.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AgentConfigLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentConfigLoader() {
    }

    public static AgentConfig load(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            throw new IllegalArgumentException("Agent config not found: " + absolute);
        }
        AgentConfig loaded = MAPPER.readValue(stripBom(Files.readString(absolute)), AgentConfig.class);
        validate(loaded);
        return loaded.withSourceFile(absolute);
    }

    private static void validate(AgentConfig config) {
        if (config.api() == null || config.sqlite() == null || config.paths() == null) {
            throw new IllegalArgumentException("Agent config must contain api, sqlite, and paths sections.");
        }
        if (blank(config.sqlite().databasePath())) {
            throw new IllegalArgumentException("sqlite.databasePath is required.");
        }
        if (blank(config.paths().initSqlPath())) {
            throw new IllegalArgumentException("paths.initSqlPath is required.");
        }
    }

    private static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
