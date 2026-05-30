package com.acme.ftpsync.db;

import com.acme.ftpsync.config.AgentConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Jdbc {
    private final AgentConfig config;

    public Jdbc(AgentConfig config) {
        this.config = config;
    }

    public Connection open() throws SQLException {
        loadDriver();
        Path databasePath = config.resolvePath(config.sqlite().databasePath());
        Path parent = databasePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException ex) {
                throw new SQLException("Unable to create SQLite data directory: " + parent, ex);
            }
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        configure(connection);
        return connection;
    }

    private static void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    private static void loadDriver() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("SQLite JDBC driver not found.", ex);
        }
    }
}
