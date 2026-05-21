package com.pasties.ui;

import com.pasties.domain.ClipboardEntry;
import com.pasties.domain.AppConfig;
import com.pasties.hook.MacEscapeHotkey;
import com.pasties.service.ClipboardService;
import com.pasties.service.PasteService;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Clipy-style cascading history menu shown by the global hotkey.
 */
public class ClipboardHistoryMenu {

    private static final int GROUP_SIZE = 10;
    private static final int PREVIEW_CHARS = 54;
    private static final Font MENU_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
    private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
    private static final Color MENU_BACKGROUND = new Color(250, 250, 250);
    private static final Color MENU_FOREGROUND = new Color(32, 32, 32);
    private static final Color TITLE_FOREGROUND = new Color(120, 120, 120);
    private static final Insets ITEM_MARGIN = new Insets(3, 12, 3, 10);

    private final ClipboardService clipboardService;
    private final PasteService pasteService;
    private final AppConfig config;
    private final Runnable onEditSnippets;
    private final Runnable onPreferences;
    private final Runnable onQuit;

    private JWindow anchorWindow;
    private JPopupMenu popupMenu;
    private KeyEventDispatcher escapeDispatcher;
    private MacEscapeHotkey nativeEscapeHotkey;

    public ClipboardHistoryMenu(ClipboardService clipboardService,
                                PasteService pasteService,
                                AppConfig config,
                                Runnable onEditSnippets,
                                Runnable onPreferences,
                                Runnable onQuit) {
        this.clipboardService = clipboardService;
        this.pasteService = pasteService;
        this.config = config;
        this.onEditSnippets = onEditSnippets;
        this.onPreferences = onPreferences;
        this.onQuit = onQuit;
    }

    public void show() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::show);
            return;
        }

        close();

        clipboardService.loadHistory(config.getMaxHistorySize())
                .thenAccept(entries -> SwingUtilities.invokeLater(() -> showWithEntries(entries)))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> showWithEntries(clipboardService.getCachedHistory()));
                    return null;
                });
    }

    private void showWithEntries(List<ClipboardEntry> entries) {
        close();

        anchorWindow = new JWindow();
        anchorWindow.setAlwaysOnTop(true);
        anchorWindow.setFocusableWindowState(false);
        anchorWindow.setType(Window.Type.POPUP);
        anchorWindow.setSize(1, 1);
        positionAnchor();
        anchorWindow.setVisible(true);

        popupMenu = buildMenu(entries);
        popupMenu.setLightWeightPopupEnabled(false);
        popupMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { cleanupAfterPopupHidden(); }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { cleanupAfterPopupHidden(); }
        });

        installEscapeDispatcher();
        installNativeEscapeHotkey();
        popupMenu.show(anchorWindow.getContentPane(), 0, 0);
    }

    private JPopupMenu buildMenu(List<ClipboardEntry> entries) {
        JPopupMenu menu = styledPopup();

        JMenuItem title = styledItem("History");
        title.setFont(TITLE_FONT);
        title.setForeground(TITLE_FOREGROUND);
        title.setEnabled(false);
        menu.add(title);

        if (entries.isEmpty()) {
            JMenuItem empty = styledItem("(no history yet)");
            empty.setEnabled(false);
            menu.add(empty);
        } else {
            for (int start = 0; start < entries.size(); start += GROUP_SIZE) {
                int end = Math.min(start + GROUP_SIZE, entries.size());
                JMenu group = styledMenu((start + 1) + " - " + end);
                for (ClipboardEntry entry : entries.subList(start, end)) {
                    JMenuItem item = styledItem(entry.preview(PREVIEW_CHARS));
                    item.addActionListener(e -> paste(entry));
                    group.add(item);
                }
                menu.add(group);
            }
        }

        menu.add(styledSeparator());

        JMenuItem clearHistory = styledItem("Clear History");
        clearHistory.addActionListener(e -> {
            close();
            clipboardService.clearHistory();
        });
        menu.add(clearHistory);

        JMenuItem snippets = styledItem("Edit Snippets...");
        snippets.addActionListener(e -> {
            close();
            onEditSnippets.run();
        });
        menu.add(snippets);

        JMenuItem preferences = styledItem("Preferences...");
        preferences.addActionListener(e -> {
            close();
            onPreferences.run();
        });
        menu.add(preferences);

        menu.add(styledSeparator());

        JMenuItem quit = styledItem("Quit Pasties");
        quit.addActionListener(e -> {
            close();
            onQuit.run();
        });
        menu.add(quit);

        return menu;
    }

    private JMenu styledMenu(String label) {
        JMenu menu = new JMenu(label);
        styleItem(menu);
        menu.getPopupMenu().setLightWeightPopupEnabled(false);
        menu.getPopupMenu().setBackground(MENU_BACKGROUND);
        menu.getPopupMenu().setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(205, 205, 205)),
                BorderFactory.createEmptyBorder(4, 0, 4, 0)
        ));
        return menu;
    }

    private JMenuItem styledItem(String label) {
        JMenuItem item = new JMenuItem(label);
        styleItem(item);
        return item;
    }

    private void styleItem(JMenuItem item) {
        item.setFont(MENU_FONT);
        item.setMargin(ITEM_MARGIN);
        item.setForeground(MENU_FOREGROUND);
        item.setBackground(MENU_BACKGROUND);
        item.setOpaque(true);
        item.setBorder(BorderFactory.createEmptyBorder(3, 12, 3, 10));
        item.setPreferredSize(null);
    }

    private JSeparator styledSeparator() {
        JSeparator separator = new JSeparator();
        separator.setForeground(new Color(220, 220, 220));
        separator.setBackground(MENU_BACKGROUND);
        return separator;
    }

    private JPopupMenu styledPopup() {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(MENU_BACKGROUND);
        menu.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(205, 205, 205)),
                BorderFactory.createEmptyBorder(4, 0, 4, 0)
        ));
        return menu;
    }

    private void paste(ClipboardEntry entry) {
        close();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            pasteService.pasteText(entry.content());
        }, "paste-from-history-menu");
        t.setDaemon(true);
        t.start();
    }

    private void positionAnchor() {
        Point mouse = MouseInfo.getPointerInfo().getLocation();
        Rectangle screen = screenBoundsFor(mouse);
        int x = Math.max(screen.x, Math.min(mouse.x, screen.x + screen.width - 2));
        int y = Math.max(screen.y, Math.min(mouse.y, screen.y + screen.height - 2));
        anchorWindow.setLocation(x, y);
    }

    private Rectangle screenBoundsFor(Point point) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            if (bounds.contains(point)) {
                return bounds;
            }
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration()
                .getBounds();
    }

    private void close() {
        if (popupMenu != null) {
            popupMenu.setVisible(false);
            popupMenu = null;
        }
        uninstallEscapeDispatcher();
        uninstallNativeEscapeHotkey();
        closeAnchorOnly();
    }

    private void installEscapeDispatcher() {
        uninstallEscapeDispatcher();
        escapeDispatcher = EscapeToClose.globalDispatcher(this::close);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escapeDispatcher);
    }

    private void uninstallEscapeDispatcher() {
        if (escapeDispatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escapeDispatcher);
            escapeDispatcher = null;
        }
    }

    private void installNativeEscapeHotkey() {
        uninstallNativeEscapeHotkey();
        nativeEscapeHotkey = new MacEscapeHotkey(this::close);
        nativeEscapeHotkey.register();
    }

    private void uninstallNativeEscapeHotkey() {
        if (nativeEscapeHotkey != null) {
            nativeEscapeHotkey.unregister();
            nativeEscapeHotkey = null;
        }
    }

    private void cleanupAfterPopupHidden() {
        uninstallEscapeDispatcher();
        uninstallNativeEscapeHotkey();
        closeAnchorOnly();
        popupMenu = null;
    }

    private void closeAnchorOnly() {
        if (anchorWindow != null) {
            anchorWindow.setVisible(false);
            anchorWindow.dispose();
            anchorWindow = null;
        }
    }
}
