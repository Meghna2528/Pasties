package com.pasties.infrastructure;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.IOException;

/**
 * Checks and guides the user through granting the two macOS permissions
 * required by Pasties:
 *
 * <ol>
 *   <li><b>Accessibility</b> — required by {@link java.awt.Robot} for keystroke
 *       simulation (paste, backspace during snippet expansion).</li>
 *   <li><b>Input Monitoring</b> — required by JNativeHook for global keyboard
 *       event capture (available on macOS Catalina 10.15+).</li>
 * </ol>
 *
 * <p>Checks are performed synchronously at startup before any hooks are
 * registered. If either permission is absent the user sees a clear dialog
 * explaining what to grant, System Settings is opened automatically, and
 * the app exits so the user can restart after granting access.
 */
public final class PermissionChecker {

    private static final Logger log = LoggerFactory.getLogger(PermissionChecker.class);

    /** JNA binding to macOS ApplicationServices framework. */
    private interface ApplicationServices extends Library {
        ApplicationServices INSTANCE = Native.load("ApplicationServices", ApplicationServices.class);

        /**
         * Returns {@code true} if the current process is trusted for
         * Accessibility. Does not show a system prompt.
         */
        boolean AXIsProcessTrusted();
    }

    private PermissionChecker() {}

    /**
     * Checks Accessibility and Input Monitoring permissions.
     *
     * <p>If both are granted returns {@code true}. Otherwise shows a modal
     * warning dialog, opens System Settings, and returns {@code false} (the
     * caller should exit the application).
     */
    public static boolean checkAndPrompt() {
        boolean accessibilityOk = checkAccessibility();
        boolean inputMonitoringOk = checkInputMonitoring();

        if (accessibilityOk && inputMonitoringOk) {
            log.info("All required permissions granted");
            return true;
        }

        showPermissionDialog(accessibilityOk, inputMonitoringOk);
        return false;
    }

    private static boolean checkAccessibility() {
        try {
            boolean trusted = ApplicationServices.INSTANCE.AXIsProcessTrusted();
            log.info("Accessibility permission: {}", trusted ? "granted" : "MISSING");
            return trusted;
        } catch (Exception e) {
            log.warn("Could not check Accessibility permission via JNA", e);
            // Treat as missing to be safe
            return false;
        }
    }

    /**
     * Input Monitoring cannot be queried without a native API that requires
     * linking against a private framework. The safest cross-version strategy is
     * a trial registration: if the hook registers cleanly the permission is
     * granted; if it throws {@link NativeHookException} it is not.
     */
    private static boolean checkInputMonitoring() {
        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.unregisterNativeHook();
            log.info("Input Monitoring permission: granted");
            return true;
        } catch (NativeHookException e) {
            log.warn("Input Monitoring permission: MISSING — {}", e.getMessage());
            return false;
        }
    }

    private static void showPermissionDialog(boolean accessibilityOk, boolean inputMonitoringOk) {
        StringBuilder message = new StringBuilder();
        message.append("<html><b>Pasties needs additional macOS permissions to function.</b><br><br>");

        if (!accessibilityOk) {
            message.append("<u>Accessibility</u><br>");
            message.append("System Settings → Privacy &amp; Security → Accessibility<br>");
            message.append("Add and enable <b>Pasties</b><br><br>");
        }
        if (!inputMonitoringOk) {
            message.append("<u>Input Monitoring</u><br>");
            message.append("System Settings → Privacy &amp; Security → Input Monitoring<br>");
            message.append("Add and enable <b>Pasties</b><br><br>");
        }
        message.append("After granting permission(s), <b>restart Pasties</b>.</html>");

        JOptionPane.showMessageDialog(
                null,
                message.toString(),
                "Permissions Required — Pasties",
                JOptionPane.WARNING_MESSAGE
        );

        // Open System Settings to the Privacy pane automatically
        try {
            Runtime.getRuntime().exec(new String[]{
                    "open",
                    "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
            });
        } catch (IOException e) {
            log.error("Could not open System Settings automatically", e);
        }
    }
}
