package com.pasties.hook;

import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.Carbon;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;

/**
 * Temporary native macOS Escape hotkey used while transient Pasties popups are open.
 */
public final class MacEscapeHotkey {

    private static final Logger log = LoggerFactory.getLogger(MacEscapeHotkey.class);

    private static final int NO_ERR = 0;
    private static final int ESCAPE_KEY_CODE = 53;
    private static final int HOTKEY_ID = 1;
    private static final int SIGNATURE = fourCharCode("Pesc");
    private static final int K_EVENT_CLASS_KEYBOARD = fourCharCode("keyb");
    private static final int K_EVENT_HOT_KEY_PRESSED = 5;
    private static final int K_EVENT_PARAM_DIRECT_OBJECT = fourCharCode("----");
    private static final int TYPE_EVENT_HOT_KEY_ID = fourCharCode("hkid");

    private final Runnable onEscape;

    private Carbon.EventHandlerProcPtr handler;
    private Pointer eventHandlerRef;
    private Pointer hotkeyRef;

    public MacEscapeHotkey(Runnable onEscape) {
        this.onEscape = onEscape;
    }

    public void register() {
        if (!isMacOs() || hotkeyRef != null) {
            return;
        }

        installHandler();

        Carbon.EventHotKeyID.ByValue hotkeyId = new Carbon.EventHotKeyID.ByValue();
        hotkeyId.signature = SIGNATURE;
        hotkeyId.id = HOTKEY_ID;

        PointerByReference hotkeyOut = new PointerByReference();
        int status = Carbon.INSTANCE.RegisterEventHotKey(
                ESCAPE_KEY_CODE,
                0,
                hotkeyId,
                Carbon.INSTANCE.GetEventDispatcherTarget(),
                0,
                hotkeyOut);
        if (status != NO_ERR) {
            unregister();
            log.warn("Could not register native Escape hotkey, OSStatus {}", status);
            return;
        }

        hotkeyRef = hotkeyOut.getValue();
    }

    public void unregister() {
        if (hotkeyRef != null) {
            int status = Carbon.INSTANCE.UnregisterEventHotKey(hotkeyRef);
            if (status != NO_ERR) {
                log.warn("UnregisterEventHotKey(Escape) returned OSStatus {}", status);
            }
            hotkeyRef = null;
        }
        if (eventHandlerRef != null) {
            int status = Carbon.INSTANCE.RemoveEventHandler(eventHandlerRef);
            if (status != NO_ERR) {
                log.warn("RemoveEventHandler(Escape) returned OSStatus {}", status);
            }
            eventHandlerRef = null;
        }
        handler = null;
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

            if (status == NO_ERR && eventHotKeyID.signature == SIGNATURE && eventHotKeyID.id == HOTKEY_ID) {
                SwingUtilities.invokeLater(onEscape);
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
            throw new IllegalStateException("InstallEventHandler(Escape) failed with OSStatus " + status);
        }

        eventHandlerRef = handlerOut.getValue();
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
