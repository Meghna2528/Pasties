package com.pasties.repository;

import com.pasties.domain.ClipboardEntry;
import com.pasties.infrastructure.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistence layer for clipboard history entries.
 *
 * <p>All database operations are submitted to a dedicated single-threaded
 * {@link ExecutorService} named {@code db-clipboard}. This serialises access
 * to the shared SQLite connection, preventing concurrent-write issues without
 * requiring a connection pool.
 *
 * <p>Operations return {@link CompletableFuture} so callers can chain
 * asynchronously without blocking the Event Dispatch Thread or the
 * JNativeHook callback thread.
 */
public class ClipboardRepository {

    private static final Logger log = LoggerFactory.getLogger(ClipboardRepository.class);

    private final DatabaseManager db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "db-clipboard");
        t.setDaemon(true);
        return t;
    });

    public ClipboardRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Inserts a new clipboard entry or, if {@code contentHash} already exists,
     * refreshes its {@code created_at} timestamp and increments {@code access_count}.
     * After the upsert, entries beyond {@code maxHistorySize} are pruned.
     *
     * @param content        raw clipboard text
     * @param contentHash    SHA-256 hex of content (for deduplication)
     * @param maxHistorySize maximum number of entries to keep
     * @return future completing when the upsert and prune are committed
     */
    public CompletableFuture<Void> upsert(String content, String contentHash, int maxHistorySize, int ttlDays) {
        return CompletableFuture.runAsync(() -> {
            Connection conn = db.getConnection();
            try {
                conn.setAutoCommit(false);

                String upsertSql = """
                        INSERT INTO clipboard_entries (content, content_hash, created_at, access_count)
                        VALUES (?, ?, ?, 1)
                        ON CONFLICT(content_hash) DO UPDATE SET
                            created_at   = excluded.created_at,
                            access_count = clipboard_entries.access_count + 1
                        """;
                try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                    ps.setString(1, content);
                    ps.setString(2, contentHash);
                    ps.setLong(3, Instant.now().toEpochMilli());
                    ps.executeUpdate();
                }

                // Prune: keep only the most-recent maxHistorySize rows
                String pruneSql = """
                        DELETE FROM clipboard_entries
                        WHERE id NOT IN (
                            SELECT id FROM clipboard_entries
                            ORDER BY created_at DESC
                            LIMIT ?
                        )
                        """;
                try (PreparedStatement ps = conn.prepareStatement(pruneSql)) {
                    ps.setInt(1, maxHistorySize);
                    ps.executeUpdate();
                }

                // Prune: remove entries older than ttlDays
                if (ttlDays > 0) {
                    long cutoff = Instant.now().toEpochMilli() - ((long) ttlDays * 86_400_000L);
                    String ttlSql = "DELETE FROM clipboard_entries WHERE created_at < ?";
                    try (PreparedStatement ps = conn.prepareStatement(ttlSql)) {
                        ps.setLong(1, cutoff);
                        ps.executeUpdate();
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                rollbackQuietly(conn);
                log.error("Error upserting clipboard entry", e);
                throw new CompletionException(e);
            } finally {
                setAutoCommitQuietly(conn, true);
            }
        }, executor);
    }

    /**
     * Returns the most recent {@code limit} entries ordered by
     * {@code created_at DESC}.
     *
     * @param limit maximum number of entries to return
     * @return future containing the list of entries (may be empty)
     */
    public CompletableFuture<List<ClipboardEntry>> getRecent(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<ClipboardEntry> entries = new ArrayList<>();
            String sql = """
                    SELECT id, content, content_hash, created_at, access_count
                    FROM clipboard_entries
                    ORDER BY created_at DESC
                    LIMIT ?
                    """;
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(mapRow(rs));
                    }
                }
            } catch (SQLException e) {
                log.error("Error loading clipboard history", e);
                throw new CompletionException(e);
            }
            return entries;
        }, executor);
    }

    /**
     * Deletes a single entry by primary key.
     *
     * @param id the entry id to delete
     * @return future completing when the deletion is done
     */
    public CompletableFuture<Void> deleteById(long id) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = db.getConnection()
                    .prepareStatement("DELETE FROM clipboard_entries WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Error deleting clipboard entry id={}", id, e);
            }
        }, executor);
    }

    /**
     * Removes all clipboard history entries.
     *
     * @return future completing when the table is empty
     */
    public CompletableFuture<Void> clearAll() {
        return CompletableFuture.runAsync(() -> {
            try (Statement stmt = db.getConnection().createStatement()) {
                stmt.execute("DELETE FROM clipboard_entries");
                log.info("Clipboard history cleared");
            } catch (SQLException e) {
                log.error("Error clearing clipboard history", e);
            }
        }, executor);
    }

    /** Shuts down the database executor; waits for in-flight tasks. */
    public void shutdown() {
        executor.shutdown();
    }

    // ---- Helpers ----

    private ClipboardEntry mapRow(ResultSet rs) throws SQLException {
        return new ClipboardEntry(
                rs.getLong("id"),
                rs.getString("content"),
                rs.getString("content_hash"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                rs.getInt("access_count")
        );
    }

    private void rollbackQuietly(Connection conn) {
        try { conn.rollback(); } catch (SQLException ignored) {}
    }

    private void setAutoCommitQuietly(Connection conn, boolean autoCommit) {
        try { conn.setAutoCommit(autoCommit); } catch (SQLException ignored) {}
    }
}
