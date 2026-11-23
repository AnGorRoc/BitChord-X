package telemetry

// Real session tracking. The old install.session_count was incremented on every
// "open" heartbeat that arrived more than 10 minutes after the previous one.
// With a 60-second heartbeat that meant roughly one "session" every ten minutes
// for an app that never closed, so it measured pings, not usage.
//
// This file replaces that with explicit sessions: one row per continuous period
// in which an install kept announcing itself open. Heartbeats renew the row,
// an explicit close marks it for ending, and a reaper closes rows whose pings
// stopped (Android kills and crashes do not always send a close).

import (
	"context"
	"errors"
	"fmt"
	"log"
	"time"

	"github.com/jackc/pgx/v5"
)

const (
	// An install stays in the same session while pings are no more than this far
	// apart. The app pings every 60s (server-configurable), so three missed pings
	// is a safe disconnection signal.
	SessionGap = 3 * time.Minute
	// A close notice is confirmed after this grace period. The WebSocket usually
	// closes first and the client sends its HTTP "open: false" shortly after; a
	// heartbeat arriving in between means the app is still open and cancels it.
	SessionCloseGrace = 2 * time.Minute
	// How often stale sessions are closed. Keeps duration error bounded even when
	// no close notice arrives at all.
	sessionReapInterval = 30 * time.Second
)

// SessionStats describes real, finished sessions. Durations come from the
// database rather than from install.session_count.
type SessionStats struct {
	SessionsInPeriod        int64   `json:"sessionsInPeriod"`
	ActiveSessions          int64   `json:"activeSessions"`
	MedianDurationSec       int64   `json:"medianDurationSec"`
	P90DurationSec          int64   `json:"p90DurationSec"`
	MaxDurationSec          int64   `json:"maxDurationSec"`
	AvgDurationSec          int64   `json:"avgDurationSec"`
	SessionsOver1h          int64   `json:"sessionsOver1h"`
	SessionsOver4h          int64   `json:"sessionsOver4h"`
	InstallsWithLongSession int64   `json:"installsWithLongSession"`
	GracefulClosePct        float64 `json:"gracefulClosePct"`
}

// PresenceBucket counts installs by how many different hours they were active
// in during the requested range. It is the direct answer to "rotation or the
// same devices all day".
type PresenceBucket struct {
	Label    string `json:"label"`
	Installs int    `json:"installs"`
}

// RotationStats measures how much the active set changes over time.
type RotationStats struct {
	ActiveInstalls    int              `json:"activeInstalls"`
	MedianActiveHours float64          `json:"medianActiveHours"`
	KeptInNextHourPct float64          `json:"keptInNextHourPct"`
	Buckets           []PresenceBucket `json:"buckets"`
}

func intervalLiteral(d time.Duration) string {
	return fmt.Sprintf("%d seconds", int64(d/time.Second))
}

func formatDuration(sec int64) string {
	if sec <= 0 {
		return "0s"
	}
	h, m, s := sec/3600, (sec%3600)/60, sec%60
	switch {
	case h > 0:
		return fmt.Sprintf("%dh %dm", h, m)
	case m > 0:
		return fmt.Sprintf("%dm %ds", m, s)
	default:
		return fmt.Sprintf("%ds", s)
	}
}

// closeStaleSQL ends sessions that stopped heartbeating, or whose close notice
// has been standing long enough to be trusted. ended_at is the last moment we
// have evidence the install was open (or the exact close notice when earlier).
const closeStaleSQL = `
UPDATE app_sessions
SET ended_at = GREATEST(started_at, CASE
		WHEN close_requested_at IS NOT NULL AND close_requested_at < last_seen_at
			THEN close_requested_at
		ELSE last_seen_at
	END),
	end_reason = CASE WHEN close_requested_at IS NOT NULL THEN 'close' ELSE 'timeout' END
WHERE ended_at IS NULL
  AND (close_requested_at <= NOW() - $1::interval
       OR last_seen_at < NOW() - $2::interval)`

// recordAppOpen renews the active session or starts a new one. Everything is
// done in one transaction so a heartbeat can never produce two open rows.
func (s *Store) recordAppOpen(ctx context.Context, installID, platform, appVersion, packageFormat string) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		log.Printf("telemetry: begin open session: %v", err)
		return
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// Install record: identity, version upgrades and last activity. Session
	// counting follows the session row below, so a brand new install starts at
	// zero and the first session brings it to one.
	const upsertInstall = `
INSERT INTO installs (install_id, platform, app_version, first_version, first_seen_at, last_seen_at, session_count, last_open_at, package_format)
VALUES ($1, $2, $3, $3, NOW(), NOW(), 0, NOW(), $4)
ON CONFLICT (install_id) DO UPDATE SET
	platform = EXCLUDED.platform,
	first_version = CASE WHEN installs.first_version = '' THEN EXCLUDED.app_version ELSE installs.first_version END,
	previous_version = CASE
		WHEN EXCLUDED.app_version <> '' AND installs.app_version <> '' AND EXCLUDED.app_version <> installs.app_version
		THEN installs.app_version
		ELSE installs.previous_version
	END,
	upgraded_at = CASE
		WHEN EXCLUDED.app_version <> '' AND installs.app_version <> '' AND EXCLUDED.app_version <> installs.app_version
		THEN NOW()
		ELSE installs.upgraded_at
	END,
	app_version = CASE WHEN EXCLUDED.app_version <> '' THEN EXCLUDED.app_version ELSE installs.app_version END,
	package_format = CASE WHEN EXCLUDED.package_format <> '' THEN EXCLUDED.package_format ELSE installs.package_format END,
	last_seen_at = NOW(),
	last_open_at = NOW();`
	if _, err := tx.Exec(ctx, upsertInstall, installID, platform, appVersion, packageFormat); err != nil {
		log.Printf("telemetry: upsert install failed: %v", err)
		return
	}

	// A killed app may never have announced its close. Finish that session now
	// so the next heartbeat starts a fresh one instead of extending the old one.
	if _, err := tx.Exec(ctx, closeStaleSQL, intervalLiteral(SessionCloseGrace), intervalLiteral(SessionGap)); err != nil {
		log.Printf("telemetry: expire stale session failed: %v", err)
		return
	}

	var sessionID int64
	err = tx.QueryRow(ctx, `SELECT id FROM app_sessions WHERE install_id = $1 AND ended_at IS NULL`, installID).Scan(&sessionID)
	switch {
	case err == nil:
		_, err = tx.Exec(ctx, `UPDATE app_sessions SET last_seen_at = NOW(), close_requested_at = NULL WHERE id = $1`, sessionID)
	case errors.Is(err, pgx.ErrNoRows):
		err = tx.QueryRow(ctx, `
INSERT INTO app_sessions (install_id, platform, app_version, package_format) VALUES ($1, $2, $3, $4) RETURNING id;`,
			installID, platform, appVersion, packageFormat).Scan(&sessionID)
		if err == nil {
			_, err = tx.Exec(ctx, `UPDATE installs SET session_count = session_count + 1 WHERE install_id = $1`, installID)
		}
		if err == nil {
			_, err = tx.Exec(ctx, `
INSERT INTO connection_events (install_id, platform, app_version, event_type, package_format)
VALUES ($1, $2, $3, 'open', $4);`, installID, platform, appVersion, packageFormat)
		}
	default:
		log.Printf("telemetry: find active session failed: %v", err)
		return
	}
	if err != nil {
		log.Printf("telemetry: record open session failed: %v", err)
		return
	}
	if err := tx.Commit(ctx); err != nil {
		log.Printf("telemetry: commit open session failed: %v", err)
	}
}

// TouchSession renews an open session from a WebSocket pong. In WS mode the app
// sends the "open" POST once at hello and then only answers server pings, so
// without this the reaper would close perfectly healthy long sessions.
func (s *Store) TouchSession(installID string) {
	if s.pool == nil {
		return
	}
	s.enqueue(func(ctx context.Context) {
		if _, err := s.pool.Exec(ctx, `UPDATE app_sessions SET last_seen_at = NOW() WHERE install_id = $1 AND ended_at IS NULL`, installID); err != nil {
			log.Printf("telemetry: touch session failed: %v", err)
		}
		if _, err := s.pool.Exec(ctx, `UPDATE installs SET last_seen_at = NOW() WHERE install_id = $1`, installID); err != nil {
			log.Printf("telemetry: touch install failed: %v", err)
		}
	})
}

// recordAppClose only marks the close. A heartbeat arriving within the grace
// period cancels it, which keeps HTTP pings that outlive a WebSocket from
// splitting one continuous session in two.
func (s *Store) recordAppClose(ctx context.Context, installID, platform, appVersion, packageFormat string) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		log.Printf("telemetry: begin close session: %v", err)
		return
	}
	defer func() { _ = tx.Rollback(ctx) }()

	tag, err := tx.Exec(ctx, `
UPDATE app_sessions SET close_requested_at = COALESCE(close_requested_at, NOW())
WHERE install_id = $1 AND ended_at IS NULL;`, installID)
	if err != nil {
		log.Printf("telemetry: mark session close failed: %v", err)
		return
	}
	if tag.RowsAffected() > 0 {
		_, err = tx.Exec(ctx, `
INSERT INTO connection_events (install_id, platform, app_version, event_type, package_format)
VALUES ($1, $2, $3, 'close', $4);`, installID, platform, appVersion, packageFormat)
		if err != nil {
			log.Printf("telemetry: insert close event failed: %v", err)
			return
		}
	}
	if _, err := tx.Exec(ctx, `UPDATE installs SET last_seen_at = NOW() WHERE install_id = $1`, installID); err != nil {
		log.Printf("telemetry: update last_seen on close failed: %v", err)
		return
	}
	if err := tx.Commit(ctx); err != nil {
		log.Printf("telemetry: commit close session failed: %v", err)
	}
}

// reapStaleSessions is called by the maintenance loop. It also bounds how long
// "active sessions" can stay open after an install disappears.
func (s *Store) reapStaleSessions(ctx context.Context) {
	if s.pool == nil {
		return
	}
	if _, err := s.pool.Exec(ctx, closeStaleSQL, intervalLiteral(SessionCloseGrace), intervalLiteral(SessionGap)); err != nil {
		log.Printf("telemetry: reap stale sessions failed: %v", err)
	}
}

// activeHoursCTE expands the range into hours and joins the sessions covering
// each hour. One install counts once per hour even if it reconnected 20 times.
const activeHoursCTE = `
WITH hours AS (
	SELECT generate_series(date_trunc('hour', $1::timestamptz), date_trunc('hour', NOW()), interval '1 hour') AS h
), active AS (
	SELECT DISTINCT hours.h, s.install_id
	FROM hours
	JOIN app_sessions s
	  ON s.started_at < hours.h + interval '1 hour'
	 AND COALESCE(s.ended_at, NOW()) >= hours.h
), per_install AS (
	SELECT install_id, COUNT(*)::int AS hours FROM active GROUP BY install_id
)`

// loadSessionMetrics fills both session duration and rotation metrics.
func (s *Store) loadSessionMetrics(ctx context.Context, since time.Time) (SessionStats, RotationStats, error) {
	stats := SessionStats{}
	rot := RotationStats{Buckets: []PresenceBucket{}}
	if s.pool == nil {
		return stats, rot, nil
	}

	const sessionsQ = `
WITH closed AS (
	SELECT install_id,
	       EXTRACT(EPOCH FROM (ended_at - started_at))::bigint AS dur_sec,
	       end_reason
	FROM app_sessions
	WHERE started_at >= $1 AND ended_at IS NOT NULL
)
SELECT COUNT(*)::bigint,
       COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY dur_sec)::bigint, 0),
       COALESCE(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY dur_sec)::bigint, 0),
       COALESCE(MAX(dur_sec), 0),
       COALESCE(AVG(dur_sec)::bigint, 0),
       COUNT(*) FILTER (WHERE dur_sec >= 3600)::bigint,
       COUNT(*) FILTER (WHERE dur_sec >= 14400)::bigint,
       COUNT(DISTINCT install_id) FILTER (WHERE dur_sec >= 14400)::bigint,
       COALESCE(ROUND(100.0 * COUNT(*) FILTER (WHERE end_reason = 'close') / NULLIF(COUNT(*), 0), 1), 0)
FROM closed;`
	if err := s.pool.QueryRow(ctx, sessionsQ, since).Scan(
		&stats.SessionsInPeriod,
		&stats.MedianDurationSec,
		&stats.P90DurationSec,
		&stats.MaxDurationSec,
		&stats.AvgDurationSec,
		&stats.SessionsOver1h,
		&stats.SessionsOver4h,
		&stats.InstallsWithLongSession,
		&stats.GracefulClosePct,
	); err != nil {
		return stats, rot, err
	}
	if err := s.pool.QueryRow(ctx, `SELECT COUNT(*)::bigint FROM app_sessions WHERE ended_at IS NULL`).Scan(&stats.ActiveSessions); err != nil {
		return stats, rot, err
	}

	if err := s.pool.QueryRow(ctx, activeHoursCTE+`
SELECT COUNT(*)::int, COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY hours), 0)::float8
FROM per_install;`, since).Scan(&rot.ActiveInstalls, &rot.MedianActiveHours); err != nil {
		return stats, rot, err
	}

	rows, err := s.pool.Query(ctx, activeHoursCTE+`
SELECT CASE
		WHEN hours >= 12 THEN '12+'
		WHEN hours >= 6 THEN '6-11'
		WHEN hours >= 2 THEN '2-5'
		ELSE '1'
	END AS label,
	COUNT(*)::int
FROM per_install
GROUP BY label
ORDER BY MIN(hours);`, since)
	if err != nil {
		return stats, rot, err
	}
	for rows.Next() {
		var b PresenceBucket
		if rows.Scan(&b.Label, &b.Installs) == nil {
			rot.Buckets = append(rot.Buckets, b)
		}
	}
	rows.Close()

	// Of all install-hours, how many were also active in the following hour?
	// A low number means the active set is rotating rather than staying put.
	if err := s.pool.QueryRow(ctx, `
WITH hours AS (
	SELECT generate_series(date_trunc('hour', $1::timestamptz), date_trunc('hour', NOW()), interval '1 hour') AS h
), active AS (
	SELECT DISTINCT hours.h, s.install_id
	FROM hours
	JOIN app_sessions s
	  ON s.started_at < hours.h + interval '1 hour'
	 AND COALESCE(s.ended_at, NOW()) >= hours.h
), joined AS (
	SELECT a.h,
	       COUNT(*)::int AS users,
	       COUNT(b.install_id)::int AS kept
	FROM active a
	LEFT JOIN active b ON b.install_id = a.install_id AND b.h = a.h + interval '1 hour'
	GROUP BY a.h
)
SELECT COALESCE(ROUND(100.0 * SUM(kept) / NULLIF(SUM(users), 0), 1), 0)::float8
FROM joined;`, since).Scan(&rot.KeptInNextHourPct); err != nil {
		return stats, rot, err
	}
	return stats, rot, nil
}

// loadHourlyActiveUsers counts distinct installs with an open session covering
// each hour of day. Unlike connection_events this is not skewed by heartbeats.
func (s *Store) loadHourlyActiveUsers(ctx context.Context, since time.Time) map[int]int {
	out := map[int]int{}
	if s.pool == nil {
		return out
	}
	rows, err := s.pool.Query(ctx, `
WITH hours AS (
	SELECT generate_series(date_trunc('hour', $1::timestamptz), date_trunc('hour', NOW()), interval '1 hour') AS h
), per_hour AS (
	SELECT EXTRACT(HOUR FROM hours.h AT TIME ZONE 'UTC')::int AS hr,
	       COUNT(DISTINCT s.install_id)::int AS uniq
	FROM hours
	LEFT JOIN app_sessions s
	  ON s.started_at < hours.h + interval '1 hour'
	 AND COALESCE(s.ended_at, NOW()) >= hours.h
	GROUP BY hours.h
)
SELECT hr, ROUND(AVG(uniq))::int FROM per_hour GROUP BY hr ORDER BY hr;`, since)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var hr, uniq int
		if rows.Scan(&hr, &uniq) == nil {
			out[hr] = uniq
		}
	}
	return out
}
