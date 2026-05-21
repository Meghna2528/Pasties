package com.pasties.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages the SQLite connection lifecycle.
 *
 * <p>A single {@link Connection} is shared across all repositories. This is
 * safe because:
 * <ul>
 *   <li>WAL journal mode allows concurrent readers while a single writer operates.</li>
 *   <li>Each repository serializes its writes through a dedicated single-threaded
 *       {@link java.util.concurrent.ExecutorService}, so no two writes race on
 *       the connection simultaneously.</li>
 * </ul>
 *
 * <p>The database file resides at
 * {@code ~/Library/Application Support/Pasties/pasties.db}.
 */
public class DatabaseManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private static final String DB_DIR =
            System.getProperty("user.home") + "/Library/Application Support/Pasties";
    private static final String DB_FILE = DB_DIR + "/pasties.db";

    private Connection connection;

    /**
     * Initializes the database: creates the directory, opens/creates the DB file,
     * applies WAL + performance settings, and runs {@code schema.sql}.
     *
     * @throws SQLException if the connection cannot be established
     * @throws IOException  if {@code schema.sql} cannot be read from resources
     */
    public void initialize() throws SQLException, IOException {
        Path dbDir = Paths.get(DB_DIR);
        Files.createDirectories(dbDir);

        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.enforceForeignKeys(true);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setCacheSize(4000);
        config.setBusyTimeout(5000);

        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + DB_FILE);
        connection = ds.getConnection();

        log.info("Database opened at {}", DB_FILE);
        runSchema();
    }

    private void runSchema() throws IOException, SQLException {
        URL schemaUrl = getClass().getResource("/db/schema.sql");
        if (schemaUrl == null) {
            throw new IllegalStateException("schema.sql not found in resources");
        }

        try (InputStream is = schemaUrl.openStream()) {
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement stmt = connection.createStatement()) {
                for (String statement : sql.split(";")) {
                    String trimmed = statement.strip();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed);
                    }
                }
            }
        }
        log.info("Schema initialized");
    }

    /**
     * Returns the shared connection. Callers must not close it; it is managed
     * exclusively by this class.
     *
     * @return the live SQLite connection
     */
    public Connection getConnection() {
        return connection;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                log.info("Database connection closed");
            } catch (SQLException e) {
                log.error("Error closing database connection", e);
            }
        }
    }
}
