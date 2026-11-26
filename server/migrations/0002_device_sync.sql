-- Operational bookkeeping of each device's last sync (D22). Not an event and
-- not a projection: the event log is unchanged.
CREATE TABLE IF NOT EXISTS device_sync (
    user_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    last_push_at TEXT,
    last_pull_at TEXT,
    pulled_through_seq INTEGER,
    PRIMARY KEY (user_id, device_id)
);
