package com.pasties.hook;

import com.pasties.domain.AppConfig;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.Carbon;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * macOS-native global hotkey registration for the history menu.
 *
 * <p>JNativeHook is still used for snippet detection, but Carbon's
 * RegisterEventHotKey is more reliable for one app-level menu shortcut and
 * mirrors the native path used by apps like Clipy.
 */
public final class MacGlobalHotkey {

    private static final Logger log = LoggerFactory.getLogger(MacGlobalHotkey.class);

    private static final int NO_ERR = 0;
    private static final int HOTKEY_ID = 1;
    private static final int SIGNATURE = fourCharCode("Psty");
    private static final int K_EVENT_CLASS_KEYBOARD = fourCharCode("keyb");
    private static final int K_EVENT_HOT_KEY_PRESSED = 5;
    private static final int K_EVENT_PARAM_DIRECT_OBJECT = fourCharCode("----");
    private static final int TYPE_EVENT_HOT_KEY_ID = fourCharCode("hkid");

    private static final Map<String, Integer> MAC_KEY_CODES = Map.ofEntries(
            Map.entry("A", 0), Map.entry("S", 1), Map.entry("D", 2), Map.entry("F", 3),
            Map.entry("H", 4), Map.entry("G", 5), Map.entry("Z", 6), Map.entry("X", 7),
            Map.entry("C", 8), Map.entry("V", 9), Map.entry("B", 11), Map.entry("Q", 12),
            Map.entry("W", 13), Map.entry("E", 14), Map.entry("R", 15), Map.entry("Y", 16),
            Map.entry("T", 17), Map.entry("O", 31), Map.entry("U", 32), Map.entry("I", 34),
            Map.entry("P", 35), Map.entry("L", 37), Map.entry("J", 38), Map.entry("K", 40),
            Map.entry("N", 45), Map.entry("M", 46)
    );

    private final AppConfig config;
    private final Runnable onHotkeyPressed;

    private Carbon.EventHandlerProcPtr handler;
    private Pointer eventHandlerRef;
    private final List<Pointer> hotkeyRefs = new ArrayList<>();
    private int registeredHotkeyCount = 0;

    public MacGlobalHotkey(AppConfig config, Runnable onHotkeyPressed) {
        this.config = config;
        this.onHotkeyPressed = onHotkeyPressed;
    }

    public void register() {
        if (!isMacOs()) {
            log.info("Native macOS hotkey skipped on non-macOS platform");
            return;
        }

        int keyCode = macKeyCode(config.getHotkeyKey());
        int[] modifierVariants = carbonModifierVariants(config.getHotkeyModifiers());
        installHandler();

        for (int i = 0; i < modifierVariants.length; i++) {
            Carbon.EventHotKeyID.ByValue hotkeyId = new Carbon.EventHotKeyID.ByValue();
            hotkeyId.signature = SIGNATURE;
            hotkeyId.id = HOTKEY_ID + i;

            PointerByReference hotkeyOut = new PointerByReference();
            int status = Carbon.INSTANCE.RegisterEventHotKey(
                    keyCode,
                    modifierVariants[i],
                    hotkeyId,
                    Carbon.INSTANCE.GetEventDispatcherTarget(),
                    0,
                    hotkeyOut);
            if (status != NO_ERR) {
                throw new IllegalStateException("RegisterEventHotKey failed with OSStatus " + status);
            }

            hotkeyRefs.add(hotkeyOut.getValue());
        }

        registeredHotkeyCount = hotkeyRefs.size();
        log.info("Native macOS history hotkey registered: {}+{} (keyCode={}, variants={})",
                config.getHotkeyModifiers(), config.getHotkeyKey(), keyCode, registeredHotkeyCount);
    }

    public void unregister() {
        for (Pointer hotkeyRef : hotkeyRefs) {
            int status = Carbon.INSTANCE.UnregisterEventHotKey(hotkeyRef);
            if (status != NO_ERR) {
                log.warn("UnregisterEventHotKey returned OSStatus {}", status);
            }
        }
        hotkeyRefs.clear();
        registeredHotkeyCount = 0;
        if (eventHandlerRef != null) {
            int status = Carbon.INSTANCE.RemoveEventHandler(eventHandlerRef);
            if (status != NO_ERR) {
                log.warn("RemoveEventHandler returned OSStatus {}", status);
            }
            eventHandlerRef = null;
        }
        handler = null;
    }

    public boolean isRegistered() {
        return !hotkeyRefs.isEmpty();
    }

    private void installHandler() {
        Carbon.EventTypeSpec eventType = new Carbon.EventTypeSpec();
        eventType.eventClass = K_EVENT_CLASS_KEYBOARD;
        eventType.eventKind = K_EVENT_HOT_KEY_PRESSED;

        handler = (nextHandler, event, userData) -> {
            Carbon.EventHotKeyID eventHotKeyID = new Carbon.EventHotKeyID();
            int status = Carbon.INSTANCE.GetEventParameter(
                    event,
                    K_EVENT_PARAM_DIRECT_OBJECT,
                    TYPE_EVENT_HOT_KEY_ID,
                    null,
                    eventHotKeyID.size(),
                    null,
                    eventHotKeyID);
            eventHotKeyID.read();

            if (status == NO_ERR
                    && eventHotKeyID.signature == SIGNATURE
                    && eventHotKeyID.id >= HOTKEY_ID
                    && eventHotKeyID.id < HOTKEY_ID + registeredHotkeyCount) {
                log.info("Native macOS history hotkey detected");
                SwingUtilities.invokeLater(onHotkeyPressed);
                return NO_ERR;
            }
            return status;
        };

        PointerByReference handlerOut = new PointerByReference();
        int status = Carbon.INSTANCE.InstallEventHandler(
                Carbon.INSTANCE.GetEventDispatcherTarget(),
                handler,
                1,
                new Carbon.EventTypeSpec[]{eventType},
                null,
                handlerOut);
        if (status != NO_ERR) {
            throw new IllegalStateException("InstallEventHandler failed with OSStatus " + status);
        }

        eventHandlerRef = handlerOut.getValue();
    }

    private int[] carbonModifierVariants(String modifierConfig) {
        List<Integer> variants = new ArrayList<>();
        variants.add(0);
        for (String rawToken : modifierConfig.toLowerCase().split("\\+")) {
            String token = rawToken.trim();
            switch (token) {
                case "ctrl", "control" -> variants = expandVariants(variants, Carbon.cmdKey, Carbon.controlKey);
                case "meta", "cmd", "command" -> variants = addModifier(variants, Carbon.cmdKey);
                case "shift" -> variants = addModifier(variants, Carbon.shiftKey);
                case "alt", "option" -> variants = addModifier(variants, Carbon.optionKey);
                default -> {
                    if (!token.isEmpty()) {
                        log.warn("Ignoring unknown native hotkey modifier '{}'", token);
                    }
                }
            }
        }
        return variants.stream().mapToInt(Integer::intValue).distinct().toArray();
    }

    private List<Integer> addModifier(List<Integer> variants, int modifier) {
        return variants.stream()
                .map(value -> value | modifier)
                .distinct()
                .toList();
    }

    private List<Integer> expandVariants(List<Integer> variants, int firstModifier, int secondModifier) {
        List<Integer> expanded = new ArrayList<>(variants.size() * 2);
        for (int value : variants) {
            expanded.add(value | firstModifier);
            expanded.add(value | secondModifier);
        }
        return expanded.stream().distinct().toList();
    }

    private int macKeyCode(String key) {
        Integer keyCode = MAC_KEY_CODES.get(key.toUpperCase());
        if (keyCode == null) {
            throw new IllegalArgumentException("Unsupported macOS hotkey key: " + key);
        }
        return keyCode;
    }

    private static int fourCharCode(String value) {
        return (value.charAt(0) << 24)
                | (value.charAt(1) << 16)
                | (value.charAt(2) << 8)
                | value.charAt(3);
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
