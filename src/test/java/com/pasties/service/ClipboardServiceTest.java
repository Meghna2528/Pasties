package com.pasties.service;

import com.pasties.domain.AppConfig;
import com.pasties.domain.ClipboardEntry;
import com.pasties.repository.ClipboardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClipboardServiceTest {

    @Mock
    private ClipboardRepository repo;

    private AppConfig config;
    private ClipboardService service;

    @BeforeEach
    void setUp() {
        config = new AppConfig();
        service = new ClipboardService(repo, config);
    }

    // ---- SHA-256 tests ----

    @Test
    void sha256_sameInput_returnsSameHash() {
        String hash1 = ClipboardService.sha256("hello");
        String hash2 = ClipboardService.sha256("hello");
        assertEquals(hash1, hash2);
    }

    @Test
    void sha256_differentInput_returnsDifferentHash() {
        assertNotEquals(
                ClipboardService.sha256("hello"),
                ClipboardService.sha256("world")
        );
    }

    @Test
    void sha256_returnsSixtyFourCharHex() {
        String hash = ClipboardService.sha256("test");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    // ---- onNewContent tests ----

    @Test
    void onNewContent_blank_isIgnored() {
        service.onNewContent("   ");
        verifyNoInteractions(repo);
    }

    @Test
    void onNewContent_null_isIgnored() {
        service.onNewContent(null);
        verifyNoInteractions(repo);
    }

    @Test
    void onNewContent_validContent_callsUpsertAndGetRecent() throws Exception {
        String content = "Hello, world!";
        ClipboardEntry fakeEntry = new ClipboardEntry(1L, content,
                ClipboardService.sha256(content), Instant.now(), 1);

        when(repo.upsert(eq(content), anyString(), eq(config.getMaxHistorySize()), eq(config.getEntryTtlDays())))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repo.getRecent(anyInt()))
                .thenReturn(CompletableFuture.completedFuture(List.of(fakeEntry)));

        service.onNewContent(content);

        // Allow the async chain to complete
        Thread.sleep(200);

        verify(repo).upsert(eq(content), anyString(), eq(config.getMaxHistorySize()), eq(config.getEntryTtlDays()));
        verify(repo).getRecent(anyInt());
    }

    @Test
    void onNewContent_populatesCache() throws Exception {
        String content = "cached content";
        ClipboardEntry entry = new ClipboardEntry(1L, content,
                ClipboardService.sha256(content), Instant.now(), 1);

        when(repo.upsert(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repo.getRecent(anyInt()))
                .thenReturn(CompletableFuture.completedFuture(List.of(entry)));

        service.onNewContent(content);
        Thread.sleep(200);

        List<ClipboardEntry> cached = service.getCachedHistory();
        assertEquals(1, cached.size());
        assertEquals(content, cached.get(0).content());
    }

    // ---- Listener tests ----

    @Test
    void addChangeListener_immediatelyNotifiedWithCurrentCache() {
        List<List<ClipboardEntry>> received = new CopyOnWriteArrayList<>();
        service.addChangeListener(received::add);

        assertEquals(1, received.size(), "Listener should be called immediately");
        assertTrue(received.get(0).isEmpty(), "Initial cache should be empty");
    }

    @Test
    void addChangeListener_notifiedOnNewContent() throws Exception {
        List<List<ClipboardEntry>> received = new CopyOnWriteArrayList<>();
        service.addChangeListener(received::add);

        String content = "listener test";
        ClipboardEntry entry = new ClipboardEntry(1L, content,
                ClipboardService.sha256(content), Instant.now(), 1);

        when(repo.upsert(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repo.getRecent(anyInt()))
                .thenReturn(CompletableFuture.completedFuture(List.of(entry)));

        service.onNewContent(content);
        Thread.sleep(200);

        assertTrue(received.size() >= 2, "Listener should be called again after new content");
    }

    // ---- clearHistory tests ----

    @Test
    void clearHistory_callsRepoAndClearsCache() throws Exception {
        when(repo.clearAll()).thenReturn(CompletableFuture.completedFuture(null));

        service.clearHistory().get();

        verify(repo).clearAll();
        assertTrue(service.getCachedHistory().isEmpty());
    }

    // ---- ClipboardEntry.preview tests ----

    @Test
    void preview_shortContent_returnedAsIs() {
        ClipboardEntry e = new ClipboardEntry(1L, "short", "hash", Instant.now(), 1);
        assertEquals("short", e.preview(80));
    }

    @Test
    void preview_longContent_truncatedWithEllipsis() {
        String longText = "a".repeat(100);
        ClipboardEntry e = new ClipboardEntry(1L, longText, "hash", Instant.now(), 1);
        String preview = e.preview(20);
        assertEquals(20, preview.length());
        assertEquals('\u2026', preview.charAt(19));
    }

    @Test
    void preview_newlines_collapsedToSpace() {
        ClipboardEntry e = new ClipboardEntry(1L, "line1\nline2", "hash", Instant.now(), 1);
        assertEquals("line1 line2", e.preview(80));
    }
}
