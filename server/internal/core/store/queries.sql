-- name: InsertEvent :execresult
INSERT INTO events (id, user_id, device_id, type, v, ts, payload, received_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(id) DO NOTHING;

-- name: ListEventsSince :many
SELECT server_seq, id, user_id, device_id, type, v, ts, payload, received_at
FROM events
WHERE user_id = ? AND server_seq > ?
ORDER BY server_seq
LIMIT ?;

-- name: ListEventsByUser :many
SELECT server_seq, id, user_id, device_id, type, v, ts, payload, received_at
FROM events
WHERE user_id = ?
ORDER BY server_seq;

-- name: MaxServerSeq :one
SELECT CAST(COALESCE(MAX(server_seq), 0) AS INTEGER) AS max_seq
FROM events
WHERE user_id = ?;

-- name: RecordDevicePush :exec
INSERT INTO device_sync (user_id, device_id, last_push_at)
VALUES (?, ?, ?)
ON CONFLICT(user_id, device_id) DO UPDATE SET last_push_at = excluded.last_push_at;

-- name: RecordDevicePull :exec
INSERT INTO device_sync (user_id, device_id, last_pull_at, pulled_through_seq)
VALUES (?, ?, ?, ?)
ON CONFLICT(user_id, device_id) DO UPDATE SET
    last_pull_at = excluded.last_pull_at,
    pulled_through_seq = excluded.pulled_through_seq;

-- name: ListDeviceSync :many
SELECT user_id, device_id, last_push_at, last_pull_at, pulled_through_seq
FROM device_sync
WHERE user_id = ?
ORDER BY device_id;
