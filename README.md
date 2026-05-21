# Pasties

A lightweight macOS clipboard manager written in Java, inspired by [Clipy](https://github.com/clipy/clipy).

Pasties lives in your menu bar, keeps a searchable history of everything you've copied, and lets you define text snippets that expand automatically when you type `/{key}` in **any** application on your Mac.

For build details, usage notes, troubleshooting, architecture diagrams, and development workflows, see [Project Details](Project%20Details.md).

---

## Features

| Feature | Description |
|---|---|
| **Clipboard history** | Stores up to 300 text entries (configurable). Deduplicated by content hash. TTL-pruned (default 90 days). |
| **Global hotkey history menu** | Press **Ctrl+Shift+S** or **⌘+Shift+S** to open a Clipy-style cascading history menu grouped as `1 - 10`, `11 - 20`, and so on. Press **Escape** to close it. |
| **Searchable history picker** | Open **History** from the menu bar to search recent clipboard entries and paste by selection. |
| **Snippet expansion** | Type `/addr` in any app and it instantly expands to your saved text. Snippet keys are case-insensitive and are detected with a native macOS event tap. |
| **Performance dashboard** | Live metrics: paste speed, snippet expansion speed, memory, CPU, DB size, and payload sizes. |
| **Menu bar app** | No Dock icon. Everything accessible from the system tray. |
| **Persistent storage** | SQLite database at `~/Library/Application Support/Pasties/pasties.db`. |

---

## Requirements

| Dependency | Version | How to install |
|---|---|---|
| Java | 21 (JDK) | `brew install openjdk@21` |
| Maven | 3.9+ | `brew install maven` |
| Git | Any recent version | `xcode-select --install` or `brew install git` |
| macOS | 13 Ventura or later | — |

> **Apple Silicon (arm64) is supported.** The SQLite JDBC driver bundles a native arm64 binary.

---

## Quick Start

For a new local install from source:

```bash
git clone <repo-url>
cd pasties
scripts/install.sh
```

The installer checks for a full JDK (`java` + `jpackage`) and Maven. If either is missing, it prompts for a JDK path or asks whether to install missing Homebrew dependencies. It then builds, signs, installs, and launches `/Applications/Pasties.app`.

On first launch, Pasties will check for the required macOS permissions and open System Settings automatically if either is missing. Enable Pasties in:

1. `System Settings -> Privacy & Security -> Input Monitoring`
2. `System Settings -> Privacy & Security -> Accessibility`

After granting permissions, restart Pasties and press **⌘+Shift+S** to open the clipboard history menu.
