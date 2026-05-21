package com.pasties.hook;

import com.pasties.domain.AppConfig;
import com.pasties.domain.Snippet;
import com.pasties.service.PasteService;
import com.pasties.service.SnippetService;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * macOS-native global key listener for snippet expansion.
 *
 * <p>JNativeHook can register successfully on some macOS setups without
 * delivering key events. This Quartz event tap is used as the primary snippet
 * detector on macOS, while JNativeHook remains available as a fallback.
 */
public final class MacSnippetEventTap {

    private static final Logger log = LoggerFactory.getLogger(MacSnippetEventTap.class);
    private static final int MAX_BUFFER_LENGTH = 64;

    private static final int K_CG_SESSION_EVENT_TAP = 1;
    private static final int K_CG_HEAD_INSERT_EVENT_TAP = 0;
    private static final int K_CG_EVENT_TAP_OPTION_LISTEN_ONLY = 1;
    private static final int K_CG_EVENT_KEY_DOWN = 10;
    private static final int K_CG_EVENT_TAP_DISABLED_BY_TIMEOUT = -2;
    private static final int K_CG_KEYBOARD_EVENT_KEYCODE = 9;

    private static final long K_CG_EVENT_FLAG_MASK_SHIFT = 0x20000L;
    private static final long K_CG_EVENT_FLAG_MASK_CONTROL = 0x40000L;
    private static final long K_CG_EVENT_FLAG_MASK_ALTERNATE = 0x80000L;
    private static final long K_CG_EVENT_FLAG_MASK_COMMAND = 0x100000L;

    private static final Pointer K_CF_RUN_LOOP_COMMON_MODES =
            NativeLibrary.getInstance("CoreFoundation")
                    .getGlobalVariableAddress("kCFRunLoopCommonModes")
                    .getPointer(0);

    private static final Map<Long, Character> KEY_CODES = buildKeyCodeMap();

    private final SnippetService snippetService;
    private final PasteService pasteService;
    private final AppConfig config;
    private final StringBuilder typingBuffer = new StringBuilder(MAX_BUFFER_LENGTH);
    private final ExecutorService pasteExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "paste-executor-mac-event-tap");
        t.setDaemon(true);
        return t;
    });

    private QuartzEventTapCallback callback;
    private Pointer eventTap;
    private Pointer runLoopSource;
    private Pointer runLoop;
    private Thread eventThread;
    private volatile boolean registered;

    public MacSnippetEventTap(SnippetService snippetService, PasteService pasteService, AppConfig config) {
        this.snippetService = snippetService;
        this.pasteService = pasteService;
        this.config = config;
    }

    public void register() {
        if (!isMacOs() || registered) {
            return;
        }

        callback = this::handleEvent;
        eventThread = new Thread(this::runEventTap, "mac-snippet-event-tap");
        eventThread.setDaemon(true);
        eventThread.start();

        for (int i = 0; i < 20 && !registered && eventThread.isAlive(); i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void unregister() {
        registered = false;
        if (eventTap != null) {
            Quartz.INSTANCE.CGEventTapEnable(eventTap, false);
        }
        if (runLoop != null) {
            Quartz.INSTANCE.CFRunLoopStop(runLoop);
        }
        if (eventThread != null) {
            try {
                eventThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (runLoopSource != null) {
            Quartz.INSTANCE.CFRelease(runLoopSource);
            runLoopSource = null;
        }
        if (eventTap != null) {
            Quartz.INSTANCE.CFRelease(eventTap);
            eventTap = null;
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
        log.info("Native macOS snippet event tap stopped");
    }

    public boolean isRegistered() {
        return registered;
    }

    private void runEventTap() {
        long mask = 1L << K_CG_EVENT_KEY_DOWN;
        eventTap = Quartz.INSTANCE.CGEventTapCreate(
                K_CG_SESSION_EVENT_TAP,
                K_CG_HEAD_INSERT_EVENT_TAP,
                K_CG_EVENT_TAP_OPTION_LISTEN_ONLY,
                mask,
                callback,
                null);

        if (eventTap == null) {
            log.warn("Native macOS snippet event tap could not be created; Input Monitoring may be missing");
            return;
        }

        runLoopSource = Quartz.INSTANCE.CFMachPortCreateRunLoopSource(null, eventTap, 0);
        runLoop = Quartz.INSTANCE.CFRunLoopGetCurrent();
        Quartz.INSTANCE.CFRunLoopAddSource(runLoop, runLoopSource, K_CF_RUN_LOOP_COMMON_MODES);
        Quartz.INSTANCE.CGEventTapEnable(eventTap, true);
        registered = true;
        log.info("Native macOS snippet event tap registered");
        Quartz.INSTANCE.CFRunLoopRun();
    }

    private Pointer handleEvent(Pointer proxy, int type, Pointer event, Pointer refcon) {
        if (type == K_CG_EVENT_TAP_DISABLED_BY_TIMEOUT && eventTap != null) {
            Quartz.INSTANCE.CGEventTapEnable(eventTap, true);
            return event;
        }
        if (type != K_CG_EVENT_KEY_DOWN) {
            return event;
        }

        long keyCode = Quartz.INSTANCE.CGEventGetIntegerValueField(event, K_CG_KEYBOARD_EVENT_KEYCODE);
        long flags = Quartz.INSTANCE.CGEventGetFlags(event);
        Character ch = characterFor(keyCode, flags);

        if ((flags & (K_CG_EVENT_FLAG_MASK_CONTROL | K_CG_EVENT_FLAG_MASK_COMMAND | K_CG_EVENT_FLAG_MASK_ALTERNATE)) != 0) {
            return event;
        }

        if (isClearingKey(keyCode)) {
            typingBuffer.setLength(0);
            return event;
        }

        if (ch != null) {
            handleSnippetCharacter(ch);
        }
        return event;
    }

    private Character characterFor(long keyCode, long flags) {
        Character ch = KEY_CODES.get(keyCode);
        if (ch == null) {
            return null;
        }
        boolean shift = (flags & K_CG_EVENT_FLAG_MASK_SHIFT) != 0;
        if (ch == '-' && shift) {
            return '_';
        }
        if (ch == '/' && shift) {
            return null;
        }
        return Character.toLowerCase(ch);
    }

    private void handleSnippetCharacter(char ch) {
        String prefix = config.getSnippetPrefix();
        char prefixChar = prefix.isEmpty() ? '/' : prefix.charAt(0);

        if (ch == prefixChar) {
            typingBuffer.setLength(0);
            typingBuffer.append(ch);
            return;
        }

        if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '_') {
            typingBuffer.setLength(0);
            return;
        }
        if (typingBuffer.isEmpty()) {
            return;
        }

        typingBuffer.append(ch);

        if (typingBuffer.length() > MAX_BUFFER_LENGTH) {
            typingBuffer.setLength(0);
            return;
        }

        checkSnippetMatch();
    }

    private void checkSnippetMatch() {
        if (typingBuffer.length() < 2) {
            return;
        }
        String buf = typingBuffer.toString();
        String prefixStr = config.getSnippetPrefix();
        if (prefixStr.isEmpty() || buf.charAt(0) != prefixStr.charAt(0)) {
            return;
        }

        String candidate = buf.substring(1);
        Optional<Snippet> match = snippetService.findByKey(candidate);

        if (match.isPresent()) {
            int eraseCount = buf.length();
            String snippetValue = match.get().value();
            typingBuffer.setLength(0);
            log.info("Snippet matched by native macOS event tap: /{} -> {} chars", candidate, snippetValue.length());
            pasteExecutor.submit(() -> pasteService.expandSnippet(eraseCount, snippetValue));
        }
    }

    private boolean isClearingKey(long keyCode) {
        return keyCode == 36  // return
                || keyCode == 48  // tab
                || keyCode == 49  // space
                || keyCode == 51  // delete/backspace
                || keyCode == 53  // escape
                || keyCode == 123 // left
                || keyCode == 124 // right
                || keyCode == 125 // down
                || keyCode == 126; // up
    }

    private static Map<Long, Character> buildKeyCodeMap() {
        Map<Long, Character> map = new HashMap<>();
        map.put(0L, 'a'); map.put(1L, 's'); map.put(2L, 'd'); map.put(3L, 'f');
        map.put(4L, 'h'); map.put(5L, 'g'); map.put(6L, 'z'); map.put(7L, 'x');
        map.put(8L, 'c'); map.put(9L, 'v'); map.put(11L, 'b'); map.put(12L, 'q');
        map.put(13L, 'w'); map.put(14L, 'e'); map.put(15L, 'r'); map.put(16L, 'y');
        map.put(17L, 't'); map.put(31L, 'o'); map.put(32L, 'u'); map.put(34L, 'i');
        map.put(35L, 'p'); map.put(37L, 'l'); map.put(38L, 'j'); map.put(40L, 'k');
        map.put(45L, 'n'); map.put(46L, 'm');
        map.put(18L, '1'); map.put(19L, '2'); map.put(20L, '3'); map.put(21L, '4');
        map.put(23L, '5'); map.put(22L, '6'); map.put(26L, '7'); map.put(28L, '8');
        map.put(25L, '9'); map.put(29L, '0');
        map.put(27L, '-'); map.put(44L, '/');
        return map;
    }

    private boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private interface Quartz extends Library {
        Quartz INSTANCE = Native.load("ApplicationServices", Quartz.class);

        Pointer CGEventTapCreate(int tap, int place, int options, long eventsOfInterest,
                                 QuartzEventTapCallback callback, Pointer userInfo);

        Pointer CFMachPortCreateRunLoopSource(Pointer allocator, Pointer port, long order);

        Pointer CFRunLoopGetCurrent();

        void CFRunLoopAddSource(Pointer runLoop, Pointer source, Pointer mode);

        void CFRunLoopRun();

        void CFRunLoopStop(Pointer runLoop);

        void CGEventTapEnable(Pointer tap, boolean enable);

        long CGEventGetIntegerValueField(Pointer event, int field);

        long CGEventGetFlags(Pointer event);

        void CFRelease(Pointer pointer);
    }

    private interface QuartzEventTapCallback extends Callback {
        Pointer invoke(Pointer proxy, int type, Pointer event, Pointer refcon);
    }
}
