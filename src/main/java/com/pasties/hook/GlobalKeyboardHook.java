package com.pasties.hook;

import com.pasties.domain.AppConfig;
import com.pasties.domain.Snippet;
import com.pasties.service.PasteService;
import com.pasties.service.SnippetService;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Registers a global keyboard hook via JNativeHook and handles two concerns:
 *
 * <h2>1. Hotkey detection</h2>
 * <p>Detects the configurable hotkey (default: Ctrl+Shift+S) and dispatches a
 * callback to show the clipboard history popup on the EDT.
 *
 * <h2>2. Snippet trigger detection (state machine)</h2>
 * <p>Maintains a rolling {@link StringBuilder} buffer of recently typed
 * characters. When the buffer ends with {@code /<keyName>} for any known snippet,
 * the trigger fires:
 * <ol>
 *   <li>The buffer is cleared immediately (prevents double-fire).</li>
 *   <li>{@link PasteService#expandSnippet(int, String)} is submitted to
 *       the {@code paste-executor} thread (never blocks the hook thread).</li>
 * </ol>
 *
 * <h2>Thread model</h2>
 * <p>JNativeHook delivers all events on its own native dispatch thread (single-
 * threaded). The {@code typingBuffer} and modifier-key state variables are
 * accessed exclusively on that thread — no synchronisation is required.
 * Heavy Robot operations are offloaded to {@code paste-executor}.
 *
 * <h2>Logging suppression</h2>
 * <p>JNativeHook uses {@code java.util.logging} internally. The static
 * initialiser silences it to avoid noise in production logs.
 */
public class GlobalKeyboardHook implements NativeKeyListener {

    private static final Logger log = LoggerFactory.getLogger(GlobalKeyboardHook.class);
    private static final int MAX_BUFFER_LENGTH = 64;

    static {
        // Silence JNativeHook's verbose internal logger
        java.util.logging.Logger jnhLog =
                java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName());
        jnhLog.setLevel(Level.WARNING);
        jnhLog.setUseParentHandlers(false);
    }

    private final SnippetService snippetService;
    private final PasteService pasteService;
    private final AppConfig config;
    private final Runnable onHotkeyPressed;

    // Accessed only on the JNativeHook thread — no synchronisation needed
    private final StringBuilder typingBuffer = new StringBuilder(MAX_BUFFER_LENGTH);
    private boolean ctrlDown  = false;
    private boolean metaDown  = false; // Command (⌘) key — treated as equivalent to Ctrl for hotkeys
    private boolean shiftDown = false;
    private boolean altDown   = false;

    /** Single-threaded executor for Robot-based paste operations. */
    private final ExecutorService pasteExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "paste-executor");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param snippetService  used for fast in-memory key lookup
     * @param pasteService    used to expand snippets and paste clipboard items
     * @param config          runtime configuration (prefix, hotkey, delays)
     * @param onHotkeyPressed called on the EDT when the global hotkey fires
     */
    public GlobalKeyboardHook(SnippetService snippetService,
                               PasteService pasteService,
                               AppConfig config,
                               Runnable onHotkeyPressed) {
        this.snippetService = snippetService;
        this.pasteService = pasteService;
        this.config = config;
        this.onHotkeyPressed = onHotkeyPressed;
    }

    /**
     * Registers the native hook with the OS. Requires Input Monitoring permission
     * on macOS 10.15+. Throws {@link RuntimeException} if registration fails.
     */
    public void register() {
        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            log.info("Global keyboard hook registered");
        } catch (NativeHookException e) {
            log.error("Failed to register global keyboard hook — Input Monitoring permission may be missing", e);
            throw new RuntimeException("Cannot register keyboard hook", e);
        }
    }

    /**
     * Unregisters the native hook and shuts down the paste executor.
     */
    public void unregister() {
        try {
            GlobalScreen.removeNativeKeyListener(this);
            GlobalScreen.unregisterNativeHook();
            log.info("Global keyboard hook unregistered");
        } catch (NativeHookException e) {
            log.error("Error unregistering keyboard hook", e);
        }
        pasteExecutor.shutdown();
        try {
            if (!pasteExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                pasteExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pasteExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---- NativeKeyListener implementation ----

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        int code = e.getKeyCode();
        syncModifierState(e);

        // Track modifier state. Modifier-only events do not need further processing.
        if (code == NativeKeyEvent.VC_CONTROL) { ctrlDown  = true; return; }
        if (code == NativeKeyEvent.VC_META)    { metaDown  = true; return; }
        if (code == NativeKeyEvent.VC_SHIFT)   { shiftDown = true; return; }
        if (code == NativeKeyEvent.VC_ALT)     { altDown   = true; return; }

        if (ctrlDown || metaDown || shiftDown || altDown) {
            log.info("Modified key pressed — key={}, keyCode={}, eventModifiers={}, state={}",
                    NativeKeyEvent.getKeyText(code),
                    code,
                    NativeKeyEvent.getModifiersText(e.getModifiers()),
                    modifierStateForLog());
        }

        // Detect configurable hotkey (default: Ctrl+Shift+S)
        if (isHotkeyPressed(e)) {
            log.info("Global hotkey detected: {}+{}", config.getHotkeyModifiers(), config.getHotkeyKey());
            SwingUtilities.invokeLater(onHotkeyPressed);
            return;
        }

        if (isConfiguredTriggerKey(e)) {
            log.info("Hotkey trigger key pressed but modifiers did not match — key={}, eventModifiers={}, state={}",
                    NativeKeyEvent.getKeyText(code),
                    NativeKeyEvent.getModifiersText(e.getModifiers()),
                    modifierStateForLog());
        }

        // Any structural key resets the snippet detection buffer
        if (isClearingKey(code)) {
            typingBuffer.setLength(0);
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        int code = e.getKeyCode();
        syncModifierState(e);
        if (code == NativeKeyEvent.VC_CONTROL) ctrlDown  = false;
        if (code == NativeKeyEvent.VC_META)    metaDown  = false;
        if (code == NativeKeyEvent.VC_SHIFT)   shiftDown = false;
        if (code == NativeKeyEvent.VC_ALT)     altDown   = false;
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        char ch = e.getKeyChar();
        String prefix = config.getSnippetPrefix();
        char prefixChar = prefix.isEmpty() ? '/' : prefix.charAt(0);

        // The prefix character resets the buffer and starts a new potential snippet trigger
        if (ch == prefixChar) {
            typingBuffer.setLength(0);
            typingBuffer.append(ch);
            return;
        }

        // Non-trigger characters: only alphanumeric, dash, underscore extend the buffer.
        // If the buffer is empty (no active prefix sequence), discard entirely.
        if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '_') {
            typingBuffer.setLength(0);
            return;
        }
        if (typingBuffer.isEmpty()) {
            return;
        }

        typingBuffer.append(ch);

        // Guard: drop buffer if it exceeds the maximum supported key length
        if (typingBuffer.length() > MAX_BUFFER_LENGTH) {
            typingBuffer.setLength(0);
            return;
        }

        checkSnippetMatch();
    }

    // ---- Snippet detection state machine ----

    /**
     * Checks whether the current buffer content matches a known snippet trigger.
     *
     * <p>The buffer format when a match is possible: {@code /<keyName>}
     * (e.g. {@code "/addr"}). The candidate key is everything after the first
     * character (the prefix).
     */
    private void checkSnippetMatch() {
        if (typingBuffer.length() < 2) {
            return;
        }
        String buf = typingBuffer.toString();
        String prefixStr = config.getSnippetPrefix();
        if (prefixStr.isEmpty() || buf.charAt(0) != prefixStr.charAt(0)) {
            return;
        }

        String candidate = buf.substring(1); // strip the prefix character
        Optional<Snippet> match = snippetService.findByKey(candidate);

        if (match.isPresent()) {
            int eraseCount = buf.length();
            String snippetValue = match.get().value();

            // Clear buffer immediately — prevents re-matching on subsequent keystrokes
            typingBuffer.setLength(0);

            log.debug("Snippet matched: /{} → {} chars", candidate, snippetValue.length());

            // Offload to paste-executor; must not block the JNativeHook thread
            pasteExecutor.submit(() -> {
                try {
                    pasteService.expandSnippet(eraseCount, snippetValue);
                } catch (Exception ex) {
                    log.error("Snippet expansion failed for key '{}'", candidate, ex);
                }
            });
        }
    }

    // ---- Helpers ----

    private boolean isHotkeyPressed(NativeKeyEvent e) {
        if (!matchesConfiguredModifiers(e)) {
            return false;
        }

        return isConfiguredTriggerKey(e);
    }

    private boolean isConfiguredTriggerKey(NativeKeyEvent e) {
        // Match the trigger key by name (case-insensitive single char)
        String hotkeyKey = config.getHotkeyKey().toUpperCase();
        return hotkeyKey.length() == 1
                && NativeKeyEvent.getKeyText(e.getKeyCode()).equalsIgnoreCase(hotkeyKey);
    }

    private boolean matchesConfiguredModifiers(NativeKeyEvent e) {
        String mods = config.getHotkeyModifiers().toLowerCase();
        int eventModifiers = e.getModifiers();
        boolean eventCtrlDown = ctrlDown || (eventModifiers & NativeKeyEvent.CTRL_MASK) != 0;
        boolean eventMetaDown = metaDown || (eventModifiers & NativeKeyEvent.META_MASK) != 0;
        boolean eventShiftDown = shiftDown || (eventModifiers & NativeKeyEvent.SHIFT_MASK) != 0;
        boolean eventAltDown = altDown || (eventModifiers & NativeKeyEvent.ALT_MASK) != 0;

        for (String rawToken : mods.split("\\+")) {
            String token = rawToken.trim();
            if (token.isEmpty()) {
                continue;
            }

            switch (token) {
                case "ctrl", "control" -> {
                    // The preferences UI stores "Ctrl / Cmd" as ctrl.
                    // Either physical key should satisfy the advertised hotkey.
                    if (!eventCtrlDown && !eventMetaDown) {
                        return false;
                    }
                }
                case "meta", "cmd", "command" -> {
                    if (!eventMetaDown) {
                        return false;
                    }
                }
                case "shift" -> {
                    if (!eventShiftDown) {
                        return false;
                    }
                }
                case "alt", "option" -> {
                    if (!eventAltDown) {
                        return false;
                    }
                }
                default -> log.warn("Ignoring unknown hotkey modifier '{}'", token);
            }
        }

        return true;
    }

    private void syncModifierState(NativeKeyEvent e) {
        int modifiers = e.getModifiers();
        ctrlDown  = ctrlDown  || (modifiers & NativeKeyEvent.CTRL_MASK)  != 0;
        metaDown  = metaDown  || (modifiers & NativeKeyEvent.META_MASK)  != 0;
        shiftDown = shiftDown || (modifiers & NativeKeyEvent.SHIFT_MASK) != 0;
        altDown   = altDown   || (modifiers & NativeKeyEvent.ALT_MASK)   != 0;
    }

    private String modifierStateForLog() {
        return "ctrl=" + ctrlDown
                + ",meta=" + metaDown
                + ",shift=" + shiftDown
                + ",alt=" + altDown;
    }

    /**
     * Returns {@code true} for keys that should terminate the current snippet
     * detection sequence (space, enter, tab, escape, backspace, delete, arrows,
     * function keys).
     */
    private boolean isClearingKey(int code) {
        return switch (code) {
            case NativeKeyEvent.VC_SPACE,
                 NativeKeyEvent.VC_ENTER,
                 NativeKeyEvent.VC_TAB,
                 NativeKeyEvent.VC_ESCAPE,
                 NativeKeyEvent.VC_BACKSPACE,
                 NativeKeyEvent.VC_DELETE,
                 NativeKeyEvent.VC_LEFT,
                 NativeKeyEvent.VC_RIGHT,
                 NativeKeyEvent.VC_UP,
                 NativeKeyEvent.VC_DOWN -> true;
            default -> code >= NativeKeyEvent.VC_F1 && code <= NativeKeyEvent.VC_F24;
        };
    }
}
