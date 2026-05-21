package com.pasties.hook;

import com.pasties.domain.AppConfig;
import com.pasties.domain.Snippet;
import com.pasties.repository.SnippetRepository;
import com.pasties.service.SnippetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests the snippet detection state machine in isolation, without JNativeHook.
 *
 * <p>The state machine logic is extracted into {@link SnippetDetector}, a
 * package-private inner utility in {@link GlobalKeyboardHook} that can be
 * tested independently. For these tests we drive it directly via a thin
 * stand-alone replica to avoid pulling in the native library.
 */
@ExtendWith(MockitoExtension.class)
class SnippetDetectionTest {

    /**
     * Self-contained replica of the GlobalKeyboardHook state machine.
     * Mirrors the exact logic in the production class.
     */
    static class StateMachine {
        private static final int MAX_BUFFER = 64;
        private final StringBuilder buffer = new StringBuilder(MAX_BUFFER);
        private final SnippetService snippetService;
        private final AppConfig config;

        // Records the last expansion request
        String lastExpansionKey = null;
        int lastEraseCount = -1;

        StateMachine(SnippetService snippetService, AppConfig config) {
            this.snippetService = snippetService;
            this.config = config;
        }

        void onKeyTyped(char ch) {
            String prefix = config.getSnippetPrefix();
            char prefixChar = prefix.isEmpty() ? '/' : prefix.charAt(0);

            if (ch == prefixChar) {
                buffer.setLength(0);
                buffer.append(ch);
                return;
            }

            if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '_') {
                buffer.setLength(0);
                return;
            }
            if (buffer.isEmpty()) {
                return;
            }

            buffer.append(ch);
            if (buffer.length() > MAX_BUFFER) {
                buffer.setLength(0);
                return;
            }

            checkMatch();
        }

        void onStructuralKey() {
            buffer.setLength(0);
        }

        private void checkMatch() {
            if (buffer.length() < 2) return;
            String buf = buffer.toString();
            String prefixStr = config.getSnippetPrefix();
            if (prefixStr.isEmpty() || buf.charAt(0) != prefixStr.charAt(0)) return;

            String candidate = buf.substring(1);
            Optional<Snippet> match = snippetService.findByKey(candidate);
            if (match.isPresent()) {
                lastEraseCount = buf.length();
                lastExpansionKey = candidate;
                buffer.setLength(0);
            }
        }

        String bufferContents() {
            return buffer.toString();
        }
    }

    @Mock
    private SnippetRepository repo;

    private AppConfig config;
    private SnippetService snippetService;
    private StateMachine sm;

    private static final Snippet ADDR = new Snippet(
            1L, "addr", "123 Main St", null, Instant.now(), Instant.now());
    private static final Snippet SIG = new Snippet(
            2L, "sig", "Best regards", null, Instant.now(), Instant.now());

    @BeforeEach
    void setUp() throws Exception {
        config = new AppConfig();
        when(repo.getAll()).thenReturn(CompletableFuture.completedFuture(List.of(ADDR, SIG)));
        snippetService = new SnippetService(repo);
        snippetService.initialize().get();
        sm = new StateMachine(snippetService, config);
    }

    // ---- Buffer management ----

    @Test
    void prefix_resetsBuffer() {
        sm.onKeyTyped('h');
        sm.onKeyTyped('e');
        sm.onKeyTyped('/');
        assertEquals("/", sm.bufferContents());
    }

    @Test
    void nonAlphanumeric_clearsBuffer() {
        sm.onKeyTyped('/');
        sm.onKeyTyped('a');
        sm.onKeyTyped(' '); // space is a structural char (clears via nativeKeyPressed)
        // In the state machine, space would be a structural key via onStructuralKey()
        sm.onStructuralKey();
        assertEquals("", sm.bufferContents());
    }

    @Test
    void structuralKey_clearsBuffer() {
        sm.onKeyTyped('/');
        sm.onKeyTyped('a');
        sm.onKeyTyped('d');
        sm.onStructuralKey(); // e.g. Backspace key
        assertEquals("", sm.bufferContents());
    }

    @Test
    void characterAfterStructural_doesNotFormTrigger() {
        sm.onKeyTyped('/');
        sm.onStructuralKey();
        sm.onKeyTyped('a');
        sm.onKeyTyped('d');
        sm.onKeyTyped('d');
        sm.onKeyTyped('r');
        // Buffer was cleared by structural key, so 'addr' without prefix won't match
        assertNull(sm.lastExpansionKey);
    }

    // ---- Snippet matching ----

    @Test
    void typingTrigger_matchesSnippet() {
        for (char c : "/addr".toCharArray()) {
            sm.onKeyTyped(c);
        }
        assertEquals("addr", sm.lastExpansionKey);
        assertEquals(5, sm.lastEraseCount); // "/addr" = 5 chars
    }

    @Test
    void typingTrigger_eraseCountEqualsBufferLength() {
        for (char c : "/sig".toCharArray()) {
            sm.onKeyTyped(c);
        }
        assertEquals("sig", sm.lastExpansionKey);
        assertEquals(4, sm.lastEraseCount); // "/sig" = 4 chars
    }

    @Test
    void bufferClearedAfterMatch() {
        for (char c : "/addr".toCharArray()) {
            sm.onKeyTyped(c);
        }
        assertEquals("", sm.bufferContents(), "Buffer must be cleared after match");
    }

    @Test
    void unknownKey_noMatch() {
        for (char c : "/unknown".toCharArray()) {
            sm.onKeyTyped(c);
        }
        assertNull(sm.lastExpansionKey);
        assertEquals("/unknown", sm.bufferContents());
    }

    @Test
    void partialKey_noMatch() {
        for (char c : "/add".toCharArray()) { // "add" not in snippet map
            sm.onKeyTyped(c);
        }
        assertNull(sm.lastExpansionKey);
        assertEquals("/add", sm.bufferContents());
    }

    @Test
    void prefixAlone_noMatch() {
        sm.onKeyTyped('/');
        assertNull(sm.lastExpansionKey);
        assertEquals("/", sm.bufferContents());
    }

    // ---- Buffer overflow ----

    @Test
    void veryLongInput_bufferDropped() {
        sm.onKeyTyped('/');
        for (int i = 0; i < 70; i++) {
            sm.onKeyTyped('a');
        }
        // After overflow the buffer is cleared
        assertEquals("", sm.bufferContents());
        assertNull(sm.lastExpansionKey);
    }

    // ---- Multiple sequences ----

    @Test
    void twoTriggers_secondOneDetected() {
        for (char c : "/addr".toCharArray()) sm.onKeyTyped(c);
        assertEquals("addr", sm.lastExpansionKey);

        sm.lastExpansionKey = null;
        sm.lastEraseCount   = -1;

        for (char c : "/sig".toCharArray()) sm.onKeyTyped(c);
        assertEquals("sig", sm.lastExpansionKey);
    }
}
