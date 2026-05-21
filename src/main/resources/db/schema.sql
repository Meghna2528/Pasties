PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

-- Clipboard history entries (text only in v1)
CREATE TABLE IF NOT EXISTS clipboard_entries (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    content      TEXT    NOT NULL,
    content_hash TEXT    NOT NULL UNIQUE,   -- SHA-256 hex for deduplication
    created_at   INTEGER NOT NULL,          -- Unix epoch milliseconds
    access_count INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_clip_created ON clipboard_entries(created_at DESC);

-- Named snippet definitions
CREATE TABLE IF NOT EXISTS snippets (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    key_name    TEXT    NOT NULL UNIQUE,   -- e.g. "addr" (triggers on "/addr")
    value       TEXT    NOT NULL,          -- expansion text
    description TEXT,                     -- optional human-readable label
    created_at  INTEGER NOT NULL,          -- Unix epoch milliseconds
    updated_at  INTEGER NOT NULL           -- Unix epoch milliseconds
);
CREATE INDEX IF NOT EXISTS idx_snip_key ON snippets(key_name);

-- Application configuration (key-value store)
CREATE TABLE IF NOT EXISTS config (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Seed defaults (INSERT OR IGNORE so re-runs are idempotent)
INSERT OR IGNORE INTO config VALUES ('max_history_size', '200');
INSERT OR IGNORE INTO config VALUES ('hotkey_modifiers', 'ctrl+shift');
INSERT OR IGNORE INTO config VALUES ('hotkey_key', 'S');
INSERT OR IGNORE INTO config VALUES ('start_on_login', 'false');
INSERT OR IGNORE INTO config VALUES ('snippet_prefix', '/');
INSERT OR IGNORE INTO config VALUES ('paste_delay_ms', '80');
INSERT OR IGNORE INTO config VALUES ('recent_menu_size', '10');
INSERT OR IGNORE INTO config VALUES ('popup_history_size', '50');
INSERT OR IGNORE INTO config VALUES ('entry_ttl_days', '90');
INSERT OR IGNORE INTO config VALUES ('db_version', '1');
