package com.pasties.domain;

/**
 * Mutable application configuration snapshot.
 *
 * <p>Loaded from {@link com.pasties.repository.ConfigRepository} at startup.
 * Changes made via the Preferences dialog are persisted immediately via the
 * same repository. The object is accessed from multiple threads; however,
 * individual field reads are atomic for int and boolean primitives, and
 * configuration changes are infrequent (user-initiated), so no further
 * synchronization is required.
 */
public class AppConfig {

    private int maxHistorySize = 200;
    private String hotkeyModifiers = "ctrl+shift";
    private String hotkeyKey = "V";
    private boolean startOnLogin = false;
    private String snippetPrefix = "/";
    private int recentMenuSize = 10;   // items per page in menu bar Recent submenu (1–150)
    private int popupHistorySize = 50; // items per page in Ctrl+Shift+V popup (1–100)
    private int entryTtlDays = 90;     // entries older than this are pruned on next upsert

    public int getMaxHistorySize() { return maxHistorySize; }
    public void setMaxHistorySize(int maxHistorySize) { this.maxHistorySize = maxHistorySize; }

    public String getHotkeyModifiers() { return hotkeyModifiers; }
    public void setHotkeyModifiers(String hotkeyModifiers) { this.hotkeyModifiers = hotkeyModifiers; }

    public String getHotkeyKey() { return hotkeyKey; }
    public void setHotkeyKey(String hotkeyKey) { this.hotkeyKey = hotkeyKey; }

    public boolean isStartOnLogin() { return startOnLogin; }
    public void setStartOnLogin(boolean startOnLogin) { this.startOnLogin = startOnLogin; }

    public String getSnippetPrefix() { return snippetPrefix; }
    public void setSnippetPrefix(String snippetPrefix) { this.snippetPrefix = snippetPrefix; }

    public int getRecentMenuSize() { return recentMenuSize; }
    public void setRecentMenuSize(int recentMenuSize) { this.recentMenuSize = recentMenuSize; }

    public int getPopupHistorySize() { return popupHistorySize; }
    public void setPopupHistorySize(int popupHistorySize) { this.popupHistorySize = popupHistorySize; }

    public int getEntryTtlDays() { return entryTtlDays; }
    public void setEntryTtlDays(int entryTtlDays) { this.entryTtlDays = entryTtlDays; }
}
