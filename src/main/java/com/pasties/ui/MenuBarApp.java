package com.pasties.ui;

import com.pasties.domain.AppConfig;
import com.pasties.domain.ClipboardEntry;
import com.pasties.repository.ConfigRepository;
import com.pasties.service.ClipboardService;
import com.pasties.service.MetricsService;
import com.pasties.service.PasteService;
import com.pasties.service.SnippetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.net.URL;
import java.util.List;

/**
 * Initialises and manages the macOS menu bar (system tray) icon and its
 * associated popup menu.
 *
 * <p>Uses Java's built-in {@link SystemTray} and {@link TrayIcon} APIs, which
 * work reliably on macOS 13+. No external dependency is required, keeping the
 * fat JAR lightweight.
 *
 * <h2>Menu structure</h2>
 * <pre>
 * [Pasties icon]
 *   ├─ Recent (submenu — up to 10 items, dynamically refreshed)
 *   ├─ ───────────────────────
 *   ├─ Snippets…
 *   ├─ Performance Dashboard…
 *   ├─ Preferences…
 *   ├─ ───────────────────────
 *   └─ Quit Pasties
 * </pre>
 *
 * <p>Must be initialised on the Event Dispatch Thread (via
 * {@link SwingUtilities#invokeAndWait}).
 */
public class MenuBarApp {

    private static final Logger log = LoggerFactory.getLogger(MenuBarApp.class);
    private static final int PREVIEW_CHARS = 60;

    private final ClipboardService clipboardService;
    private final SnippetService snippetService;
    private final PasteService pasteService;
    private final MetricsService metricsService;
    private final AppConfig config;
    private final ConfigRepository configRepo;

    private TrayIcon trayIcon;
    private PopupMenu trayMenu;
    private Menu recentMenu;

    private ClipboardHistoryPopup historyPopup;
    private MetricsDashboardDialog dashboardDialog;
    private HistoryDialog historyDialog;

    /** Current page index for the Recent submenu (0-based). */
    private int recentPage = 0;
    /** Last known full entry list, kept so page navigation can re-render without a listener call. */
    private List<ClipboardEntry> cachedEntries = List.of();

    public MenuBarApp(ClipboardService clipboardService,
                      SnippetService snippetService,
                      PasteService pasteService,
                      MetricsService metricsService,
                      AppConfig config,
                      ConfigRepository configRepo) {
        this.clipboardService = clipboardService;
        this.snippetService   = snippetService;
        this.pasteService     = pasteService;
        this.metricsService   = metricsService;
        this.config           = config;
        this.configRepo       = configRepo;
    }

    /**
     * Initialises the system tray icon and popup menu.
     * Must be called on the EDT.
     *
     * @throws UnsupportedOperationException if {@link SystemTray} is not supported
     */
    public void initialize() {
        if (!SystemTray.isSupported()) {
            throw new UnsupportedOperationException(
                    "SystemTray is not supported on this platform");
        }

        trayMenu = buildMenu();
        trayIcon = new TrayIcon(loadIcon(), "Pasties", trayMenu);
        trayIcon.setImageAutoSize(true);

        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            throw new RuntimeException("Could not add icon to system tray", e);
        }

        historyPopup = new ClipboardHistoryPopup(clipboardService, pasteService, config);
        dashboardDialog = new MetricsDashboardDialog(null, metricsService);
        historyDialog = new HistoryDialog(null, clipboardService, pasteService);

        // Subscribe to clipboard history changes to keep the Recent submenu fresh
        clipboardService.addChangeListener(entries ->
                SwingUtilities.invokeLater(() -> refreshRecentMenu(entries))
        );

        log.info("MenuBarApp initialised in system tray");
    }

    /**
     * Shows the clipboard history popup at the current cursor position.
     * Must be called on the EDT (dispatched by {@link com.pasties.hook.GlobalKeyboardHook}).
     */
    public void showHistoryPopup() {
        historyPopup.show();
    }

    /** Removes the tray icon on shutdown. */
    public void shutdown() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
    }

    // ---- Menu construction ----

    private PopupMenu buildMenu() {
        PopupMenu menu = new PopupMenu();

        recentMenu = new Menu("Recent");
        recentMenu.add(buildDisabledItem("(no history yet)"));
        menu.add(recentMenu);

        menu.add(buildItem("History", e ->
                SwingUtilities.invokeLater(() -> historyDialog.open())
        ));

        menu.addSeparator();

        menu.add(buildItem("Snippets", e ->
                SwingUtilities.invokeLater(() ->
                        new SnippetManagerDialog(null, snippetService).setVisible(true))
        ));

        menu.add(buildItem("Performance Dashboard", e ->
                SwingUtilities.invokeLater(() -> dashboardDialog.showDashboard())
        ));

        menu.add(buildItem("Preferences", e ->
                SwingUtilities.invokeLater(() ->
                        new PreferencesDialog(null, config, clipboardService, configRepo)
                                .setVisible(true))
        ));

        menu.addSeparator();

        menu.add(buildItem("Quit Pasties", e -> {
            com.pasties.infrastructure.AppLifecycle.shutdown();
            System.exit(0);
        }));

        return menu;
    }

    /** Rebuilds the Recent submenu entries whenever clipboard history changes. */
    private void refreshRecentMenu(List<ClipboardEntry> entries) {
        cachedEntries = entries;
        // Clamp page in case history shrank or page size changed
        int pageSize = Math.max(1, config.getRecentMenuSize());
        int totalPages = entries.isEmpty() ? 1 : (int) Math.ceil((double) entries.size() / pageSize);
        if (recentPage >= totalPages) recentPage = Math.max(0, totalPages - 1);

        renderRecentMenu();
    }

    /** Renders the current page of the Recent submenu from {@code cachedEntries}. */
    private void renderRecentMenu() {
        recentMenu.removeAll();

        if (cachedEntries.isEmpty()) {
            recentMenu.add(buildDisabledItem("(no history yet)"));
            return;
        }

        int pageSize  = Math.max(1, config.getRecentMenuSize());
        int total     = cachedEntries.size();
        int totalPages = (int) Math.ceil((double) total / pageSize);
        int from      = recentPage * pageSize;
        int to        = Math.min(from + pageSize, total);

        List<ClipboardEntry> page = cachedEntries.subList(from, to);
        for (ClipboardEntry entry : page) {
            MenuItem item = new MenuItem(entry.preview(PREVIEW_CHARS));
            item.addActionListener(e -> {
                Thread t = new Thread(
                        () -> pasteService.pasteText(entry.content()),
                        "paste-from-menu");
                t.setDaemon(true);
                t.start();
            });
            recentMenu.add(item);
        }

        // Navigation row (only shown when there is more than one page)
        if (totalPages > 1) {
            recentMenu.addSeparator();

            // Page indicator (disabled label)
            recentMenu.add(buildDisabledItem(
                    String.format("Page %d / %d", recentPage + 1, totalPages)));

            if (recentPage > 0) {
                MenuItem prev = new MenuItem("\u2190 Prev");
                prev.addActionListener(e -> {
                    recentPage--;
                    renderRecentMenu();
                });
                recentMenu.add(prev);
            }

            if (recentPage < totalPages - 1) {
                MenuItem next = new MenuItem("Next \u2192");
                next.addActionListener(e -> {
                    recentPage++;
                    renderRecentMenu();
                });
                recentMenu.add(next);
            }
        }
    }

    // ---- Helpers ----

    private MenuItem buildItem(String label, ActionListener listener) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(listener);
        return item;
    }

    private MenuItem buildDisabledItem(String label) {
        MenuItem item = new MenuItem(label);
        item.setEnabled(false);
        return item;
    }

    private Image loadIcon() {
        URL iconUrl = getClass().getResource("/icon_16.png");
        if (iconUrl != null) {
            return Toolkit.getDefaultToolkit().getImage(iconUrl);
        }
        // Fallback: 1x1 transparent image if the resource is missing
        log.warn("icon_16.png not found in resources; using blank icon");
        return new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    }
}
