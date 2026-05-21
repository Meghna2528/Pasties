package com.pasties.service;

import com.pasties.domain.Snippet;
import com.pasties.repository.SnippetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SnippetServiceTest {

    @Mock
    private SnippetRepository repo;

    private SnippetService service;

    private static final Snippet ADDR_SNIPPET = new Snippet(
            1L, "addr", "123 Main St", "Home address", Instant.now(), Instant.now());

    @BeforeEach
    void setUp() {
        service = new SnippetService(repo);
    }

    // ---- initialize tests ----

    @Test
    void initialize_loadsSnippetsIntoMap() throws Exception {
        when(repo.getAll()).thenReturn(CompletableFuture.completedFuture(List.of(ADDR_SNIPPET)));

        service.initialize().get();

        Optional<Snippet> found = service.findByKey("addr");
        assertTrue(found.isPresent());
        assertEquals("123 Main St", found.get().value());
    }

    @Test
    void initialize_emptyRepo_mapIsEmpty() throws Exception {
        when(repo.getAll()).thenReturn(CompletableFuture.completedFuture(List.of()));

        service.initialize().get();

        assertTrue(service.findByKey("anything").isEmpty());
        assertTrue(service.getAllCached().isEmpty());
    }

    // ---- findByKey tests ----

    @Test
    void findByKey_existingKey_returnsSnippet() throws Exception {
        when(repo.getAll()).thenReturn(CompletableFuture.completedFuture(List.of(ADDR_SNIPPET)));
        service.initialize().get();

        assertTrue(service.findByKey("addr").isPresent());
    }

    @Test
    void findByKey_missingKey_returnsEmpty() throws Exception {
        when(repo.getAll()).thenReturn(CompletableFuture.completedFuture(List.of()));
        service.initialize().get();

        assertTrue(service.findByKey("missing").isEmpty());
    }

    // ---- saveSnippet validation tests ----

    @Test
    void saveSnippet_invalidKey_failsImmediately() {
        CompletableFuture<Void> future = service.saveSnippet("bad key!", "value", null);
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void saveSnippet_nullKey_failsImmediately() {
        CompletableFuture<Void> future = service.saveSnippet(null, "value", null);
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void saveSnippet_blankValue_failsImmediately() {
        CompletableFuture<Void> future = service.saveSnippet("key", "   ", null);
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void saveSnippet_validInputs_persistsAndUpdatesMap() throws Exception {
        Snippet saved = new Snippet(2L, "sig", "Best regards", null, Instant.now(), Instant.now());

        when(repo.save(eq("sig"), eq("Best regards"), isNull()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repo.findByKey("sig"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(saved)));

        service.saveSnippet("sig", "Best regards", null).get();

        Optional<Snippet> found = service.findByKey("sig");
        assertTrue(found.isPresent());
        assertEquals("Best regards", found.get().value());
    }

    @Test
    void saveSnippet_withDashAndUnderscore_keyIsValid() throws Exception {
        Snippet s = new Snippet(3L, "my-key_1", "value", null, Instant.now(), Instant.now());
        when(repo.save(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repo.findByKey("my-key_1"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(s)));

        assertDoesNotThrow(() -> service.saveSnippet("my-key_1", "value", null).get());
    }

    // ---- deleteSnippet tests ----

    @Test
    void deleteSnippet_removesFromMap() throws Exception {
        when(repo.getAll()).thenReturn(CompletableFuture.completedFuture(List.of(ADDR_SNIPPET)));
        service.initialize().get();

        when(repo.deleteByKey("addr")).thenReturn(CompletableFuture.completedFuture(null));
        service.deleteSnippet("addr").get();

        assertTrue(service.findByKey("addr").isEmpty());
    }

    // ---- Listener tests ----

    @Test
    void addChangeListener_immediatelyReceivesCurrentState() throws Exception {
        when(repo.getAll()).thenReturn(CompletableFuture.completedFuture(List.of(ADDR_SNIPPET)));
        service.initialize().get();

        java.util.List<List<Snippet>> received = new java.util.ArrayList<>();
        service.addChangeListener(received::add);

        assertEquals(1, received.size());
        assertEquals(1, received.get(0).size());
    }

    // ---- Snippet record tests ----

    @Test
    void snippet_trigger_returnsPrefixPlusKey() {
        assertEquals("/addr", ADDR_SNIPPET.trigger("/"));
        assertEquals("\\addr", ADDR_SNIPPET.trigger("\\"));
    }
}
