# Project Details

Detailed setup, usage, troubleshooting, and architecture notes for [Pasties](README.md).

---

## Permissions

Pasties checks for the two required macOS permissions on launch and opens System Settings automatically if either is missing.

1. **Input Monitoring** — required for native global key observation used by snippet detection.
   `System Settings -> Privacy & Security -> Input Monitoring -> Add Pasties`

2. **Accessibility** — required for simulating keystrokes (paste, backspace).
   `System Settings -> Privacy & Security -> Accessibility -> Add Pasties`

The history menu hotkey is registered through macOS Carbon `RegisterEventHotKey` via JNA Platform, which is more reliable than relying on JNativeHook for app-level shortcuts. While the hotkey menu is open, Pasties also registers a temporary native Escape hotkey so the menu can close even when keyboard focus remains in the previous app. Snippet triggers use a native macOS Quartz event tap, with JNativeHook kept as a fallback if native hooks cannot be registered.

After granting both permissions, restart Pasties.

---

## Building a macOS App Bundle

For new users installing from source, use:

```bash
scripts/install.sh
```

The installer checks for a full JDK (`java` + `jpackage`) and Maven. If either is missing, it can install the Homebrew dependency or accept a custom JDK path before building and launching `/Applications/Pasties.app`.

Useful options:

```bash
scripts/install.sh --yes
scripts/install.sh --run-tests
```

For local development, the full reinstall flow is:

```bash
scripts/reinstall-and-run.sh
```

This stops any running Pasties instance, removes old build output and `/Applications/Pasties.app`, runs tests, rebuilds the fat JAR, creates a fresh app bundle, ad-hoc signs it with the required JVM entitlements, installs it to `/Applications`, updates the local development DB hotkey to `Ctrl/Cmd+Shift+S`, refreshes the signature, and launches it.

Useful options:

```bash
scripts/reinstall-and-run.sh --skip-tests
scripts/reinstall-and-run.sh --no-launch
scripts/reinstall-and-run.sh --reset-permissions
```

`--reset-permissions` explicitly resets macOS Accessibility/Input Monitoring grants with `tccutil`. Do not use it for ordinary rebuilds unless you want to re-grant permissions.

```bash
# 1. Package the fat JAR
mvn package -q

# 2. Bundle with jpackage (requires JDK 21 jpackage on PATH)
jpackage \
  --input target \
  --main-jar pasties-1.0.0.jar \
  --main-class com.pasties.Main \
  --name Pasties \
  --type app-image \
  --dest target/dist \
  --icon src/main/resources/icon.icns \
  --java-options "-Xmx64m" \
  --java-options "-Dapple.awt.UIElement=true" \
  --mac-package-name Pasties \
  --mac-package-identifier com.pasties
```

The resulting `target/dist/Pasties.app` can be moved to `/Applications`.

---

## Usage

### Clipboard History Menu

Press **Ctrl+Shift+S** or **⌘+Shift+S** in any application to open a Clipy-style history menu at the cursor.

- **Mouse-first interaction** — hover or click range rows and items. Full arrow-key navigation is not supported yet.
- **History ranges** — open grouped submenus such as `1 - 10` and `11 - 20`
- **Clipboard item** — click any item to paste it into the previously active app
- **Escape** — closes the open hotkey menu through a temporary native macOS hotkey, so it works even when focus remains in the previous app
- **Clear History** — remove all saved clipboard entries
- **Edit Snippets... / Preferences... / Quit Pasties** — available directly from the hotkey menu

### Searchable History Picker

Click **History** in the menu bar to open the searchable picker. It loads recent entries from the database, filters as you type, and pastes the selected item with Enter or double-click.

### Snippet Expansion

1. Open the **Snippets** dialog from the menu bar icon.
2. Click **Add** and enter a key (e.g. `addr`) and a value (e.g. `123 Main St, City, State`).
3. In any application, type `/addr` — Pasties erases `/addr` and types your snippet value automatically. Snippet keys are case-insensitive, so `/ADDR`, `/Addr`, and `/addr` match the same snippet.

**Key rules:**
- Keys must match `[a-zA-Z0-9_-]+` (letters, digits, dashes, underscores).
- Keys are normalized for matching, so casing does not matter when typing a trigger.
- The prefix character is `/` by default (configurable in the database).

### Menu Bar

| Menu item | Action |
|---|---|
| **Recent** | Shows the latest clipboard entries for quick mouse-based paste (configured by `recent_menu_size`, default 10). |
| **History** | Open the searchable clipboard picker. |
| **Snippets** | Open the snippet manager to add, edit, or delete snippets. |
| **Performance Dashboard** | Open the live metrics dashboard. |
| **Preferences** | Configure history size, page sizes, TTL, hotkey, and start-on-login. |
| **Quit Pasties** | Gracefully shut down the app. |

---

## Performance Dashboard

Open via **Performance Dashboard** in the menu bar. Refreshes automatically every 2 seconds.

| Metric | Description |
|---|---|
| Avg paste speed (ms) | Wall-clock time per clipboard history paste |
| Total pastes | Paste operations recorded this session |
| Avg snippet expansion (ms) | Wall-clock time per snippet expansion (backspaces + paste) |
| Total snippet expansions | Expansion operations recorded this session |
| JVM heap used | Current Java heap allocation |
| Process CPU load | Pasties's JVM CPU usage percentage |
| DB file size | SQLite database size on disk |
| Clipboard payload | Total UTF-8 bytes of cached clipboard entries |
| Snippet payload | Total UTF-8 bytes of all snippet values |

---

## Configuration

All settings are stored in the SQLite `config` table and editable via **Preferences** in the menu bar.

| Key | Default | UI range | Description |
|---|---|---|---|
| `max_history_size` | `200` | 10-300 | Maximum clipboard entries to keep |
| `hotkey_modifiers` | `ctrl+shift` | Ctrl/Cmd, Shift, Alt checkboxes | Modifier keys for the history menu hotkey. "Ctrl/Cmd" means either key triggers the hotkey. |
| `hotkey_key` | `S` | A-Z dropdown | Trigger key for the history menu hotkey |
| `snippet_prefix` | `/` | — | Character that starts a snippet trigger (DB only) |
| `recent_menu_size` | `10` | 10-150 | Maximum entries shown in the Recent tray submenu |
| `popup_history_size` | `50` | 10-100 | Legacy setting for the old paginated popup |
| `entry_ttl_days` | `90` | 90-150 | Days before a clipboard entry is pruned |
| `start_on_login` | `false` | Checkbox | Auto-start at login (login item setup is manual) |

> **Paste delay** is fixed at 80 ms in code and not user-configurable. This is optimised for macOS responsiveness without perceptible lag.

You can also edit the database directly with any SQLite browser (e.g. [DB Browser for SQLite](https://sqlitebrowser.org/)).

---

## Running Tests

```bash
mvn test
```

The test suite covers:
- **SHA-256 deduplication** in `ClipboardService`
- **Key validation and CRUD** in `SnippetService`
- **Snippet detection state machine** in `SnippetDetectionTest` (unit-tested without native hooks)
- **History hotkey matching** in `GlobalKeyboardHookHotkeyTest`

---

## Project Structure

```
src/main/java/com/pasties/
├── Main.java                        Entry point, dependency wiring
├── domain/                          Domain models
│   ├── ClipboardEntry.java          Record
│   ├── Snippet.java                 Record
│   └── AppConfig.java               Mutable config snapshot
├── infrastructure/                  Cross-cutting concerns
│   ├── DatabaseManager.java         SQLite connection + schema migration
│   ├── PermissionChecker.java       macOS permission check via JNA
│   └── AppLifecycle.java            LIFO shutdown hook coordinator
├── repository/                      SQLite CRUD (one ExecutorService each)
│   ├── ClipboardRepository.java
│   ├── SnippetRepository.java
│   └── ConfigRepository.java
├── service/                         Business logic + in-memory caches
│   ├── ClipboardService.java        History, cache, change listeners
│   ├── SnippetService.java          Snippet CRUD + ConcurrentHashMap lookup
│   ├── PasteService.java            AWT Robot paste + snippet expansion
│   └── MetricsService.java          Performance metrics collection
├── hook/                            System-level event listeners
│   ├── ClipboardMonitor.java        500 ms clipboard poll
│   ├── GlobalKeyboardHook.java      JNativeHook fallback listener
│   ├── MacGlobalHotkey.java         Carbon RegisterEventHotKey history shortcut
│   ├── MacSnippetEventTap.java      Quartz event tap snippet detection
│   └── MacEscapeHotkey.java         Temporary native Escape shortcut for popups
└── ui/                              Swing/AWT UI components
    ├── MenuBarApp.java              System tray + top-N Recent submenu
    ├── ClipboardHistoryMenu.java    Clipy-style cascading menu (global hotkey)
    ├── ClipboardSearchPicker.java   Searchable floating picker (tray History)
    ├── ClipboardHistoryPopup.java   Legacy paginated popup (unused)
    ├── HistoryDialog.java           Legacy modal history browser (DB-backed)
    ├── SnippetManagerDialog.java    Add/edit/delete snippets
    ├── PreferencesDialog.java       Settings editor + clear history
    └── MetricsDashboardDialog.java  Live performance dashboard
```

---

## Thread Model

| Thread | Purpose |
|---|---|
| **EDT** | All Swing/AWT UI: popup show/hide, dialogs |
| **Carbon event dispatcher** | Native macOS history-menu hotkey and temporary popup Escape hotkey via `RegisterEventHotKey` |
| **`mac-snippet-event-tap`** | Native macOS Quartz event tap for typed snippet triggers |
| **JNativeHook native thread** | Fallback key listener if native macOS hooks cannot be registered |
| **`paste-executor`** | `PasteService.expandSnippet()` — Robot blocks here (snippet expansion only) |
| **`paste-executor-mac-event-tap`** | Snippet expansion requests from the native macOS event tap |
| **Ephemeral daemon threads** | `paste-from-history-menu`, `paste-from-search-picker`, `paste-from-menu`, `paste-from-history` — one per clipboard paste operation |
| **`clipboard-monitor`** | 500 ms clipboard polling |
| **`db-clipboard`** | All clipboard table SQL |
| **`db-snippet`** | All snippets table SQL |
| **`db-config`** | All config table SQL |
| **`metrics-refresher`** | Dashboard auto-refresh |

---

## Dependencies

| Library | Version | License |
|---|---|---|
| [JNativeHook](https://github.com/kwhat/jnativehook) | 2.2.2 | LGPL-3.0; fallback key listener and Input Monitoring check |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | 3.47.1.0 | Apache-2.0 |
| [JNA](https://github.com/java-native-access/jna) | 5.18.1 | Apache-2.0 |
| [JNA Platform](https://github.com/java-native-access/jna) | 5.18.1 | Apache-2.0 |
| [SLF4J](https://www.slf4j.org/) | 2.0.16 | MIT |
| [Logback](https://logback.qos.ch/) | 1.5.12 | EPL-1.0 |

---

## Logs

Application logs are written to `~/Library/Logs/Pasties/pasties.log` with 7-day rolling retention (max 50 MB total). Console output is also enabled during development.

---

## License

MIT

---

## Troubleshooting

### macOS Permissions Keep Asking Even After Pasties Is Enabled

During local development, macOS may keep showing the Accessibility/Input Monitoring prompt even when **Pasties** appears enabled in System Settings. This usually happens when TCC has stale permission entries from an older app bundle, an unsigned rebuilt app, or multiple copies of `Pasties.app`.

Use this reset flow after installing the latest app bundle to `/Applications`:

```bash
tccutil reset Accessibility com.pasties && \
tccutil reset ListenEvent com.pasties && \
codesign --force --deep --sign - --entitlements packaging/entitlements.plist /Applications/Pasties.app
```

Then re-enable Pasties manually:

1. `System Settings -> Privacy & Security -> Accessibility -> Pasties -> On`
2. `System Settings -> Privacy & Security -> Input Monitoring -> Pasties -> On`
3. Restart Pasties:

```bash
pkill -f Pasties || true
open /Applications/Pasties.app
```

To avoid duplicate/stale permission entries, keep only one installed copy of the app:

```bash
mdfind "kMDItemCFBundleIdentifier == 'com.pasties'"
```

Expected result:

```text
/Applications/Pasties.app
```

If another copy appears, such as `target/dist/Pasties.app`, remove it and launch only the `/Applications` copy.

---

## Project Structure and UMLs

The diagrams below visualize how `Main.java` wires the layered packages, how startup proceeds, and how clipboard/snippet data flows at runtime. Rendered automatically on GitHub; use any Mermaid-compatible viewer locally.

> **Note:** `PasteService` uses a fixed 80 ms paste delay in code. The `paste_delay_ms` DB key is no longer written or read by the app.

### Package Map

| Package | Classes | Role |
|---|---|---|
| `com.pasties` | `Main` | Composition root |
| `domain/` | 3 | `AppConfig` (mutable) + records |
| `infrastructure/` | 3 | DB, permissions, shutdown |
| `repository/` | 3 | SQLite CRUD (per-table executors) |
| `service/` | 4 | History + listeners, snippets, paste, metrics |
| `hook/` | 5 | Clipboard poll, native history hotkey, native snippet event tap, native popup Escape hotkey, fallback key listener |
| `ui/` | 8 | Tray, Clipy-style menu, search picker, history dialog, settings dialogs |
| `test/` | 4 | Unit tests (services, snippet FSM, hotkey matching) |

### Layered Architecture

```mermaid
flowchart TB
    subgraph External["macOS & third-party"]
        macOS["macOS APIs<br/>Clipboard · Robot · System Tray"]
        carbon["Carbon<br/>RegisterEventHotKey"]
        quartz["Quartz Event Tap<br/>CGEventTapCreate"]
        perms["Permissions<br/>Accessibility · Input Monitoring"]
        jnh["JNativeHook<br/>fallback keyboard events"]
        sqlite["SQLite file<br/>~/Library/Application Support/Pasties/"]
        jna["JNA / JNA Platform<br/>AXIsProcessTrusted · Carbon · Quartz"]
    end

    subgraph Entry["Entry"]
        Main["Main.java<br/>wiring & startup"]
    end

    subgraph UI["ui/ — Swing/AWT (EDT)"]
        MenuBar["MenuBarApp<br/>tray + top-N Recent"]
        HistoryMenu["ClipboardHistoryMenu<br/>hotkey cascading menu"]
        SearchPicker["ClipboardSearchPicker<br/>tray History search picker"]
        HistDlg["HistoryDialog<br/>legacy full history"]
        SnippetDlg["SnippetManagerDialog"]
        PrefsDlg["PreferencesDialog"]
        MetricsDlg["MetricsDashboardDialog"]
    end

    subgraph Hooks["hook/ — background threads"]
        ClipMon["ClipboardMonitor<br/>500ms poll"]
        MacHotkey["MacGlobalHotkey<br/>native history shortcut"]
        EscHotkey["MacEscapeHotkey<br/>temporary popup Escape shortcut"]
        MacSnippet["MacSnippetEventTap<br/>native snippet FSM"]
        KeyHook["GlobalKeyboardHook<br/>fallback snippet FSM"]
    end

    subgraph Services["service/ — business logic"]
        ClipSvc["ClipboardService<br/>cache + change listeners"]
        SnipSvc["SnippetService<br/>CRUD + map cache"]
        PasteSvc["PasteService<br/>Robot paste, fixed 80ms delay"]
        MetricsSvc["MetricsService<br/>timings + payloads"]
    end

    subgraph Repos["repository/ — async SQL"]
        ClipRepo["ClipboardRepository"]
        SnipRepo["SnippetRepository"]
        ConfigRepo["ConfigRepository"]
    end

    subgraph Infra["infrastructure/"]
        DB["DatabaseManager<br/>schema migration"]
        PermChk["PermissionChecker"]
        Life["AppLifecycle<br/>shutdown hooks"]
    end

    subgraph Domain["domain/ — records & config"]
        ClipEntry["ClipboardEntry"]
        Snippet["Snippet"]
        AppConfig["AppConfig"]
    end

    Main --> DB
    Main --> PermChk
    Main --> ConfigRepo
    Main --> ClipRepo & SnipRepo
    Main --> ClipSvc & SnipSvc & PasteSvc & MetricsSvc
    Main --> ClipMon & MacHotkey & MacSnippet & KeyHook & MenuBar
    Main --> Life

    PermChk --> jna & perms
    DB --> sqlite
    ConfigRepo & ClipRepo & SnipRepo --> DB

    ClipSvc --> ClipRepo
    ClipSvc --> AppConfig
    SnipSvc --> SnipRepo
    ConfigRepo --> AppConfig
    ClipSvc & SnipSvc --> ClipEntry & Snippet

    ClipMon --> ClipSvc
    ClipMon --> macOS
    MacHotkey --> carbon
    MacHotkey -->|"hotkey callback"| MenuBar
    HistoryMenu --> EscHotkey
    EscHotkey --> carbon
    EscHotkey -->|"Escape callback"| HistoryMenu
    MacSnippet --> quartz
    MacSnippet --> SnipSvc & PasteSvc & AppConfig
    KeyHook --> jnh
    KeyHook --> SnipSvc & PasteSvc & AppConfig

    MenuBar --> ClipSvc & SnipSvc & PasteSvc & MetricsSvc & ConfigRepo & AppConfig
    MenuBar --> HistoryMenu & SearchPicker & HistDlg & SnippetDlg & PrefsDlg & MetricsDlg
    MenuBar -->|"change listener"| ClipSvc
    HistoryMenu --> ClipSvc & PasteSvc
    SearchPicker --> PasteSvc
    HistDlg --> ClipSvc & PasteSvc
    PrefsDlg --> ClipSvc & ConfigRepo & AppConfig
    MetricsDlg --> MetricsSvc

    PasteSvc --> macOS
    PasteSvc --> MetricsSvc
    ClipSvc & SnipSvc -->|"payload data"| MetricsSvc

    Life --> MacHotkey & MacSnippet & KeyHook & ClipMon & MenuBar & ClipRepo & SnipRepo & ConfigRepo & DB
```

### Startup Sequence

```mermaid
sequenceDiagram
    participant Main
    participant DB as DatabaseManager
    participant Config as ConfigRepository
    participant Svc as Services
    participant Perm as PermissionChecker
    participant Mon as ClipboardMonitor
    participant UI as MenuBarApp (EDT)
    participant Hotkey as MacGlobalHotkey
    participant Tap as MacSnippetEventTap
    participant Hook as GlobalKeyboardHook

    Main->>DB: initialize()
    Main->>Config: load() → AppConfig
    Main->>Svc: construct repos + services
    Main->>Svc: snippetService.initialize()
    Main->>Perm: checkAndPrompt()
    alt permissions missing
        Perm-->>Main: exit(0)
    end
    Main->>Mon: start()
    Main->>UI: invokeAndWait(initialize)
    Main->>Hotkey: register native history hotkey
    Main->>Tap: register native snippet event tap
    opt native hooks unavailable
        Main->>Hook: register JNativeHook fallback
    end
    Main->>Main: AppLifecycle shutdown hooks
    Main->>Main: main thread join()
```

### Runtime Data Flows

```mermaid
flowchart LR
    subgraph Capture["Capture path"]
        CB["System clipboard"]
        CM["ClipboardMonitor<br/>500ms poll"]
        CS["ClipboardService"]
        CR["ClipboardRepository<br/>upsert + count/TTL prune"]
        CB --> CM --> CS --> CR
        CS -->|"notify listeners"| MB2["MenuBarApp Recent"]
    end

    subgraph Hotkey["Hotkey history menu path"]
        HK["MacGlobalHotkey<br/>Carbon RegisterEventHotKey"]
        ESC["MacEscapeHotkey<br/>temporary native Escape"]
        MB4["MenuBarApp<br/>showHistoryMenu()"]
        HM["ClipboardHistoryMenu<br/>mouse-first range submenus<br/>Esc closes"]
        PS1["PasteService<br/>paste-from-history-menu thread"]
        HK -->|"invokeLater callback"| MB4 --> HM --> PS1
        HM --> ESC
        ESC -->|"Escape closes menu"| HM
        CS --> MB4
        PS1 -->|"Cmd+V via Robot"| App["Active app"]
    end

    subgraph Search["Tray History search path"]
        MB3["MenuBar → History"]
        SP["ClipboardSearchPicker<br/>filter + list"]
        PS2["PasteService<br/>paste-from-search-picker thread"]
        MB3 --> SP --> CS
        SP --> PS2 --> App
    end

    subgraph Snip["Snippet expansion path"]
        Keys["User types /key"]
        Tap["MacSnippetEventTap<br/>Quartz CGEventTap"]
        JHK["GlobalKeyboardHook<br/>fallback"]
        FSM["Key buffer state machine"]
        SS["SnippetService lookup"]
        PEx["paste-executor / paste-executor-mac-event-tap"]
        Keys --> Tap --> FSM --> SS
        Keys -. fallback .-> JHK
        JHK --> FSM
        FSM --> PEx --> PS3["PasteService.expandSnippet"]
        PS3 --> App
    end

    subgraph Tray["Tray Recent path"]
        MB["MenuBarApp<br/>top-N submenu"]
        PS4["PasteService<br/>paste-from-menu thread"]
        MB --> CS
        MB --> PS4 --> App
    end
```

### Thread Model

```mermaid
flowchart TB
    subgraph Threads["Named threads / executors"]
        EDT["EDT<br/>Swing UI, dialogs, popup"]
        CarbonT["macOS Carbon event dispatcher<br/>history hotkey + popup Escape"]
        QuartzT["mac-snippet-event-tap<br/>native snippet key events"]
        JNH["JNativeHook native thread<br/>fallback key events"]
        PEx["paste-executor<br/>snippet expansion"]
        PMac["paste-executor-mac-event-tap<br/>native snippet expansion"]
        CMt["clipboard-monitor<br/>500ms poll"]
        DBC["db-clipboard"]
        DBS["db-snippet"]
        DBK["db-config"]
        MR["metrics-refresher<br/>dashboard refresh"]
    end

    subgraph Ephemeral["Ephemeral daemon threads per paste"]
        PHM["paste-from-history-menu"]
        PF["paste-from-search-picker"]
        PM["paste-from-menu"]
        PH["paste-from-history"]
    end

    MacHotkey["MacGlobalHotkey"] --> CarbonT
    MacHotkey -->|"invokeLater show menu"| EDT
    EscHotkey["MacEscapeHotkey"] --> CarbonT
    EscHotkey -->|"invokeLater close menu"| EDT
    MacSnippet["MacSnippetEventTap"] --> QuartzT
    MacSnippet -->|"expandSnippet"| PMac
    KeyHook["GlobalKeyboardHook"] --> JNH
    KeyHook -->|"expandSnippet"| PEx

    ClipMon["ClipboardMonitor"] --> CMt
    ClipRepo["Repositories"] --> DBC & DBS & DBK
    PEx --> PasteSvc["PasteService.pasteText"]
    PMac --> PasteSvc

    HistoryMenu2["ClipboardHistoryMenu"] --> PHM
    SearchPicker2["ClipboardSearchPicker"] --> PF
    MenuBar["MenuBarApp Recent"] --> PM
    HistDlg["HistoryDialog"] --> PH
    PHM & PF & PM & PH --> PasteSvc

    ClipSvc["ClipboardService listeners"] -->|"invokeLater"| EDT
    MetricsDlg["MetricsDashboardDialog"] --> MR
```

### External Dependencies

```mermaid
flowchart LR
    Pasties["Pasties JAR"]
    Pasties --> JNH["JNativeHook"]
    Pasties --> Carbon["Carbon RegisterEventHotKey<br/>via JNA Platform"]
    Pasties --> Quartz["Quartz CGEventTap<br/>via JNA"]
    Pasties --> JDBC["sqlite-jdbc"]
    Pasties --> JNA["JNA / jna-platform"]
    Pasties --> Log["SLF4J + Logback"]

    JNH --> macPerm1["Input Monitoring<br/>(fallback snippet detection)"]
    Carbon --> macHotkey["Native app hotkeys<br/>(history menu + popup Escape)"]
    Quartz --> macPerm3["Input Monitoring<br/>(native snippet detection)"]
    JNA --> macPerm2["Accessibility check"]
    Paste["PasteService + Robot"] --> macPerm2
```
