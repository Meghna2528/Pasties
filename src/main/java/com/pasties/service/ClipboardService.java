package com.pasties.service;

import com.pasties.domain.AppConfig;
import com.pasties.domain.ClipboardEntry;
import com.pasties.repository.ClipboardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Business logic for clipboard history management.
 *
 * <p>Maintains an in-memory cache ({@link CopyOnWriteArrayList}) of the most
 * recent entries (up to {@code max_history_size}) so UI surfaces can display
 * without a database round-trip. The cache is kept in sync after every
 * {@link #onNewContent(String)} call.
 *
 * <p>Change listeners registered via {@link #addChangeListener(Consumer)} are
 * notified on the repository's DB executor thread. UI listeners must dispatch
 * to the Event Dispatch Thread themselves (e.g. via
 * {@code SwingUtilities.invokeLater}).
 */
public class ClipboardService {

    private static final Logger log = LoggerFactory.getLogger(ClipboardService.class);
    // Cache size is driven by config.getMaxHistorySize() at runtime

    private final ClipboardRepository repo;
    private final AppConfig config;

    private final List<ClipboardEntry> cache = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<ClipboardEntry>>> changeListeners = new CopyOnWriteArrayList<>();

    public ClipboardService(ClipboardRepository repo, AppConfig config) {
        this.repo = repo;
        this.config = config;
    }

    /**
     * Called by {@link com.pasties.hook.ClipboardMonitor} when new text is
     * detected on the system clipboard. Thread-safe: may be called from any
     * thread.
     *
     * @param content the raw clipboard text (non-null, non-blank)
     */
    public void onNewContent(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        String hash = sha256(content);

        repo.upsert(content, hash, config.getMaxHistorySize(), config.getEntryTtlDays())
                .thenCompose(v -> repo.getRecent(config.getMaxHistorySize()))
                .thenAccept(entries -> {
                    cache.clear();
                    cache.addAll(entries);
                    notifyListeners();
                })
                .exceptionally(ex -> {
                    log.error("Error persisting clipboard content", ex);
                    return null;
                });
    }

    /**
     * Returns the current in-memory cache as an unmodifiable snapshot.
     * Safe to call from any thread; no DB access occurs.
     *
     * @return unmodifiable list of recent entries (most-recent first)
     */
    public List<ClipboardEntry> getCachedHistory() {
        return Collections.unmodifiableList(cache);
    }

    /**
     * Loads history from the database asynchronously.
     *
     * @param limit maximum number of entries to load
     * @return future containing the list (most-recent first)
     */
    public CompletableFuture<List<ClipboardEntry>> loadHistory(int limit) {
        return repo.getRecent(limit);
    }

    /**
     * Registers a listener that is called whenever the history changes.
     * The listener is immediately called with the current cached state.
     *
     * @param listener consumer accepting the updated entry list
     */
    public void addChangeListener(Consumer<List<ClipboardEntry>> listener) {
        changeListeners.add(listener);
        listener.accept(Collections.unmodifiableList(new ArrayList<>(cache)));
    }

    /**
     * Removes a single entry from history by id and refreshes the cache.
     *
     * @param id the entry id to remove
     * @return future completing when the deletion and cache refresh are done
     */
    public CompletableFuture<Void> deleteEntry(long id) {
        return repo.deleteById(id)
                .thenCompose(v -> repo.getRecent(config.getMaxHistorySize()))
                .thenAccept(entries -> {
                    cache.clear();
                    cache.addAll(entries);
                    notifyListeners();
                });
    }

    /**
     * Clears all clipboard history and resets the cache.
     *
     * @return future completing when the operation is done
     */
    public CompletableFuture<Void> clearHistory() {
        return repo.clearAll().thenAccept(v -> {
            cache.clear();
            notifyListeners();
        });
    }

    // ---- Helpers ----

    private void notifyListeners() {
        List<ClipboardEntry> snapshot = Collections.unmodifiableList(new ArrayList<>(cache));
        for (Consumer<List<ClipboardEntry>> listener : changeListeners) {
            try {
                listener.accept(snapshot);
            } catch (Exception e) {
                log.error("Error in clipboard change listener", e);
            }
        }
    }

    /**
     * Computes the SHA-256 hex digest of {@code input} encoded as UTF-8.
     *
     * @param input the string to hash
     * @return 64-character lowercase hex string
     */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java spec; this cannot happen in practice
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
