package com.pasties.repository;

import com.pasties.domain.Snippet;
import com.pasties.infrastructure.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistence layer for named snippet definitions.
 *
 * <p>Uses the same single-threaded executor pattern as
 * {@link ClipboardRepository} to serialize all SQLite writes.
 */
public class SnippetRepository {

    private static final Logger log = LoggerFactory.getLogger(SnippetRepository.class);

    private final DatabaseManager db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "db-snippet");
        t.setDaemon(true);
        return t;
    });

    public SnippetRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Returns all snippets ordered alphabetically by key name.
     *
     * @return future containing the full list (may be empty)
     */
    public CompletableFuture<List<Snippet>> getAll() {
        return CompletableFuture.supplyAsync(() -> {
            List<Snippet> list = new ArrayList<>();
            String sql = """
                    SELECT id, key_name, value, description, created_at, updated_at
                    FROM snippets
                    ORDER BY key_name
                    """;
            try (Statement stmt = db.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            } catch (SQLException e) {
                log.error("Error loading snippets", e);
                throw new CompletionException(e);
            }
            return list;
        }, executor);
    }

    /**
     * Finds a single snippet by its key name.
     *
     * @param keyName the snippet key (without prefix)
     * @return future containing the snippet, or empty if not found
     */
    public CompletableFuture<Optional<Snippet>> findByKey(String keyName) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                    SELECT id, key_name, value, description, created_at, updated_at
                    FROM snippets WHERE lower(key_name) = lower(?)
                    """;
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, keyName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                }
            } catch (SQLException e) {
                log.error("Error finding snippet '{}'", keyName, e);
            }
            return Optional.empty();
        }, executor);
    }

    /**
     * Inserts or updates a snippet (upsert on {@code key_name}).
     *
     * @param keyName     snippet key
     * @param value       expansion text
     * @param description optional human label (may be null)
     * @return future completing when the save is committed
     */
    public CompletableFuture<Void> save(String keyName, String value, String description) {
        return CompletableFuture.runAsync(() -> {
            long now = Instant.now().toEpochMilli();
            String update = """
                    UPDATE snippets
                    SET key_name = ?, value = ?, description = ?, updated_at = ?
                    WHERE lower(key_name) = lower(?)
                    """;
            String insert = """
                    INSERT INTO snippets (key_name, value, description, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = db.getConnection().prepareStatement(update)) {
                ps.setString(1, keyName);
                ps.setString(2, value);
                ps.setString(3, description);
                ps.setLong(4, now);
                ps.setString(5, keyName);
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    try (PreparedStatement insertPs = db.getConnection().prepareStatement(insert)) {
                        insertPs.setString(1, keyName);
                        insertPs.setString(2, value);
                        insertPs.setString(3, description);
                        insertPs.setLong(4, now);
                        insertPs.setLong(5, now);
                        insertPs.executeUpdate();
                    }
                }
                log.info("Snippet '{}' saved", keyName);
            } catch (SQLException e) {
                log.error("Error saving snippet '{}'", keyName, e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Deletes a snippet by its key name.
     *
     * @param keyName snippet key to remove
     * @return future completing when the deletion is done
     */
    public CompletableFuture<Void> deleteByKey(String keyName) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = db.getConnection()
                    .prepareStatement("DELETE FROM snippets WHERE lower(key_name) = lower(?)")) {
                ps.setString(1, keyName);
                ps.executeUpdate();
                log.info("Snippet '{}' deleted", keyName);
            } catch (SQLException e) {
                log.error("Error deleting snippet '{}'", keyName, e);
            }
        }, executor);
    }

    /** Shuts down the database executor. */
    public void shutdown() {
        executor.shutdown();
    }

    // ---- Helpers ----

    private Snippet mapRow(ResultSet rs) throws SQLException {
        return new Snippet(
                rs.getLong("id"),
                rs.getString("key_name"),
                rs.getString("value"),
                rs.getString("description"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at"))
        );
    }
}
