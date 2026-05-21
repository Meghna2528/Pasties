package com.pasties.repository;

import com.pasties.domain.AppConfig;
import com.pasties.infrastructure.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Loads and persists {@link AppConfig} from/to the {@code config} table.
 *
 * <p>Config changes are rare (user-initiated via Preferences dialog), so
 * {@link #load()} blocks briefly at startup while all other operations are
 * non-blocking futures. The same single-threaded executor pattern as the
 * other repositories is used for connection safety.
 */
public class ConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(ConfigRepository.class);

    private final DatabaseManager db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "db-config");
        t.setDaemon(true);
        return t;
    });

    public ConfigRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Loads all configuration keys from the database and returns a populated
     * {@link AppConfig}. Blocks for up to 5 seconds; returns defaults on timeout.
     *
     * @return populated AppConfig (never null)
     */
    public AppConfig load() {
        try {
            return CompletableFuture.supplyAsync(() -> {
                AppConfig cfg = new AppConfig();
                try (Statement stmt = db.getConnection().createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT key, value FROM config")) {
                    while (rs.next()) {
                        applyKey(cfg, rs.getString("key"), rs.getString("value"));
                    }
                } catch (SQLException e) {
                    log.error("Error loading config; using defaults", e);
                }
                return cfg;
            }, executor).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Config load timed out; using defaults", e);
            return new AppConfig();
        }
    }

    /**
     * Persists a single config key-value pair (upsert).
     *
     * @param key   config key
     * @param value config value
     * @return future completing when the write is committed
     */
    public CompletableFuture<Void> save(String key, String value) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                    INSERT INTO config (key, value) VALUES (?, ?)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value
                    """;
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Error saving config key '{}' = '{}'", key, value, e);
            }
        }, executor);
    }

    /**
     * Persists all fields of an {@link AppConfig} in a single batch transaction.
     *
     * @param cfg the config object to persist
     * @return future completing when all keys are committed
     */
    public CompletableFuture<Void> saveAll(AppConfig cfg) {
        return CompletableFuture.runAsync(() -> {
            Map<String, String> entries = new HashMap<>();
            entries.put("max_history_size",   String.valueOf(cfg.getMaxHistorySize()));
            entries.put("hotkey_modifiers",   cfg.getHotkeyModifiers());
            entries.put("hotkey_key",         cfg.getHotkeyKey());
            entries.put("start_on_login",     String.valueOf(cfg.isStartOnLogin()));
            entries.put("snippet_prefix",     cfg.getSnippetPrefix());
            entries.put("recent_menu_size",   String.valueOf(cfg.getRecentMenuSize()));
            entries.put("popup_history_size", String.valueOf(cfg.getPopupHistorySize()));
            entries.put("entry_ttl_days",     String.valueOf(cfg.getEntryTtlDays()));
            String sql = """
                    INSERT INTO config (key, value) VALUES (?, ?)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value
                    """;
            Connection conn = db.getConnection();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (Map.Entry<String, String> e : entries.entrySet()) {
                        ps.setString(1, e.getKey());
                        ps.setString(2, e.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    conn.commit();
                    log.info("AppConfig persisted ({} keys)", entries.size());
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                log.error("Error persisting AppConfig", e);
            }
        }, executor);
    }

    /** Shuts down the database executor. */
    public void shutdown() {
        executor.shutdown();
    }

    // ---- Helpers ----

    private void applyKey(AppConfig cfg, String key, String value) {
        try {
            switch (key) {
                case "max_history_size" -> cfg.setMaxHistorySize(Integer.parseInt(value));
                case "hotkey_modifiers" -> cfg.setHotkeyModifiers(value);
                case "hotkey_key"       -> cfg.setHotkeyKey(value);
                case "start_on_login"   -> cfg.setStartOnLogin(Boolean.parseBoolean(value));
                case "snippet_prefix"   -> cfg.setSnippetPrefix(value);
                case "recent_menu_size"   -> cfg.setRecentMenuSize(Integer.parseInt(value));
                case "popup_history_size" -> cfg.setPopupHistorySize(Integer.parseInt(value));
                case "entry_ttl_days"     -> cfg.setEntryTtlDays(Integer.parseInt(value));
                default                   -> log.debug("Ignoring unknown config key: {}", key);
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid numeric config value for key '{}': '{}'", key, value);
        }
    }
}
