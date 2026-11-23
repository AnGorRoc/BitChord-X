package telemetry

// Integration coverage for real session tracking. Skipped unless a test
// database is provided, so `go test ./...` stays self-contained:
//
//   TELEMETRY_TEST_DATABASE_URL=postgres://... go test ./telemetry -run SessionTracking -v

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"
)

// The DB worker is a single goroutine, so an enqueued barrier means every
// presence event sent before it has been committed.
func drain(t *testing.T, s *Store) {
	t.Helper()
	done := make(chan struct{})
	s.enqueue(func(context.Context) { close(done) })
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("timed out draining telemetry queue")
	}
}

func testInstallID(b byte) string {
	return fmt.Sprintf("%08x-0000-0000-0000-%012x", b, b)
}

func TestSessionTrackingIntegration(t *testing.T) {
	dsn := os.Getenv("TELEMETRY_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TELEMETRY_TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	store, err := NewStore(ctx, dsn, "test", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	if _, err := store.pool.Exec(ctx, `TRUNCATE TABLE installs, connection_events, app_sessions, concurrency_snapshots RESTART IDENTITY`); err != nil {
		t.Fatal(err)
	}
	id := testInstallID(1)
	store.RecordPresence(id, "android", "1.10.0", true)
	drain(t, store)

	var sessions, opens, sessionCount int
	queryState := func() {
		t.Helper()
		if err := store.pool.QueryRow(ctx, `SELECT COUNT(*) FROM app_sessions WHERE install_id = $1`, id).Scan(&sessions); err != nil {
			t.Fatal(err)
		}
		if err := store.pool.QueryRow(ctx, `SELECT COUNT(*) FROM connection_events WHERE install_id = $1 AND event_type = 'open'`, id).Scan(&opens); err != nil {
			t.Fatal(err)
		}
		if err := store.pool.QueryRow(ctx, `SELECT session_count FROM installs WHERE install_id = $1`, id).Scan(&sessionCount); err != nil {
			t.Fatal(err)
		}
	}
	queryState()
	if sessions != 1 || opens != 1 || sessionCount != 1 {
		t.Fatalf("first open: sessions=%d opens=%d sessionCount=%d", sessions, opens, sessionCount)
	}

	// Heartbeats renew the same session; they must not create new ones.
	for i := 0; i < 5; i++ {
		store.RecordPresence(id, "android", "1.10.0", true)
	}
	drain(t, store)
	queryState()
	if sessions != 1 || opens != 1 || sessionCount != 1 {
		t.Fatalf("repeated heartbeats: sessions=%d opens=%d sessionCount=%d", sessions, opens, sessionCount)
	}

	// WebSocket pongs renew the session without starting another one.
	if _, err := store.pool.Exec(ctx, `UPDATE app_sessions SET last_seen_at = NOW() - INTERVAL '4 minutes' WHERE install_id = $1`, id); err != nil {
		t.Fatal(err)
	}
	store.TouchSession(id)
	drain(t, store)
	var last time.Time
	if err := store.pool.QueryRow(ctx, `SELECT last_seen_at FROM app_sessions WHERE install_id = $1 AND ended_at IS NULL`, id).Scan(&last); err != nil {
		t.Fatal(err)
	}
	if time.Since(last) > 2*time.Second {
		t.Fatalf("pong did not renew session: %v", last)
	}

	// A close is only a request until the reaper confirms it.
	store.RecordPresence(id, "android", "1.10.0", false)
	drain(t, store)
	var pending bool
	if err := store.pool.QueryRow(ctx, `SELECT close_requested_at IS NOT NULL FROM app_sessions WHERE install_id = $1 AND ended_at IS NULL`, id).Scan(&pending); err != nil {
		t.Fatal(err)
	}
	if !pending {
		t.Fatal("close was not recorded as a request")
	}
	if _, err := store.pool.Exec(ctx, `
UPDATE app_sessions
SET started_at = NOW() - INTERVAL '10 minutes',
    last_seen_at = NOW() - INTERVAL '3 minutes 1 second',
    close_requested_at = NOW() - INTERVAL '3 minutes'
WHERE install_id = $1 AND ended_at IS NULL`, id); err != nil {
		t.Fatal(err)
	}
	store.reapStaleSessions(ctx)

	var reason string
	var duration float64
	if err := store.pool.QueryRow(ctx, `
SELECT end_reason, EXTRACT(EPOCH FROM (ended_at - started_at))
FROM app_sessions WHERE install_id = $1`, id).Scan(&reason, &duration); err != nil {
		t.Fatal(err)
	}
	if reason != "close" {
		t.Fatalf("graceful close reason = %q", reason)
	}

	// A heartbeat after the close starts a second session.
	store.RecordPresence(id, "android", "1.10.0", true)
	drain(t, store)
	queryState()
	if sessions != 2 || sessionCount != 2 {
		t.Fatalf("second session: sessions=%d sessionCount=%d", sessions, sessionCount)
	}

	// A crash (no close) is bounded by the gap reaper.
	if _, err := store.pool.Exec(ctx, `UPDATE app_sessions SET last_seen_at = NOW() - INTERVAL '5 minutes' WHERE ended_at IS NULL`); err != nil {
		t.Fatal(err)
	}
	store.reapStaleSessions(ctx)
	if err := store.pool.QueryRow(ctx, `SELECT end_reason FROM app_sessions WHERE ended_at IS NOT NULL AND end_reason = 'timeout' LIMIT 1`).Scan(&reason); err != nil {
		t.Fatalf("expected a timeout session: %v", err)
	}

	// A second install spanning two hours, to exercise the rotation queries.
	other := testInstallID(2)
	if _, err := store.pool.Exec(ctx, `
INSERT INTO app_sessions (install_id, platform, app_version, started_at, last_seen_at, ended_at, end_reason)
VALUES ($1, 'pc', '1.10.0', NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hour', 'close')`, other); err != nil {
		t.Fatal(err)
	}
	if _, err := store.pool.Exec(ctx, `INSERT INTO installs (install_id, platform, app_version, first_seen_at, last_seen_at, session_count) VALUES ($1, 'pc', '1.10.0', NOW() - INTERVAL '3 hours', NOW() - INTERVAL '1 hour', 1) ON CONFLICT DO NOTHING`, other); err != nil {
		t.Fatal(err)
	}

	overview, err := store.Overview(ctx, "24h")
	if err != nil {
		t.Fatal(err)
	}
	if overview.Sessions.SessionsInPeriod < 3 {
		t.Fatalf("expected at least 3 finished sessions, got %d", overview.Sessions.SessionsInPeriod)
	}
	if overview.Sessions.MedianDurationSec <= 0 || overview.Rotation.ActiveInstalls < 2 {
		t.Fatalf("bad metrics: %+v / %+v", overview.Sessions, overview.Rotation)
	}
	if overview.Rotation.KeptInNextHourPct < 0 || overview.Rotation.KeptInNextHourPct > 100 {
		t.Fatalf("bad overlap: %v", overview.Rotation.KeptInNextHourPct)
	}
	for _, bucket := range overview.HourlyConnections {
		if bucket.ActiveUsers < 0 {
			t.Fatal("negative active users")
		}
	}
}
