package com.pasties;

import com.pasties.domain.AppConfig;
import com.pasties.hook.ClipboardMonitor;
import com.pasties.hook.GlobalKeyboardHook;
import com.pasties.hook.MacGlobalHotkey;
import com.pasties.hook.MacSnippetEventTap;
import com.pasties.infrastructure.AppLifecycle;
import com.pasties.infrastructure.DatabaseManager;
import com.pasties.infrastructure.PermissionChecker;
import com.pasties.repository.ClipboardRepository;
import com.pasties.repository.ConfigRepository;
import com.pasties.repository.SnippetRepository;
import com.pasties.service.ClipboardService;
import com.pasties.service.MetricsService;
import com.pasties.service.PasteService;
import com.pasties.service.SnippetService;
import com.pasties.ui.MenuBarApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application entry point.
 *
 * <h2>Startup sequence</h2>
 * <ol>
 *   <li>Suppress Dock icon ({@code apple.awt.UIElement}).</li>
 *   <li>Open/create the SQLite database and run the schema.</li>
 *   <li>Load {@link AppConfig} from the database (blocking, 5 s timeout).</li>
 *   <li>Construct all repositories, services, and the metrics service.</li>
 *   <li>Prime the snippet in-memory map (awaited before continuing).</li>
 *   <li>Check macOS permissions (Accessibility + Input Monitoring);
 *       exit if either is missing.</li>
 *   <li>Start the clipboard polling monitor.</li>
 *   <li>Initialise the system tray UI on the EDT.</li>
 *   <li>Register the global keyboard hook.</li>
 *   <li>Register all shutdown hooks with {@link AppLifecycle}.</li>
 * </ol>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        // 1. Suppress Dock icon — app lives exclusively in the menu bar
        System.setProperty("apple.awt.UIElement", "true");

        // Enable macOS-native look for AWT menus (dark/light mode, correct font)
        System.setProperty("apple.laf.useScreenMenuBar", "true");

        try {
            run();
        } catch (Exception e) {
            log.error("Fatal error during startup", e);
            JOptionPane.showMessageDialog(null,
                    "Pasties failed to start:\n" + e.getMessage(),
                    "Startup Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        // 2. Database
        DatabaseManager db = new DatabaseManager();
        db.initialize();
        log.info("Database ready");

        // 3. Config
        ConfigRepository configRepo = new ConfigRepository(db);
        AppConfig config = configRepo.load();
        log.info("Config loaded — maxHistory={}, hotkey={}+{}",
                config.getMaxHistorySize(), config.getHotkeyModifiers(), config.getHotkeyKey());

        // 4. Repositories + Services
        ClipboardRepository clipboardRepo = new ClipboardRepository(db);
        SnippetRepository   snippetRepo   = new SnippetRepository(db);

        ClipboardService clipboardService = new ClipboardService(clipboardRepo, config);
        SnippetService   snippetService   = new SnippetService(snippetRepo);
        MetricsService   metricsService   = new MetricsService();

        AtomicBoolean isProgrammaticPaste = new AtomicBoolean(false);
        PasteService  pasteService        = new PasteService(isProgrammaticPaste, metricsService);

        // 5. Prime snippet map (blocking)
        snippetService.initialize().get();
        log.info("Snippet cache ready");

        // Wire service references into MetricsService for payload calculations
        metricsService.setServices(clipboardService, snippetService);

        // 6. Permission check — exit cleanly if either permission is missing
        if (!PermissionChecker.checkAndPrompt()) {
            log.warn("Required permissions not granted — exiting");
            System.exit(0);
        }

        // 7. Clipboard monitor
        ClipboardMonitor clipboardMonitor = new ClipboardMonitor(clipboardService, isProgrammaticPaste);
        clipboardMonitor.start();

        // 8. System tray UI (must run on EDT)
        MenuBarApp menuBarApp = new MenuBarApp(
                clipboardService, snippetService, pasteService,
                metricsService, config, configRepo);

        SwingUtilities.invokeAndWait(menuBarApp::initialize);
        log.info("Menu bar UI ready");

        // 9. Global keyboard hooks (after EDT is running)
        MacGlobalHotkey macHotkey = new MacGlobalHotkey(config, menuBarApp::showHistoryMenu);
        MacSnippetEventTap macSnippetEventTap = new MacSnippetEventTap(snippetService, pasteService, config);
        boolean nativeHotkeyRegistered = false;
        boolean nativeSnippetHookRegistered = false;
        if (isMacOs()) {
            try {
                macHotkey.register();
                nativeHotkeyRegistered = macHotkey.isRegistered();
            } catch (Exception e) {
                log.warn("Native macOS history hotkey registration failed; falling back to JNativeHook hotkey", e);
            }
            try {
                macSnippetEventTap.register();
                nativeSnippetHookRegistered = macSnippetEventTap.isRegistered();
            } catch (Exception e) {
                log.warn("Native macOS snippet event tap failed; falling back to JNativeHook snippets", e);
            }
        }

        Runnable jNativeHookHotkeyCallback = nativeHotkeyRegistered ? () -> { } : menuBarApp::showHistoryMenu;
        GlobalKeyboardHook keyboardHook = new GlobalKeyboardHook(
                snippetService,
                pasteService,
                config,
                jNativeHookHotkeyCallback,
                !nativeSnippetHookRegistered
        );
        if (nativeSnippetHookRegistered && nativeHotkeyRegistered) {
            log.info("JNativeHook skipped; native macOS hooks are handling snippets and history hotkey");
        } else {
            keyboardHook.register();
        }

        // 10. Shutdown hooks (registered in reverse teardown order)
        AppLifecycle.registerShutdownHook(macHotkey::unregister);
        AppLifecycle.registerShutdownHook(macSnippetEventTap::unregister);
        AppLifecycle.registerShutdownHook(keyboardHook::unregister);
        AppLifecycle.registerShutdownHook(clipboardMonitor::stop);
        AppLifecycle.registerShutdownHook(menuBarApp::shutdown);
        AppLifecycle.registerShutdownHook(clipboardRepo::shutdown);
        AppLifecycle.registerShutdownHook(snippetRepo::shutdown);
        AppLifecycle.registerShutdownHook(configRepo::shutdown);
        AppLifecycle.registerShutdownHook(db::close);

        log.info("Pasties started successfully — running in system tray");

        // Keep the main thread alive; all work happens on daemon/EDT threads
        Thread.currentThread().join();
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
