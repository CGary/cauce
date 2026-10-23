CREATE TABLE IF NOT EXISTS events (
    server_seq INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    user_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    type TEXT NOT NULL,
    v INTEGER NOT NULL,
    ts TEXT NOT NULL,
    payload TEXT NOT NULL,
    received_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_user_seq ON events (user_id, server_seq);
CREATE INDEX IF NOT EXISTS idx_events_user_type ON events (user_id, type);
