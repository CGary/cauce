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
