package com.pasties.ui;

import com.pasties.service.MetricsService;
import com.pasties.service.MetricsService.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Non-modal dialog that displays a live performance dashboard for Pasties.
 *
 * <h2>Metrics displayed</h2>
 * <table border="1">
 *   <tr><th>Metric</th><th>Description</th></tr>
 *   <tr><td>Avg paste speed</td><td>ms from trigger to Cmd+V release (history pastes)</td></tr>
 *   <tr><td>Total pastes</td><td>number of paste operations recorded this session</td></tr>
 *   <tr><td>Avg snippet expansion</td><td>ms from trigger detection to paste completion</td></tr>
 *   <tr><td>Total expansions</td><td>number of snippet expansions recorded this session</td></tr>
 *   <tr><td>JVM heap used</td><td>current Java heap allocation in KB</td></tr>
 *   <tr><td>Process CPU</td><td>JVM process CPU load percentage</td></tr>
 *   <tr><td>DB file size</td><td>size of the SQLite database file</td></tr>
 *   <tr><td>Clipboard payload</td><td>total bytes of cached clipboard entries</td></tr>
 *   <tr><td>Snippet payload</td><td>total bytes of all snippet values</td></tr>
 * </table>
 *
 * <p>The dashboard auto-refreshes every 2 seconds while visible. The refresh
 * is handled on a daemon thread; UI updates are dispatched via
 * {@link SwingUtilities#invokeLater}.
 */
public class MetricsDashboardDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(MetricsDashboardDialog.class);
    private static final int REFRESH_INTERVAL_SECS = 2;

    private final MetricsService metricsService;

    // Table rows (index → metric label)
    private static final String[] ROW_LABELS = {
            "Avg paste speed (ms)",
            "Total pastes",
            "Avg snippet expansion (ms)",
            "Total snippet expansions",
            "JVM heap used",
            "Process CPU load",
            "DB file size",
            "Clipboard payload (cached)",
            "Snippet payload (all keys)"
    };

    private final DefaultTableModel tableModel;
    private final JLabel statusLabel;

    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "metrics-refresher");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> refreshTask;

    public MetricsDashboardDialog(Frame parent, MetricsService metricsService) {
        super(parent, "Performance Dashboard — Pasties", false); // non-modal
        this.metricsService = metricsService;
        this.tableModel = buildTableModel();
        this.statusLabel = new JLabel("  Refreshes every " + REFRESH_INTERVAL_SECS + "s");
        buildUI();
        setSize(500, 380);
        setMinimumSize(new Dimension(400, 300));
        setLocationRelativeTo(parent);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        EscapeToClose.install(this);

        // Start/stop the refresh task with dialog visibility
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                startRefresh();
            }
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                stopRefresh();
            }
        });
    }

    // ---- Public API ----

    /** Shows the dialog (creates if needed) and starts live refresh. */
    public void showDashboard() {
        refresh(); // populate immediately before showing
        setVisible(true);
    }

    // ---- Build helpers ----

    private DefaultTableModel buildTableModel() {
        DefaultTableModel m = new DefaultTableModel(new String[]{"Metric", "Value"}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        for (String label : ROW_LABELS) {
            m.addRow(new Object[]{label, "—"});
        }
        return m;
    }

    private void buildUI() {
        JTable table = new JTable(tableModel);
        table.setRowHeight(26);
        table.setFocusable(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(240);
        table.setShowGrid(true);
        table.setGridColor(Color.LIGHT_GRAY);

        JScrollPane scroll = new JScrollPane(table);

        JPanel speedSection = new JPanel(new BorderLayout());
        speedSection.setBorder(new TitledBorder("Operation Speed"));
        speedSection.add(scroll, BorderLayout.CENTER);

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));
        statusLabel.setForeground(Color.GRAY);

        JButton refreshBtn = new JButton("Refresh Now");
        refreshBtn.addActionListener(e -> refresh());

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());

        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));
        bottomBar.add(statusLabel, BorderLayout.WEST);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btns.add(refreshBtn);
        btns.add(closeBtn);
        bottomBar.add(btns, BorderLayout.EAST);

        getContentPane().setLayout(new BorderLayout(0, 4));
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(bottomBar, BorderLayout.SOUTH);
    }

    private void startRefresh() {
        refreshTask = refresher.scheduleAtFixedRate(
                this::refresh, REFRESH_INTERVAL_SECS, REFRESH_INTERVAL_SECS, TimeUnit.SECONDS);
    }

    private void stopRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
        }
    }

    private void refresh() {
        try {
            Snapshot s = metricsService.snapshot();
            SwingUtilities.invokeLater(() -> populateTable(s));
        } catch (Exception e) {
            log.error("Error refreshing metrics dashboard", e);
        }
    }

    private void populateTable(Snapshot s) {
        String cpuText = s.processCpuLoad() < 0
                ? "N/A"
                : String.format("%.1f%%", s.processCpuLoad() * 100);

        Object[][] values = {
                {ROW_LABELS[0], s.pasteCount() == 0 ? "No pastes yet" : s.avgPasteMs() + " ms"},
                {ROW_LABELS[1], s.pasteCount()},
                {ROW_LABELS[2], s.snippetExpansionCount() == 0 ? "No expansions yet" : s.avgSnippetExpansionMs() + " ms"},
                {ROW_LABELS[3], s.snippetExpansionCount()},
                {ROW_LABELS[4], formatBytes(s.heapUsedBytes())},
                {ROW_LABELS[5], cpuText},
                {ROW_LABELS[6], formatBytes(s.dbFileSizeBytes())},
                {ROW_LABELS[7], formatBytes(s.clipboardPayloadBytes()) + " (" + countLabel(s.clipboardPayloadBytes()) + ")"},
                {ROW_LABELS[8], formatBytes(s.snippetPayloadBytes())}
        };

        for (int i = 0; i < values.length; i++) {
            tableModel.setValueAt(values[i][1], i, 1);
        }

        statusLabel.setText("  Last refresh: " + java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
    }

    // ---- Formatting helpers ----

    private static String formatBytes(long bytes) {
        if (bytes < 1024)       return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }

    private static String countLabel(long bytes) {
        // Rough estimate: average English word is ~5 bytes
        return bytes == 0 ? "0 entries" : "~" + (bytes / 5) + " words";
    }
}
