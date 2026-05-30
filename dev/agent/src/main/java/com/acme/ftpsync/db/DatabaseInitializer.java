package com.acme.ftpsync.db;

import com.acme.ftpsync.config.AgentConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

public final class DatabaseInitializer {
    private static final String DATABASE_NAME = "sqlite";
    private static final List<String> REQUIRED_TABLES = List.of(
            "file_source",
            "file_source_directory_cache",
            "directory_copy_task",
            "file_operation",
            "file_operation_log",
            "file_source_diagnostic",
            "file_source_diagnostic_step"
    );

    private final Jdbc jdbc;
    private final Logger logger;

    public DatabaseInitializer(AgentConfig config, Logger logger) {
        this.jdbc = new Jdbc(config);
        this.logger = logger;
    }

    public DatabaseStatus initialize(Path initSqlPath) throws SQLException, IOException {
        if (!Files.exists(initSqlPath)) {
            throw new IllegalArgumentException("SQLite init SQL file not found: " + initSqlPath);
        }
        String script = Files.readString(initSqlPath, StandardCharsets.UTF_8);
        List<String> statements = splitStatements(stripSqlComments(script));
        try (Connection connection = jdbc.open(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
            List<String> tables = loadTables(statement);
            boolean ready = tables.containsAll(REQUIRED_TABLES);
            logger.info("Database initialized. database=" + DATABASE_NAME + ", tables=" + tables);
            return new DatabaseStatus(true, ready, DATABASE_NAME, tables, Instant.now().toString(), "");
        } catch (SQLException ex) {
            return new DatabaseStatus(false, false, DATABASE_NAME, List.of(), Instant.now().toString(), ex.getMessage());
        }
    }

    public DatabaseStatus checkStatus() {
        try (Connection connection = jdbc.open(); Statement statement = connection.createStatement()) {
            List<String> tables = loadTables(statement);
            return new DatabaseStatus(true, tables.containsAll(REQUIRED_TABLES), DATABASE_NAME, tables,
                    Instant.now().toString(), "");
        } catch (SQLException ex) {
            return new DatabaseStatus(false, false, DATABASE_NAME, List.of(), Instant.now().toString(), ex.getMessage());
        }
    }

    private List<String> loadTables(Statement statement) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = """
                SELECT name
                FROM sqlite_master
                WHERE type IN ('table', 'view')
                  AND name NOT LIKE 'sqlite_%'
                  AND name NOT LIKE '%_fts%'
                ORDER BY name
                """;
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private static String stripSqlComments(String script) {
        String text = stripBom(script);
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.startsWith("#")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inTrigger = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }
            if (!inSingleQuote && !inDoubleQuote && !inTrigger) {
                String currentSql = current.toString().stripLeading().toUpperCase(Locale.ROOT);
                inTrigger = currentSql.startsWith("CREATE TRIGGER")
                        || currentSql.startsWith("CREATE TEMP TRIGGER")
                        || currentSql.startsWith("CREATE TEMPORARY TRIGGER");
            }
            if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                if (inTrigger) {
                    String currentSql = current.toString().trim().toUpperCase(Locale.ROOT);
                    if (!currentSql.endsWith("END")) {
                        current.append(c);
                        continue;
                    }
                }
                statements.add(current.toString().trim());
                current.setLength(0);
                inTrigger = false;
            } else {
                current.append(c);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    public record DatabaseStatus(
            boolean connected,
            boolean schemaReady,
            String schema,
            List<String> tables,
            String checkedAt,
            String error
    ) {
    }
}
