package com.pasties.ui;

import com.pasties.domain.AppConfig;
import com.pasties.domain.ClipboardEntry;
import com.pasties.service.ClipboardService;
import com.pasties.service.PasteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.Collections;
import java.util.List;

/**
 * An always-on-top {@link JWindow} popup that appears at the mouse cursor
 * position when the user presses the global hotkey.
 *
 * <p>Displays the clipboard history as a scrollable list. Clicking or pressing
 * Enter on an item pastes it into the previously focused application.
 *
 * <h2>Dismissal triggers</h2>
 * <ul>
 *   <li>User clicks outside the window (via {@link WindowFocusListener})</li>
 *   <li>User presses Escape (via InputMap)</li>
 *   <li>User selects an item (single click or Enter)</li>
 * </ul>
 *
 * <h2>Thread notes</h2>
 * <p>{@link #show()} and {@link #hide()} must be called on the EDT.
 * {@link PasteService#pasteText(String)} blocks and is offloaded to a daemon
 * thread — it is never called on the EDT.
 */
public class ClipboardHistoryPopup {

    private static final Logger log = LoggerFactory.getLogger(ClipboardHistoryPopup.class);

    private static final int POPUP_WIDTH = 440;
    private static final int ROW_HEIGHT = 30;
    private static final int MAX_VISIBLE_ROWS = 15;
    private static final int HEADER_HEIGHT = 32;
    private static final int NAV_HEIGHT = 28;
    private static final int PREVIEW_CHARS = 80;

    private final ClipboardService clipboardService;
    private final PasteService pasteService;
    private final AppConfig config;

    private final JWindow window;
    private final DefaultListModel<ClipboardEntry> model;
    private final JList<ClipboardEntry> list;

    /** Full entry list for the current popup session. */
    private List<ClipboardEntry> allEntries = Collections.emptyList();
    /** Current page index (0-based). */
    private int currentPage = 0;

    private JButton prevBtn;
    private JButton nextBtn;
    private JLabel pageLabel;
    private JPanel navPanel;

    public ClipboardHistoryPopup(ClipboardService clipboardService,
                                  PasteService pasteService,
                                  AppConfig config) {
        this.clipboardService = clipboardService;
        this.pasteService = pasteService;
        this.config = config;

        model  = new DefaultListModel<>();
        list   = buildList();
        window = buildWindow(list);
    }

    // ---- Public API ----

    /**
     * Populates the list from the in-memory cache and shows the popup at the
     * current mouse cursor position. Must be called on the EDT.
     */
    public void show() {
        int pageSize = Math.max(1, config.getPopupHistorySize());
        allEntries = clipboardService.getCachedHistory();

        if (allEntries.isEmpty()) {
            log.debug("No clipboard history to display");
            return;
        }

        currentPage = 0;
        renderPage(pageSize);

        positionNearCursor();
        window.setVisible(true);
        list.requestFocusInWindow();
        list.setSelectedIndex(0);
    }

    /** Fills the list model with entries for {@code currentPage}. */
    private void renderPage(int pageSize) {
        int total      = allEntries.size();
        int totalPages = (int) Math.ceil((double) total / pageSize);
        int from       = currentPage * pageSize;
        int to         = Math.min(from + pageSize, total);

        model.clear();
        allEntries.subList(from, to).forEach(model::addElement);

        // Update nav bar visibility and labels
        boolean multiPage = totalPages > 1;
        navPanel.setVisible(multiPage);
        if (multiPage) {
            pageLabel.setText("Page " + (currentPage + 1) + " / " + totalPages);
            prevBtn.setEnabled(currentPage > 0);
            nextBtn.setEnabled(currentPage < totalPages - 1);
        }

        // Re-size window to fit new content
        positionNearCursor();
    }

    /**
     * Hides the popup. Must be called on the EDT.
     */
    public void hide() {
        window.setVisible(false);
    }

    // ---- Build helpers ----

    private JList<ClipboardEntry> buildList() {
        JList<ClipboardEntry> l = new JList<>(model);
        l.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        l.setFixedCellHeight(ROW_HEIGHT);
        l.setCellRenderer(new EntryRenderer(PREVIEW_CHARS));
        l.setFocusable(true);

        // Escape → dismiss
        l.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dismiss");
        l.getActionMap().put("dismiss", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { hide(); }
        });

        // Single click → paste
        l.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = l.locationToIndex(e.getPoint());
                if (idx >= 0) {
                    dispatchPaste(model.get(idx));
                }
            }
        });

        // Enter key → paste selected
        l.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    ClipboardEntry sel = l.getSelectedValue();
                    if (sel != null) dispatchPaste(sel);
                }
            }
        });

        return l;
    }

    private JWindow buildWindow(JList<ClipboardEntry> l) {
        JWindow w = new JWindow();
        w.setAlwaysOnTop(true);
        w.setType(Window.Type.POPUP);

        JLabel header = new JLabel("  Clipboard History");
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));
        header.setOpaque(true);
        header.setBackground(new Color(235, 235, 235));
        header.setBorder(new EmptyBorder(6, 6, 6, 6));
        header.setPreferredSize(new Dimension(POPUP_WIDTH, HEADER_HEIGHT));

        JScrollPane scroll = new JScrollPane(l);
        scroll.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 1, Color.LIGHT_GRAY));

        // Navigation bar (hidden when only one page)
        prevBtn   = new JButton("\u2190");
        nextBtn   = new JButton("\u2192");
        pageLabel = new JLabel("", SwingConstants.CENTER);
        pageLabel.setFont(pageLabel.getFont().deriveFont(Font.PLAIN, 11f));

        prevBtn.setMargin(new Insets(2, 6, 2, 6));
        nextBtn.setMargin(new Insets(2, 6, 2, 6));
        prevBtn.setFocusable(false);
        nextBtn.setFocusable(false);

        prevBtn.addActionListener(e -> {
            if (currentPage > 0) {
                currentPage--;
                renderPage(Math.max(1, config.getPopupHistorySize()));
                list.setSelectedIndex(0);
                list.requestFocusInWindow();
            }
        });
        nextBtn.addActionListener(e -> {
            int totalPages = (int) Math.ceil(
                    (double) allEntries.size() / Math.max(1, config.getPopupHistorySize()));
            if (currentPage < totalPages - 1) {
                currentPage++;
                renderPage(Math.max(1, config.getPopupHistorySize()));
                list.setSelectedIndex(0);
                list.requestFocusInWindow();
            }
        });

        navPanel = new JPanel(new BorderLayout(4, 0));
        navPanel.setBackground(new Color(245, 245, 245));
        navPanel.setBorder(new EmptyBorder(3, 6, 3, 6));
        navPanel.add(prevBtn,   BorderLayout.WEST);
        navPanel.add(pageLabel, BorderLayout.CENTER);
        navPanel.add(nextBtn,   BorderLayout.EAST);
        navPanel.setPreferredSize(new Dimension(POPUP_WIDTH, NAV_HEIGHT));
        navPanel.setVisible(false);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        panel.add(header,   BorderLayout.NORTH);
        panel.add(scroll,   BorderLayout.CENTER);
        panel.add(navPanel, BorderLayout.SOUTH);

        w.setContentPane(panel);

        // Dismiss when focus leaves the popup
        w.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                hide();
            }
        });

        return w;
    }

    private void positionNearCursor() {
        Point mouse = MouseInfo.getPointerInfo().getLocation();
        int rows = Math.min(MAX_VISIBLE_ROWS, Math.max(1, model.size()));
        int navHeight = navPanel.isVisible() ? NAV_HEIGHT : 0;
        int popupHeight = rows * ROW_HEIGHT + HEADER_HEIGHT + navHeight + 4;

        window.setSize(POPUP_WIDTH, popupHeight);

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int x = Math.min(mouse.x, screen.width  - POPUP_WIDTH - 10);
        int y = Math.min(mouse.y, screen.height - popupHeight - 10);
        window.setLocation(Math.max(0, x), Math.max(0, y));
    }

    private void dispatchPaste(ClipboardEntry entry) {
        hide();
        // Allow focus to return to the previously active app before pasting
        Thread t = new Thread(() -> {
            try { Thread.sleep(60); } catch (InterruptedException ignored) {}
            pasteService.pasteText(entry.content());
        }, "paste-from-popup");
        t.setDaemon(true);
        t.start();
    }

    // ---- Cell renderer ----

    private static class EntryRenderer extends DefaultListCellRenderer {
        private final int maxChars;

        EntryRenderer(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ClipboardEntry entry) {
                setText(String.format("  %d.  %s", index + 1, entry.preview(maxChars)));
                setFont(getFont().deriveFont(Font.PLAIN, 12f));
            }
            return this;
        }
    }
}
