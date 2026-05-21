package com.pasties.ui;

import com.pasties.domain.ClipboardEntry;
import com.pasties.service.PasteService;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Searchable floating picker for clipboard history, opened via the global hotkey.
 */
public class ClipboardSearchPicker extends JDialog {

    private static final int PREVIEW_CHARS = 120;
    private static final int MAX_RESULTS = 100;

    private final List<ClipboardEntry> allEntries;
    private final PasteService pasteService;

    private final JTextField searchField = new JTextField();
    private final DefaultListModel<ClipboardEntry> listModel = new DefaultListModel<>();
    private final JList<ClipboardEntry> resultList = new JList<>(listModel);

    public ClipboardSearchPicker(Window owner,
                                 List<ClipboardEntry> entries,
                                 PasteService pasteService) {
        super(owner);
        this.allEntries = List.copyOf(entries);
        this.pasteService = pasteService;

        setUndecorated(true);
        setAlwaysOnTop(true);
        setModal(false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(720, 420);
        setLocationRelativeTo(null);

        searchField.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        searchField.putClientProperty("JTextField.placeholderText", "Search clipboard history...");

        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = new JLabel(value.preview(PREVIEW_CHARS));
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            if (isSelected) {
                label.setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
            } else {
                label.setBackground(list.getBackground());
                label.setForeground(list.getForeground());
            }
            return label;
        });

        setLayout(new BorderLayout(8, 8));
        add(searchField, BorderLayout.NORTH);
        add(new JScrollPane(resultList), BorderLayout.CENTER);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshResults();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshResults();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshResults();
            }
        });

        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_ESCAPE -> {
                        dispose();
                        e.consume();
                    }
                    case KeyEvent.VK_ENTER -> {
                        pasteSelected();
                        e.consume();
                    }
                    case KeyEvent.VK_DOWN -> {
                        moveSelection(1);
                        e.consume();
                    }
                    case KeyEvent.VK_UP -> {
                        moveSelection(-1);
                        e.consume();
                    }
                    default -> { }
                }
            }
        });

        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    pasteSelected();
                }
            }
        });
    }

    public void showPicker() {
        refreshResults();
        setVisible(true);
        searchField.requestFocusInWindow();
        searchField.selectAll();
    }

    private void refreshResults() {
        String query = searchField.getText().trim().toLowerCase();
        listModel.clear();
        for (ClipboardEntry entry : allEntries) {
            if (query.isEmpty() || entry.content().toLowerCase().contains(query)) {
                listModel.addElement(entry);
            }
            if (listModel.size() >= MAX_RESULTS) {
                break;
            }
        }
        if (!listModel.isEmpty()) {
            resultList.setSelectedIndex(0);
        }
    }

    private void moveSelection(int delta) {
        int size = listModel.getSize();
        if (size == 0) {
            return;
        }
        int current = resultList.getSelectedIndex();
        if (current < 0) {
            current = 0;
        }
        int next = Math.max(0, Math.min(size - 1, current + delta));
        resultList.setSelectedIndex(next);
        resultList.ensureIndexIsVisible(next);
    }

    private void pasteSelected() {
        ClipboardEntry selected = resultList.getSelectedValue();
        if (selected == null) {
            return;
        }
        dispose();
        Thread t = new Thread(
                () -> pasteService.pasteText(selected.content()),
                "paste-from-search-picker"
        );
        t.setDaemon(true);
        t.start();
    }
}
