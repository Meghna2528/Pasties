package com.pasties.ui;

import com.pasties.domain.AppConfig;
import com.pasties.repository.ConfigRepository;
import com.pasties.service.ClipboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Modal dialog for editing application settings.
 *
 * <p>Settings exposed:
 * <ul>
 *   <li>Max history size — dropdown, multiples of 10 up to 1000</li>
 *   <li>Paste delay in ms — dropdown, multiples of 10, 20–500</li>
 *   <li>Recent menu items per page — dropdown, multiples of 10, 10–150</li>
 *   <li>Popup history items per page — dropdown, multiples of 10, 10–100</li>
 *   <li>Entry TTL (days) — dropdown, multiples of 10, 10–3650</li>
 *   <li>Hotkey modifiers — Ctrl / Shift / Alt checkboxes</li>
 *   <li>Hotkey key — A–Z dropdown</li>
 *   <li>Start on login — checkbox</li>
 *   <li>Clear all history — button with confirmation</li>
 * </ul>
 *
 * <p>Clicking OK applies changes to {@link AppConfig} in-memory and persists
 * them via {@link ConfigRepository#saveAll(AppConfig)}.
 *
 * <p>Hotkey changes take effect on the next keystroke — no restart needed.
 * {@link com.pasties.hook.GlobalKeyboardHook#isHotkeyPressed} reads
 * {@link AppConfig} dynamically on every event.
 */
public class PreferencesDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(PreferencesDialog.class);

    private final AppConfig config;
    private final ClipboardService clipboardService;
    private final ConfigRepository configRepo;

    // Numeric dropdowns (multiples of 10)
    private JComboBox<Integer> historyCombo;
    private JComboBox<Integer> recentMenuCombo;
    private JComboBox<Integer> popupSizeCombo;
    private JComboBox<Integer> ttlDaysCombo;

    // Hotkey controls
    private JCheckBox ctrlCheck;
    private JCheckBox shiftCheck;
    private JCheckBox altCheck;
    private JComboBox<String> keyCombo;

    private JCheckBox loginCheckBox;

    public PreferencesDialog(Frame parent,
                              AppConfig config,
                              ClipboardService clipboardService,
                              ConfigRepository configRepo) {
        super(parent, "Preferences \u2014 Pasties", true);
        this.config = config;
        this.clipboardService = clipboardService;
        this.configRepo = configRepo;
        buildUI();
        pack();
        setMinimumSize(new Dimension(400, 0));
        setResizable(false);
        setLocationRelativeTo(parent);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private void buildUI() {
        // ---- Numeric dropdowns ----
        historyCombo    = buildStepCombo(10,    300, 10, config.getMaxHistorySize());
        recentMenuCombo = buildStepCombo(10,    150, 10, config.getRecentMenuSize());
        popupSizeCombo  = buildStepCombo(10,    100, 10, config.getPopupHistorySize());
        ttlDaysCombo    = buildStepCombo(90,    150, 10, config.getEntryTtlDays());

        // ---- Hotkey controls ----
        String mods = config.getHotkeyModifiers().toLowerCase();
        ctrlCheck  = new JCheckBox("Ctrl / Cmd", mods.contains("ctrl"));
        shiftCheck = new JCheckBox("Shift", mods.contains("shift"));
        altCheck   = new JCheckBox("Alt",   mods.contains("alt"));

        String[] letters = IntStream.rangeClosed('A', 'Z')
                .mapToObj(c -> String.valueOf((char) c))
                .toArray(String[]::new);
        keyCombo = new JComboBox<>(letters);
        keyCombo.setSelectedItem(config.getHotkeyKey().toUpperCase());

        JPanel modifiersPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        modifiersPanel.add(ctrlCheck);
        modifiersPanel.add(shiftCheck);
        modifiersPanel.add(altCheck);

        // ---- Login checkbox ----
        loginCheckBox = new JCheckBox("Start Pasties at login", config.isStartOnLogin());

        // ---- Layout ----
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));

        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(4, 0, 4, 12);
        lc.gridx = 0;

        GridBagConstraints fc = new GridBagConstraints();
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.insets = new Insets(4, 0, 4, 0);
        fc.gridx = 1;

        int row = 0;
        lc.gridy = row; fields.add(new JLabel("Max history size:"), lc);
        fc.gridy = row; fields.add(historyCombo, fc);

        row++;
        lc.gridy = row; fields.add(new JLabel("Recent menu items:"), lc);
        fc.gridy = row; fields.add(recentMenuCombo, fc);

        row++;
        lc.gridy = row; fields.add(new JLabel("Popup history items:"), lc);
        fc.gridy = row; fields.add(popupSizeCombo, fc);

        row++;
        lc.gridy = row; fields.add(new JLabel("Entry TTL (days):"), lc);
        fc.gridy = row; fields.add(ttlDaysCombo, fc);

        row++;
        lc.gridy = row; fields.add(new JLabel("Hotkey modifiers:"), lc);
        fc.gridy = row; fields.add(modifiersPanel, fc);

        row++;
        lc.gridy = row; fields.add(new JLabel("Hotkey key:"), lc);
        fc.gridy = row; fields.add(keyCombo, fc);

        row++;
        GridBagConstraints cbConstraints = new GridBagConstraints();
        cbConstraints.gridx = 0; cbConstraints.gridy = row;
        cbConstraints.gridwidth = 2;
        cbConstraints.anchor = GridBagConstraints.WEST;
        cbConstraints.insets = new Insets(4, 0, 4, 0);
        fields.add(loginCheckBox, cbConstraints);

        // ---- Button row ----
        JButton clearHistoryBtn = new JButton("Clear History Now");
        clearHistoryBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Clear all clipboard history? This cannot be undone.",
                    "Confirm Clear", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                clipboardService.clearHistory()
                        .thenAccept(v -> log.info("Clipboard history cleared from Preferences"))
                        .exceptionally(ex -> { log.error("Error clearing history", ex); return null; });
            }
        });

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());

        JButton okBtn = new JButton("OK");
        okBtn.addActionListener(e -> {
            applyAndSave();
            dispose();
        });
        getRootPane().setDefaultButton(okBtn);

        JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightBtns.add(cancelBtn);
        rightBtns.add(okBtn);

        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBorder(BorderFactory.createEmptyBorder(4, 16, 12, 16));
        bottomBar.add(clearHistoryBtn, BorderLayout.WEST);
        bottomBar.add(rightBtns, BorderLayout.EAST);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(fields, BorderLayout.CENTER);
        getContentPane().add(bottomBar, BorderLayout.SOUTH);
    }

    private void applyAndSave() {
        config.setMaxHistorySize((Integer) historyCombo.getSelectedItem());
        config.setRecentMenuSize((Integer) recentMenuCombo.getSelectedItem());
        config.setPopupHistorySize((Integer) popupSizeCombo.getSelectedItem());
        config.setEntryTtlDays((Integer)   ttlDaysCombo.getSelectedItem());
        config.setStartOnLogin(loginCheckBox.isSelected());

        List<String> modParts = new ArrayList<>();
        if (ctrlCheck.isSelected())  modParts.add("ctrl");
        if (shiftCheck.isSelected()) modParts.add("shift");
        if (altCheck.isSelected())   modParts.add("alt");
        config.setHotkeyModifiers(String.join("+", modParts));
        config.setHotkeyKey((String) keyCombo.getSelectedItem());

        configRepo.saveAll(config)
                .exceptionally(ex -> { log.error("Error persisting preferences", ex); return null; });
        log.info("Preferences saved — hotkey: {}+{}", config.getHotkeyModifiers(), config.getHotkeyKey());
    }

    // ---- Helpers ----

    /**
     * Builds a combo box pre-populated with every multiple of {@code step} in
     * [{@code min}, {@code max}]. The initial selection is the largest value
     * in the list that is ≤ {@code currentValue} (falls back to the first item).
     */
    private JComboBox<Integer> buildStepCombo(int min, int max, int step, int currentValue) {
        List<Integer> values = new ArrayList<>();
        for (int v = min; v <= max; v += step) {
            values.add(v);
        }
        JComboBox<Integer> combo = new JComboBox<>(values.toArray(new Integer[0]));
        int selected = values.stream()
                .filter(v -> v <= currentValue)
                .reduce((a, b) -> b)
                .orElse(values.get(0));
        combo.setSelectedItem(selected);
        return combo;
    }
}
