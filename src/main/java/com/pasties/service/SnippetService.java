package com.pasties.service;

import com.pasties.domain.Snippet;
import com.pasties.repository.SnippetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Business logic for snippet management.
 *
 * <p>Maintains a {@link ConcurrentHashMap} keyed by {@code keyName} so that
 * {@link #findByKey(String)} is a single lock-free map lookup — critical
 * because it is called on the JNativeHook native callback thread after every
 * typed character.
 *
 * <p>All write operations (save, delete) go through the repository and then
 * update the in-memory map atomically, keeping DB and cache consistent.
 */
public class SnippetService {

    private static final Logger log = LoggerFactory.getLogger(SnippetService.class);
    private static final String KEY_PATTERN = "[a-zA-Z0-9_-]+";

    private final SnippetRepository repo;
    private final ConcurrentHashMap<String, Snippet> snippetMap = new ConcurrentHashMap<>();
    private final List<Consumer<List<Snippet>>> changeListeners = new CopyOnWriteArrayList<>();

    public SnippetService(SnippetRepository repo) {
        this.repo = repo;
    }

    /**
     * Loads all snippets from the database into the in-memory map.
     * Must be called once at startup (after the DB is initialised) and
     * awaited before registering the keyboard hook.
     *
     * @return future completing when the map is fully populated
     */
    public CompletableFuture<Void> initialize() {
        return repo.getAll().thenAccept(snippets -> {
            snippetMap.clear();
            snippets.forEach(s -> snippetMap.put(s.keyName(), s));
            log.info("SnippetService initialised with {} snippet(s)", snippetMap.size());
        });
    }

    /**
     * Looks up a snippet by key name. Zero-latency: reads from the
     * {@link ConcurrentHashMap} without any I/O.
     *
     * <p>This method is safe to call from the JNativeHook callback thread.
     *
     * @param keyName the snippet key (without prefix)
     * @return the matching snippet, or empty if not found
     */
    public Optional<Snippet> findByKey(String keyName) {
        return Optional.ofNullable(snippetMap.get(keyName));
    }

    /**
     * Returns a snapshot of all currently loaded snippets.
     *
     * @return unmodifiable list ordered arbitrarily (ConcurrentHashMap ordering)
     */
    public List<Snippet> getAllCached() {
        return Collections.unmodifiableList(new ArrayList<>(snippetMap.values()));
    }

    /**
     * Validates the key, persists the snippet, updates the in-memory map,
     * and notifies all change listeners.
     *
     * @param keyName     snippet key — must match {@code [a-zA-Z0-9_-]+}
     * @param value       expansion text — must not be blank
     * @param description optional human-readable label (may be null)
     * @return future completing when persistence and cache update are done;
     *         fails with {@link IllegalArgumentException} on validation error
     */
    public CompletableFuture<Void> saveSnippet(String keyName, String value, String description) {
        if (keyName == null || !keyName.matches(KEY_PATTERN)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Key must match [a-zA-Z0-9_-]+ but was: " + keyName));
        }
        if (value == null || value.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Snippet value must not be blank"));
        }

        return repo.save(keyName, value, description)
                .thenCompose(v -> repo.findByKey(keyName))
                .thenAccept(opt -> opt.ifPresent(s -> {
                    snippetMap.put(s.keyName(), s);
                    notifyListeners();
                }));
    }

    /**
     * Removes a snippet by key, updates the in-memory map, and notifies listeners.
     *
     * @param keyName the key to delete
     * @return future completing when the deletion is done
     */
    public CompletableFuture<Void> deleteSnippet(String keyName) {
        return repo.deleteByKey(keyName).thenAccept(v -> {
            snippetMap.remove(keyName);
            notifyListeners();
        });
    }

    /**
     * Registers a listener called whenever the snippet collection changes.
     * The listener is immediately invoked with the current cached state.
     *
     * @param listener consumer accepting the updated snippet list
     */
    public void addChangeListener(Consumer<List<Snippet>> listener) {
        changeListeners.add(listener);
        listener.accept(getAllCached());
    }

    // ---- Helpers ----

    private void notifyListeners() {
        List<Snippet> snapshot = getAllCached();
        for (Consumer<List<Snippet>> listener : changeListeners) {
            try {
                listener.accept(snapshot);
            } catch (Exception e) {
                log.error("Error in snippet change listener", e);
            }
        }
    }
}
