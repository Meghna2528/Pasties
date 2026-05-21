package com.pasties.ui;

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
 * Modal dialog showing up to 200 clipboard history entries, paginated at
 * 10 entries per page (20 pages maximum).
 *
 * <p>Entries are loaded from the database on each open via
 * {@link ClipboardService#loadHistory(int)}, so they reflect the full
 * TTL-limited history rather than just the in-memory cache.
 *
 * <p>Clicking or pressing Enter on an entry dismisses the dialog and pastes
 * the content into the previously focused application.
 */
public class HistoryDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(HistoryDialog.class);

    private static final int PAGE_SIZE   = 10;
    private static final int MAX_ENTRIES = 200;
    private static final int ROW_HEIGHT  = 28;
    private static final int PREVIEW_CHARS = 90;

    private final ClipboardService clipboardService;
    private final PasteService pasteService;

    private final DefaultListModel<ClipboardEntry> model = new DefaultListModel<>();
    private final JList<ClipboardEntry> list;
    private final JButton prevBtn;
    private final JButton nextBtn;
    private final JLabel pageLabel;

    private List<ClipboardEntry> allEntries = Collections.emptyList();
    private int currentPage = 0;

    public HistoryDialog(Frame parent,
                         ClipboardService clipboardService,
                         PasteService pasteService) {
        super(parent, "Clipboard History", false);
        this.clipboardService = clipboardService;
        this.pasteService     = pasteService;

        list = buildList();

        prevBtn   = new JButton("\u2190 Prev");
        nextBtn   = new JButton("Next \u2192");
        pageLabel = new JLabel("", SwingConstants.CENTER);
        pageLabel.setFont(pageLabel.getFont().deriveFont(Font.PLAIN, 12f));

        prevBtn.addActionListener(e -> { currentPage--; renderPage(); });
        nextBtn.addActionListener(e -> { currentPage++; renderPage(); });

        JPanel navPanel = new JPanel(new BorderLayout(8, 0));
        navPanel.setBorder(new EmptyBorder(6, 12, 6, 12));
        navPanel.add(prevBtn,   BorderLayout.WEST);
        navPanel.add(pageLabel, BorderLayout.CENTER);
        navPanel.add(nextBtn,   BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(scroll,   BorderLayout.CENTER);
        getContentPane().add(navPanel, BorderLayout.SOUTH);

        setSize(520, 380);
        setMinimumSize(new Dimension(400, 280));
        setLocationRelativeTo(parent);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // Escape closes the dialog
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { dispose(); }
        });
    }

    /**
     * Loads fresh history from the DB and makes the dialog visible.
     * Safe to call on the EDT — the DB load is async.
     */
    public void open() {
        model.clear();
        allEntries = Collections.emptyList();
        currentPage = 0;
        pageLabel.setText("Loading...");
        prevBtn.setEnabled(false);
        nextBtn.setEnabled(false);

        setVisible(true);

        clipboardService.loadHistory(MAX_ENTRIES).thenAccept(entries -> {
            SwingUtilities.invokeLater(() -> {
                allEntries = entries;
                currentPage = 0;
                renderPage();
            });
        }).exceptionally(ex -> {
            log.error("Failed to load clipboard history", ex);
            SwingUtilities.invokeLater(() -> {
                model.clear();
                pageLabel.setText("Error loading history");
                prevBtn.setEnabled(false);
                nextBtn.setEnabled(false);
            });
            return null;
        });
    }

    // ---- Private helpers ----

    private void renderPage() {
        int total      = allEntries.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
        // Clamp page index
        currentPage = Math.max(0, Math.min(currentPage, totalPages - 1));

        int from = currentPage * PAGE_SIZE;
        int to   = Math.min(from + PAGE_SIZE, total);

        model.clear();
        if (total == 0) {
            pageLabel.setText("No history");
        } else {
            allEntries.subList(from, to).forEach(model::addElement);
            pageLabel.setText(String.format("Page %d / %d  (%d entries)",
                    currentPage + 1, totalPages, total));
        }

        prevBtn.setEnabled(currentPage > 0);
        nextBtn.setEnabled(currentPage < totalPages - 1);

        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
            list.requestFocusInWindow();
        }
    }

    private JList<ClipboardEntry> buildList() {
        JList<ClipboardEntry> l = new JList<>(model);
        l.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        l.setFixedCellHeight(ROW_HEIGHT);
        l.setCellRenderer(new EntryRenderer());
        l.setFocusable(true);

        l.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = l.locationToIndex(e.getPoint());
                if (idx >= 0) pasteEntry(model.get(idx));
            }
        });

        l.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    ClipboardEntry sel = l.getSelectedValue();
                    if (sel != null) pasteEntry(sel);
                }
            }
        });

        return l;
    }

    private void pasteEntry(ClipboardEntry entry) {
        dispose();
        Thread t = new Thread(() -> {
            try { Thread.sleep(80); } catch (InterruptedException ignored) {}
            pasteService.pasteText(entry.content());
        }, "paste-from-history");
        t.setDaemon(true);
        t.start();
    }

    // ---- Cell renderer ----

    private static class EntryRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ClipboardEntry entry) {
                // Global index across all pages is not available here; show 1-based row within page
                setText(String.format("  %s", entry.preview(PREVIEW_CHARS)));
                setFont(getFont().deriveFont(Font.PLAIN, 12f));
            }
            return this;
        }
    }
}
