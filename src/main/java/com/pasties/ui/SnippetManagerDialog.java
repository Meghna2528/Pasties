package com.pasties.ui;

import com.pasties.domain.Snippet;
import com.pasties.service.SnippetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Modal dialog for managing named snippets (add / edit / delete).
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────┐
 * │  Key         │  Value (preview)         │  Description   │
 * │──────────────────────────────────────────────────────────│
 * │  /addr       │  123 Main St…            │  Home address  │
 * │  /sig        │  Best regards, Ajay      │  Email sig     │
 * ├──────────────────────────────────────────────────────────┤
 * │  [Add]  [Edit]  [Delete]                      [Close]    │
 * └──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>All snippet mutations go through {@link SnippetService}, which updates
 * both the SQLite database and the in-memory ConcurrentHashMap atomically.
 */
public class SnippetManagerDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(SnippetManagerDialog.class);

    private final SnippetService snippetService;
    private final SnippetTableModel tableModel;
    private final JTable table;

    public SnippetManagerDialog(Frame parent, SnippetService snippetService) {
        super(parent, "Snippet Manager", true);
        this.snippetService = snippetService;
        this.tableModel = new SnippetTableModel(snippetService.getAllCached());
        this.table = buildTable();
        buildUI();
        setSize(640, 400);
        setMinimumSize(new Dimension(480, 280));
        setLocationRelativeTo(parent);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        EscapeToClose.install(this);
    }

    private JTable buildTable() {
        JTable t = new JTable(tableModel);
        t.setRowHeight(26);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setFillsViewportHeight(true);
        t.getColumnModel().getColumn(0).setPreferredWidth(90);
        t.getColumnModel().getColumn(1).setPreferredWidth(330);
        t.getColumnModel().getColumn(2).setPreferredWidth(160);

        // Double-click row to open edit dialog
        t.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && t.getSelectedRow() >= 0) {
                    showEditDialog(tableModel.getSnippetAt(t.getSelectedRow()));
                }
            }
        });
        return t;
    }

    private void buildUI() {
        JScrollPane scroll = new JScrollPane(table);

        JButton addBtn    = new JButton("Add");
        JButton editBtn   = new JButton("Edit");
        JButton deleteBtn = new JButton("Delete");
        JButton closeBtn  = new JButton("Close");

        addBtn.addActionListener(e -> showEditDialog(null));

        editBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) showEditDialog(tableModel.getSnippetAt(row));
        });

        deleteBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            Snippet s = tableModel.getSnippetAt(row);
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Delete snippet '/" + s.keyName() + "'?",
                    "Confirm Delete", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                snippetService.deleteSnippet(s.keyName())
                        .thenAccept(v -> SwingUtilities.invokeLater(this::refreshTable))
                        .exceptionally(ex -> {
                            log.error("Delete failed for snippet '{}'", s.keyName(), ex);
                            return null;
                        });
            }
        });

        closeBtn.addActionListener(e -> dispose());

        JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftBtns.add(addBtn);
        leftBtns.add(editBtn);
        leftBtns.add(deleteBtn);

        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        bottomBar.add(leftBtns, BorderLayout.WEST);
        bottomBar.add(closeBtn, BorderLayout.EAST);

        JLabel hint = new JLabel("  Type /<key> in any app to expand a snippet.");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 11f));
        hint.setForeground(Color.GRAY);

        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(hint, BorderLayout.NORTH);
        getContentPane().add(bottomBar, BorderLayout.SOUTH);
    }

    private void showEditDialog(Snippet existing) {
        boolean isNew = existing == null;

        JTextField keyField  = new JTextField(isNew ? "" : existing.keyName(), 18);
        JTextArea  valueArea = new JTextArea(isNew ? "" : existing.value(), 6, 32);
        JTextField descField = new JTextField(isNew ? "" :
                (existing.description() != null ? existing.description() : ""), 32);

        valueArea.setLineWrap(true);
        valueArea.setWrapStyleWord(true);
        keyField.setEnabled(isNew); // Key is immutable after creation

        Object[] fields = {
                "Key (used as /<key>):", keyField,
                "Expansion value:", new JScrollPane(valueArea),
                "Description (optional):", descField
        };

        int result = JOptionPane.showConfirmDialog(this, fields,
                isNew ? "Add Snippet" : "Edit Snippet — /" + existing.keyName(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        String key   = keyField.getText().strip();
        String value = valueArea.getText();
        String desc  = descField.getText().strip();

        snippetService.saveSnippet(key, value, desc.isEmpty() ? null : desc)
                .thenAccept(v -> SwingUtilities.invokeLater(this::refreshTable))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(this,
                                    "Invalid input: " + ex.getCause().getMessage(),
                                    "Error", JOptionPane.ERROR_MESSAGE));
                    return null;
                });
    }

    private void refreshTable() {
        tableModel.setSnippets(snippetService.getAllCached());
        tableModel.fireTableDataChanged();
    }

    // ---- Table model ----

    private static class SnippetTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Key", "Value", "Description"};
        private List<Snippet> snippets;

        SnippetTableModel(List<Snippet> snippets) {
            this.snippets = snippets;
        }

        void setSnippets(List<Snippet> s) {
            this.snippets = s;
        }

        Snippet getSnippetAt(int row) {
            return snippets.get(row);
        }

        @Override public int getRowCount()    { return snippets.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            Snippet s = snippets.get(row);
            return switch (col) {
                case 0 -> "/" + s.keyName();
                case 1 -> s.value().replace('\n', ' ').replace('\r', ' ');
                case 2 -> s.description() != null ? s.description() : "";
                default -> "";
            };
        }
    }
}
