package com.pasties.ui;

import com.pasties.domain.ClipboardEntry;
import com.pasties.domain.AppConfig;
import com.pasties.service.ClipboardService;
import com.pasties.service.PasteService;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * Clipy-style cascading history menu shown by the global hotkey.
 */
public class ClipboardHistoryMenu {

    private static final int GROUP_SIZE = 10;
    private static final int PREVIEW_CHARS = 70;

    private final ClipboardService clipboardService;
    private final PasteService pasteService;
    private final AppConfig config;
    private final Runnable onEditSnippets;
    private final Runnable onPreferences;
    private final Runnable onQuit;

    private JWindow anchorWindow;
    private JPopupMenu popupMenu;

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
        anchorWindow.setFocusableWindowState(true);
        anchorWindow.setType(Window.Type.POPUP);
        anchorWindow.setSize(1, 1);
        positionAnchor();
        anchorWindow.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                close();
            }
        });
        anchorWindow.setVisible(true);

        popupMenu = buildMenu(entries);
        popupMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { closeAnchorOnly(); }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { closeAnchorOnly(); }
        });

        popupMenu.show(anchorWindow.getContentPane(), 0, 0);
        anchorWindow.requestFocusInWindow();
    }

    private JPopupMenu buildMenu(List<ClipboardEntry> entries) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem title = new JMenuItem("History");
        title.setEnabled(false);
        menu.add(title);

        if (entries.isEmpty()) {
            JMenuItem empty = new JMenuItem("(no history yet)");
            empty.setEnabled(false);
            menu.add(empty);
        } else {
            for (int start = 0; start < entries.size(); start += GROUP_SIZE) {
                int end = Math.min(start + GROUP_SIZE, entries.size());
                JMenu group = new JMenu((start + 1) + " - " + end);
                for (ClipboardEntry entry : entries.subList(start, end)) {
                    JMenuItem item = new JMenuItem(entry.preview(PREVIEW_CHARS));
                    item.addActionListener(e -> paste(entry));
                    group.add(item);
                }
                menu.add(group);
            }
        }

        menu.addSeparator();

        JMenuItem clearHistory = new JMenuItem("Clear History");
        clearHistory.addActionListener(e -> {
            close();
            clipboardService.clearHistory();
        });
        menu.add(clearHistory);

        JMenuItem snippets = new JMenuItem("Edit Snippets...");
        snippets.addActionListener(e -> {
            close();
            onEditSnippets.run();
        });
        menu.add(snippets);

        JMenuItem preferences = new JMenuItem("Preferences...");
        preferences.addActionListener(e -> {
            close();
            onPreferences.run();
        });
        menu.add(preferences);

        menu.addSeparator();

        JMenuItem quit = new JMenuItem("Quit Pasties");
        quit.addActionListener(e -> {
            close();
            onQuit.run();
        });
        menu.add(quit);

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
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int x = Math.max(0, Math.min(mouse.x, screen.width - 2));
        int y = Math.max(0, Math.min(mouse.y, screen.height - 2));
        anchorWindow.setLocation(x, y);
    }

    private void close() {
        if (popupMenu != null) {
            popupMenu.setVisible(false);
            popupMenu = null;
        }
        closeAnchorOnly();
    }

    private void closeAnchorOnly() {
        if (anchorWindow != null) {
            anchorWindow.setVisible(false);
            anchorWindow.dispose();
            anchorWindow = null;
        }
    }
}
