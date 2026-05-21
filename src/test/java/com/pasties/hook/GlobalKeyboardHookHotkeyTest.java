package com.pasties.hook;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.pasties.domain.AppConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalKeyboardHookHotkeyTest {

    @Test
    void defaultHotkey_acceptsControlShiftS() throws Exception {
        GlobalKeyboardHook hook = new GlobalKeyboardHook(null, null, new AppConfig(), () -> {});
        setModifierState(hook, "ctrlDown", true);
        setModifierState(hook, "shiftDown", true);

        assertTrue(isHotkeyPressed(hook, keyPress(NativeKeyEvent.VC_S)));
    }

    @Test
    void defaultHotkey_acceptsCommandShiftS() throws Exception {
        GlobalKeyboardHook hook = new GlobalKeyboardHook(null, null, new AppConfig(), () -> {});
        setModifierState(hook, "metaDown", true);
        setModifierState(hook, "shiftDown", true);

        assertTrue(isHotkeyPressed(hook, keyPress(NativeKeyEvent.VC_S)));
    }

    @Test
    void defaultHotkey_requiresConfiguredTriggerKey() throws Exception {
        GlobalKeyboardHook hook = new GlobalKeyboardHook(null, null, new AppConfig(), () -> {});
        setModifierState(hook, "ctrlDown", true);
        setModifierState(hook, "shiftDown", true);

        assertFalse(isHotkeyPressed(hook, keyPress(NativeKeyEvent.VC_C)));
    }

    @Test
    void snippetCharacterMapping_handlesNonSequentialLetterCodes() throws Exception {
        GlobalKeyboardHook hook = new GlobalKeyboardHook(null, null, new AppConfig(), () -> {});

        assertEquals('l', snippetCharFromKeyPressed(hook, keyPress(NativeKeyEvent.VC_L)));
        assertEquals('i', snippetCharFromKeyPressed(hook, keyPress(NativeKeyEvent.VC_I)));
    }

    @Test
    void snippetCharacterMapping_handlesTriggerAlphabet() throws Exception {
        GlobalKeyboardHook hook = new GlobalKeyboardHook(null, null, new AppConfig(), () -> {});

        assertEquals('/', snippetCharFromKeyPressed(hook, keyPress(NativeKeyEvent.VC_SLASH)));
        assertEquals('-', snippetCharFromKeyPressed(hook, keyPress(NativeKeyEvent.VC_MINUS)));
        assertEquals('1', snippetCharFromKeyPressed(hook, keyPress(NativeKeyEvent.VC_1)));
    }

    @Test
    void modifierSync_clearsStaleModifierState() throws Exception {
        GlobalKeyboardHook hook = new GlobalKeyboardHook(null, null, new AppConfig(), () -> {});
        setModifierState(hook, "metaDown", true);

        syncModifierState(hook, keyPress(NativeKeyEvent.VC_L));

        assertFalse(getModifierState(hook, "metaDown"));
    }

    private boolean isHotkeyPressed(GlobalKeyboardHook hook, NativeKeyEvent event) throws Exception {
        Method method = GlobalKeyboardHook.class.getDeclaredMethod("isHotkeyPressed", NativeKeyEvent.class);
        method.setAccessible(true);
        return (boolean) method.invoke(hook, event);
    }

    private void setModifierState(GlobalKeyboardHook hook, String fieldName, boolean value) throws Exception {
        Field field = GlobalKeyboardHook.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(hook, value);
    }

    private boolean getModifierState(GlobalKeyboardHook hook, String fieldName) throws Exception {
        Field field = GlobalKeyboardHook.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(hook);
    }

    private void syncModifierState(GlobalKeyboardHook hook, NativeKeyEvent event) throws Exception {
        Method method = GlobalKeyboardHook.class.getDeclaredMethod("syncModifierState", NativeKeyEvent.class);
        method.setAccessible(true);
        method.invoke(hook, event);
    }

    private Character snippetCharFromKeyPressed(GlobalKeyboardHook hook, NativeKeyEvent event) throws Exception {
        Method method = GlobalKeyboardHook.class.getDeclaredMethod("snippetCharFromKeyPressed", NativeKeyEvent.class);
        method.setAccessible(true);
        return (Character) method.invoke(hook, event);
    }

    private NativeKeyEvent keyPress(int keyCode) {
        return new NativeKeyEvent(
                NativeKeyEvent.NATIVE_KEY_PRESSED,
                0,
                0,
                keyCode,
                NativeKeyEvent.CHAR_UNDEFINED,
                NativeKeyEvent.KEY_LOCATION_STANDARD);
    }
}
