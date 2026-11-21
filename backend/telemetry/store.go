// Package telemetry stores anonymous install activity, concurrency history,
// and opt-in error reports in PostgreSQL, and serves a local-network admin
// dashboard on a dedicated port.
package telemetry

import (
	"bytes"
	"context"
	"encoding/csv"
	"errors"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// LiveMetricsProvider returns current in-memory live stats so the dashboard
// can combine real-time RAM counts with historical PostgreSQL metrics.
type LiveMetricsProvider func() LiveStats

type LiveStats struct {
	Online            int   `json:"online"`
	Android           int   `json:"android"`
	PC                int   `json:"pc"`
	Browser           int   `json:"browser"`
	ActiveParties     int   `json:"activeParties"`
	TotalPartyMembers int   `json:"totalPartyMembers"`
	ActiveSockets     int   `json:"activeSockets"`
	UpdatedAt         int64 `json:"updatedAt"`
}

type ErrorInput struct {
	InstallID    string
	Platform     string
	AppVersion   string
	ErrorType    string
	ErrorMessage string
	Stacktrace   string
	DeviceModel  string
	OSVersion    string
	ABI          string
}

type ErrorReport struct {
	ID           int64     `json:"id"`
	CreatedAt    time.Time `json:"createdAt"`
	InstallID    string    `json:"installId"`
	Platform     string    `json:"platform"`
	AppVersion   string    `json:"appVersion"`
	ErrorType    string    `json:"errorType"`
	ErrorMessage string    `json:"errorMessage"`
	Stacktrace   string    `json:"stacktrace"`
	DeviceModel  string    `json:"deviceModel"`
	OSVersion    string    `json:"osVersion"`
	ABI          string    `json:"abi"`
	Resolved     bool      `json:"resolved"`
}

type ErrorFilter struct {
	Version        string
	Platform       string
	ErrorType      string
	UnresolvedOnly bool
	Search         string
	Limit          int
	Offset         int
}

type ErrorListResponse struct {
	Items      []ErrorReport `json:"items"`
	Total      int           `json:"total"`
	Versions   []string      `json:"versions"`
	ErrorTypes []string      `json:"errorTypes"`
}

type SummaryStats struct {
	TotalInstalls     int     `json:"totalInstalls"`
	DAU               int     `json:"dau"`
	WAU               int     `json:"wau"`
	MAU               int     `json:"mau"`
	NewInPeriod       int     `json:"newInPeriod"`
	ReturningInPeriod int     `json:"returningInPeriod"`
	LostInstalls      int     `json:"lostInstalls"`
	RetentionRatePct  float64 `json:"retentionRatePct"`
	TotalSessions     int64   `json:"totalSessions"`
	ErrorsInPeriod    int     `json:"errorsInPeriod"`
	UnresolvedErrors  int     `json:"unresolvedErrors"`
	PeakOnline        int     `json:"peakOnline"`
}

type SnapshotPoint struct {
	Timestamp     time.Time `json:"ts"`
	Online        int       `json:"online"`
	Android       int       `json:"android"`
	PC            int       `json:"pc"`
	Browser       int       `json:"browser"`
	ActiveParties int       `json:"activeParties"`
}

type HourlyBucket struct {
	Hour        int `json:"hour"`
	Connections int `json:"connections"`
	UniqueUsers int `json:"uniqueUsers"`
}

type VersionStat struct {
	Version    string  `json:"version"`
	Count      int     `json:"count"`
	Active24h  int     `json:"active24h"`
	ErrorCount int     `json:"errorCount"`
	Pct        float64 `json:"pct"`
}

type PlatformStat struct {
	Platform string  `json:"platform"`
	Count    int     `json:"count"`
	Pct      float64 `json:"pct"`
}

type ErrorGroupStat struct {
	ErrorType        string    `json:"errorType"`
	Count            int       `json:"count"`
	Unresolved       int       `json:"unresolved"`
	AffectedInstalls int       `json:"affectedInstalls"`
	LastSeen         time.Time `json:"lastSeen"`
}

type DeviceStat struct {
	DeviceModel string `json:"deviceModel"`
	OSVersion   string `json:"osVersion"`
	Count       int    `json:"count"`
}

type UpgradeStat struct {
	FromVersion string `json:"fromVersion"`
	ToVersion   string `json:"toVersion"`
	Count       int    `json:"count"`
}

type OverviewData struct {
	Range             string           `json:"range"`
	GeneratedAt       time.Time        `json:"generatedAt"`
	ServerVersion     string           `json:"serverVersion"`
	DatabaseEnabled   bool             `json:"databaseEnabled"`
	UptimeSec         int64            `json:"uptimeSec"`
	Live              LiveStats        `json:"live"`
	Summary           SummaryStats     `json:"summary"`
	ConcurrencySeries []SnapshotPoint  `json:"concurrencySeries"`
	HourlyConnections []HourlyBucket   `json:"hourlyConnections"`
	Versions          []VersionStat    `json:"versions"`
	Upgrades          []UpgradeStat    `json:"upgrades"`
	UpgradedInPeriod  int              `json:"upgradedInPeriod"`
	OutdatedActive7d  int              `json:"outdatedActive7d"`
	LatestVersion     string           `json:"latestVersion"`
	Platforms         []PlatformStat   `json:"platforms"`
	TopErrors         []ErrorGroupStat `json:"topErrors"`
	ErrorDevices      []DeviceStat     `json:"errorDevices"`
	Minimal           MinimalStats     `json:"minimal"`
}

// MinimalStats is what the minimal level adds up to: device-days and errors by
// type, with no way back to any one device.
type MinimalStats struct {
	DeviceDays int64              `json:"deviceDays"`
	Errors     []MinimalErrorStat `json:"errors"`
}

type MinimalErrorStat struct {
	ErrorType string `json:"errorType"`
	Count     int64  `json:"count"`
}

type Store struct {
	pool      *pgxpool.Pool
	startedAt time.Time
	liveFn    LiveMetricsProvider
	version   string

	queue  chan func(context.Context)
	wg     sync.WaitGroup
	closed chan struct{}
	once   sync.Once

	// Today's minimal-level IDs, kept in memory only and only for the day they
	// were made: they change every day on the client, so there is nothing to
	// follow, and they are never written to the database. Their one job is to
	// count a device once a day. A restart forgets them and can count a device
	// a second time that day, which is the whole cost of not storing them.
	minimalMu   sync.Mutex
	minimalDay  string
	minimalSeen map[string]struct{}
}

// NewStore connects to PostgreSQL when databaseURL is non-empty and runs
// idempotent schema migrations. When databaseURL is empty, it returns a no-op
// store that still serves live in-memory metrics on the dashboard.
func NewStore(ctx context.Context, databaseURL, serverVersion string, liveFn LiveMetricsProvider) (*Store, error) {
	s := &Store{
		startedAt: time.Now().UTC(),
		liveFn:    liveFn,
		version:   serverVersion,
		queue:     make(chan func(context.Context), 2048),
		closed:    make(chan struct{}),
	}
	if strings.TrimSpace(databaseURL) == "" {
		log.Printf("telemetry: DATABASE_URL not set; running with live-only in-memory mode")
		return s, nil
	}

	cfg, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, fmt.Errorf("parse DATABASE_URL: %w", err)
	}
	cfg.MaxConns = 10
	cfg.MinConns = 1

	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("connect postgres: %w", err)
	}

	// Retry ping briefly on cold container startup so compose depends_on races never crash.
	var pingErr error
	for attempt := 0; attempt < 10; attempt++ {
		pingCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		pingErr = pool.Ping(pingCtx)
		cancel()
		if pingErr == nil {
			break
		}
		time.Sleep(time.Second)
	}
	if pingErr != nil {
		pool.Close()
		return nil, fmt.Errorf("ping postgres: %w", pingErr)
	}

	if err := migrate(ctx, pool); err != nil {
		pool.Close()
		return nil, fmt.Errorf("migrate telemetry schema: %w", err)
	}

	s.pool = pool
	s.wg.Add(2)
	go s.worker()
	go s.maintenanceLoop()
	log.Printf("telemetry: PostgreSQL connected and schema ready")
	return s, nil
}

func migrate(ctx context.Context, pool *pgxpool.Pool) error {
	const ddl = `
CREATE TABLE IF NOT EXISTS installs (
	install_id       TEXT PRIMARY KEY,
	platform         TEXT NOT NULL,
	app_version      TEXT NOT NULL DEFAULT '',
	first_version    TEXT NOT NULL DEFAULT '',
	previous_version TEXT NOT NULL DEFAULT '',
	upgraded_at      TIMESTAMPTZ,
	first_seen_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
	last_seen_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
	session_count    BIGINT NOT NULL DEFAULT 1,
	last_open_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE installs ADD COLUMN IF NOT EXISTS first_version TEXT NOT NULL DEFAULT '';
ALTER TABLE installs ADD COLUMN IF NOT EXISTS previous_version TEXT NOT NULL DEFAULT '';
ALTER TABLE installs ADD COLUMN IF NOT EXISTS upgraded_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_installs_last_seen ON installs (last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_installs_version ON installs (app_version);

CREATE TABLE IF NOT EXISTS connection_events (
	id          BIGSERIAL PRIMARY KEY,
	install_id  TEXT NOT NULL,
	platform    TEXT NOT NULL,
	app_version TEXT NOT NULL DEFAULT '',
	event_type  TEXT NOT NULL,
	created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_conn_events_created ON connection_events (created_at DESC);

CREATE TABLE IF NOT EXISTS concurrency_snapshots (
	id             BIGSERIAL PRIMARY KEY,
	recorded_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
	online_total   INT NOT NULL,
	android_count  INT NOT NULL,
	pc_count       INT NOT NULL,
	browser_count  INT NOT NULL,
	active_parties INT NOT NULL,
	active_sockets INT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_snapshots_recorded ON concurrency_snapshots (recorded_at DESC);

CREATE TABLE IF NOT EXISTS error_reports (
	id            BIGSERIAL PRIMARY KEY,
	created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
	install_id    TEXT NOT NULL DEFAULT '',
	platform      TEXT NOT NULL,
	app_version   TEXT NOT NULL DEFAULT '',
	error_type    TEXT NOT NULL,
	error_message TEXT NOT NULL,
	stacktrace    TEXT NOT NULL DEFAULT '',
	device_model  TEXT NOT NULL DEFAULT '',
	os_version    TEXT NOT NULL DEFAULT '',
	abi           TEXT NOT NULL DEFAULT '',
	resolved      BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_errors_created ON error_reports (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_errors_resolved ON error_reports (resolved, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_errors_type ON error_reports (error_type);
CREATE INDEX IF NOT EXISTS idx_errors_version ON error_reports (app_version);

-- Minimal telemetry: counters only. No install ID, no message, no stack trace,
-- no device. A row says how many devices opened which version on which day, or
-- how many errors of one type a version had, and nothing about who.
CREATE TABLE IF NOT EXISTS minimal_usage (
	day         DATE NOT NULL,
	platform    TEXT NOT NULL,
	app_version TEXT NOT NULL DEFAULT '',
	devices     BIGINT NOT NULL DEFAULT 0,
	PRIMARY KEY (day, platform, app_version)
);

CREATE TABLE IF NOT EXISTS minimal_errors (
	day         DATE NOT NULL,
	platform    TEXT NOT NULL,
	app_version TEXT NOT NULL DEFAULT '',
	error_type  TEXT NOT NULL,
	count       BIGINT NOT NULL DEFAULT 0,
	PRIMARY KEY (day, platform, app_version, error_type)
);
`
	_, err := pool.Exec(ctx, ddl)
	return err
}

func (s *Store) worker() {
	defer s.wg.Done()
	for fn := range s.queue {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		fn(ctx)
		cancel()
	}
}

func (s *Store) maintenanceLoop() {
	defer s.wg.Done()
	ticker := time.NewTicker(6 * time.Hour)
	defer ticker.Stop()
	for {
		select {
		case <-s.closed:
			return
		case <-ticker.C:
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			_, _ = s.pool.Exec(ctx, `DELETE FROM concurrency_snapshots WHERE recorded_at < NOW() - INTERVAL '90 days'`)
			_, _ = s.pool.Exec(ctx, `DELETE FROM connection_events WHERE created_at < NOW() - INTERVAL '90 days'`)
			cancel()
		}
	}
}

func (s *Store) enqueue(fn func(context.Context)) {
	if s.pool == nil {
		return
	}
	select {
	case s.queue <- fn:
	default:
		// Drop if the DB queue is saturated so live traffic never blocks.
	}
}

func (s *Store) Close() {
	s.once.Do(func() {
		close(s.closed)
		close(s.queue)
		s.wg.Wait()
		if s.pool != nil {
			s.pool.Close()
		}
	})
}

func (s *Store) DatabaseEnabled() bool {
	return s.pool != nil
}

// Pool is the database connection pool, or nil without a database. Other features that keep
// their rows in the same database (feedback) share it.
func (s *Store) Pool() *pgxpool.Pool {
	return s.pool
}

func (s *Store) Live() LiveStats {
	if s.liveFn == nil {
		return LiveStats{}
	}
	return s.liveFn()
}

// RecordPresence records an install heartbeat or close event asynchronously.
func (s *Store) RecordPresence(installID, platform, appVersion string, open bool) {
	if s.pool == nil {
		return
	}
	appVersion = truncate(strings.TrimSpace(appVersion), 32)
	eventType := "close"
	if open {
		eventType = "open"
	}

	s.enqueue(func(ctx context.Context) {
		if open {
			// A new session is counted when the previous open was more than 10 minutes ago
			// (a heartbeat is every ~5 minutes).
			const upsertOpen = `
INSERT INTO installs (install_id, platform, app_version, first_version, first_seen_at, last_seen_at, session_count, last_open_at)
VALUES ($1, $2, $3, $3, NOW(), NOW(), 1, NOW())
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
	last_seen_at = NOW(),
	session_count = CASE
		WHEN installs.last_open_at IS NULL OR NOW() - installs.last_open_at > INTERVAL '10 minutes'
		THEN installs.session_count + 1
		ELSE installs.session_count
	END,
	last_open_at = NOW();`
			if _, err := s.pool.Exec(ctx, upsertOpen, installID, platform, appVersion); err != nil {
				log.Printf("telemetry: upsert install failed: %v", err)
			}

			// Log connection event only when starting a session (or once per 10m window)
			// to keep connection_events compact and meaningful for hourly connection charts.
			const insertEvent = `
INSERT INTO connection_events (install_id, platform, app_version, event_type)
SELECT $1, $2, $3, 'open'
WHERE NOT EXISTS (
	SELECT 1 FROM connection_events
	WHERE install_id = $1 AND event_type = 'open' AND created_at > NOW() - INTERVAL '10 minutes'
);`
			if _, err := s.pool.Exec(ctx, insertEvent, installID, platform, appVersion); err != nil {
				log.Printf("telemetry: insert open event failed: %v", err)
			}
		} else {
			const upsertClose = `
UPDATE installs SET
	last_seen_at = NOW(),
	app_version = CASE WHEN $2 <> '' THEN $2 ELSE app_version END
WHERE install_id = $1;`
			_, _ = s.pool.Exec(ctx, upsertClose, installID, appVersion)
			_, _ = s.pool.Exec(ctx,
				`INSERT INTO connection_events (install_id, platform, app_version, event_type) VALUES ($1, $2, $3, $4)`,
				installID, platform, appVersion, eventType,
			)
		}
	})
}

// firstSightingToday reports whether dailyID is new for day, and remembers it
// until the day changes.
func (s *Store) firstSightingToday(day, dailyID string) bool {
	s.minimalMu.Lock()
	defer s.minimalMu.Unlock()
	if s.minimalDay != day || s.minimalSeen == nil {
		s.minimalDay = day
		s.minimalSeen = make(map[string]struct{})
	}
	if _, seen := s.minimalSeen[dailyID]; seen {
		return false
	}
	s.minimalSeen[dailyID] = struct{}{}
	return true
}

// RecordMinimalPresence counts a minimal-level device once a day, by version
// and platform. dailyID is the client's per-day ID: it is used to avoid
// counting the same device twice and is not stored.
func (s *Store) RecordMinimalPresence(dailyID, platform, appVersion string) {
	if s.pool == nil {
		return
	}
	day := time.Now().UTC().Format("2006-01-02")
	if !s.firstSightingToday(day, dailyID) {
		return
	}
	platform = truncate(strings.TrimSpace(platform), 24)
	appVersion = truncate(strings.TrimSpace(appVersion), 32)
	s.enqueue(func(ctx context.Context) {
		const q = `
INSERT INTO minimal_usage (day, platform, app_version, devices) VALUES ($1::date, $2, $3, 1)
ON CONFLICT (day, platform, app_version) DO UPDATE SET devices = minimal_usage.devices + 1;`
		if _, err := s.pool.Exec(ctx, q, day, platform, appVersion); err != nil {
			log.Printf("telemetry: minimal usage failed: %v", err)
		}
	})
}

// RecordMinimalError counts one error of errorType. Nothing else about the
// report is accepted here: not the message, the stack trace, the device or
// the install.
func (s *Store) RecordMinimalError(ctx context.Context, platform, appVersion, errorType string) error {
	if s.pool == nil {
		return nil
	}
	const q = `
INSERT INTO minimal_errors (day, platform, app_version, error_type, count)
VALUES (CURRENT_DATE, $1, $2, $3, 1)
ON CONFLICT (day, platform, app_version, error_type) DO UPDATE SET count = minimal_errors.count + 1;`
	_, err := s.pool.Exec(ctx, q,
		truncate(strings.TrimSpace(platform), 24),
		truncate(strings.TrimSpace(appVersion), 32),
		CleanErrorType(strings.TrimSpace(errorType)),
	)
	return err
}

// RecordSnapshot persists a point-in-time concurrency sample.
func (s *Store) RecordSnapshot(live LiveStats) {
	if s.pool == nil {
		return
	}
	s.enqueue(func(ctx context.Context) {
		const q = `
INSERT INTO concurrency_snapshots (online_total, android_count, pc_count, browser_count, active_parties, active_sockets)
VALUES ($1, $2, $3, $4, $5, $6);`
		if _, err := s.pool.Exec(ctx, q, live.Online, live.Android, live.PC, live.Browser, live.ActiveParties, live.ActiveSockets); err != nil {
			log.Printf("telemetry: insert snapshot failed: %v", err)
		}
	})
}

// RecordError stores a client-reported error along with basic device metadata.
func (s *Store) RecordError(ctx context.Context, in ErrorInput) (int64, error) {
	if s.pool == nil {
		return 0, nil
	}
	const q = `
INSERT INTO error_reports (
	install_id, platform, app_version, error_type, error_message,
	stacktrace, device_model, os_version, abi
) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
RETURNING id;`
	var id int64
	err := s.pool.QueryRow(
		ctx, q,
		truncate(strings.TrimSpace(in.InstallID), 64),
		truncate(strings.TrimSpace(in.Platform), 24),
		truncate(strings.TrimSpace(in.AppVersion), 32),
		truncate(strings.TrimSpace(in.ErrorType), 64),
		truncate(strings.TrimSpace(in.ErrorMessage), 1000),
		truncate(strings.TrimSpace(in.Stacktrace), 8192),
		truncate(strings.TrimSpace(in.DeviceModel), 120),
		truncate(strings.TrimSpace(in.OSVersion), 64),
		truncate(strings.TrimSpace(in.ABI), 32),
	).Scan(&id)
	return id, err
}

func parseRangeDuration(rangeStr string) (string, time.Duration, string) {
	switch rangeStr {
	case "24h":
		return "24h", 24 * time.Hour, "15 minutes"
	case "30d":
		return "30d", 30 * 24 * time.Hour, "6 hours"
	default:
		return "7d", 7 * 24 * time.Hour, "1 hour"
	}
}

func (s *Store) Overview(ctx context.Context, rangeStr string) (OverviewData, error) {
	normRange, dur, bucket := parseRangeDuration(rangeStr)
	live := LiveStats{}
	if s.liveFn != nil {
		live = s.liveFn()
	}

	out := OverviewData{
		Range:             normRange,
		GeneratedAt:       time.Now().UTC(),
		ServerVersion:     s.version,
		DatabaseEnabled:   s.pool != nil,
		UptimeSec:         int64(time.Since(s.startedAt).Seconds()),
		Live:              live,
		ConcurrencySeries: []SnapshotPoint{},
		HourlyConnections: make([]HourlyBucket, 24),
		Versions:          []VersionStat{},
		Upgrades:          []UpgradeStat{},
		Platforms:         []PlatformStat{},
		TopErrors:         []ErrorGroupStat{},
		ErrorDevices:      []DeviceStat{},
		Minimal:           MinimalStats{Errors: []MinimalErrorStat{}},
	}
	for h := 0; h < 24; h++ {
		out.HourlyConnections[h].Hour = h
	}
	if s.pool == nil {
		return out, nil
	}

	since := time.Now().UTC().Add(-dur)

	// 1. Summary KPIs
	const summaryQ = `
SELECT
	COUNT(*)::INT AS total_installs,
	COUNT(*) FILTER (WHERE last_seen_at >= NOW() - INTERVAL '24 hours')::INT AS dau,
	COUNT(*) FILTER (WHERE last_seen_at >= NOW() - INTERVAL '7 days')::INT AS wau,
	COUNT(*) FILTER (WHERE last_seen_at >= NOW() - INTERVAL '30 days')::INT AS mau,
	COUNT(*) FILTER (WHERE first_seen_at >= $1)::INT AS new_in_period,
	COUNT(*) FILTER (WHERE last_seen_at >= $1 AND first_seen_at < $1)::INT AS returning_in_period,
	COUNT(*) FILTER (WHERE last_seen_at < NOW() - INTERVAL '14 days')::INT AS lost_installs,
	COALESCE(SUM(session_count), 0)::BIGINT AS total_sessions
FROM installs;`
	err := s.pool.QueryRow(ctx, summaryQ, since).Scan(
		&out.Summary.TotalInstalls,
		&out.Summary.DAU,
		&out.Summary.WAU,
		&out.Summary.MAU,
		&out.Summary.NewInPeriod,
		&out.Summary.ReturningInPeriod,
		&out.Summary.LostInstalls,
		&out.Summary.TotalSessions,
	)
	if err != nil {
		return out, err
	}
	if out.Summary.TotalInstalls > 0 {
		active := out.Summary.TotalInstalls - out.Summary.LostInstalls
		out.Summary.RetentionRatePct = float64(active) * 100.0 / float64(out.Summary.TotalInstalls)
	}

	const errSummaryQ = `
SELECT
	COUNT(*) FILTER (WHERE created_at >= $1)::INT,
	COUNT(*) FILTER (WHERE NOT resolved)::INT
FROM error_reports;`
	if err := s.pool.QueryRow(ctx, errSummaryQ, since).Scan(&out.Summary.ErrorsInPeriod, &out.Summary.UnresolvedErrors); err != nil {
		return out, err
	}

	const peakQ = `SELECT COALESCE(MAX(online_total), 0)::INT FROM concurrency_snapshots WHERE recorded_at >= $1;`
	_ = s.pool.QueryRow(ctx, peakQ, since).Scan(&out.Summary.PeakOnline)
	if live.Online > out.Summary.PeakOnline {
		out.Summary.PeakOnline = live.Online
	}

	// 2. Concurrency time series (bucketed)
	seriesQ := fmt.Sprintf(`
SELECT
	to_timestamp(floor(extract(epoch FROM recorded_at) / extract(epoch FROM INTERVAL '%s')) * extract(epoch FROM INTERVAL '%s')) AS bucket,
	MAX(online_total)::INT,
	MAX(android_count)::INT,
	MAX(pc_count)::INT,
	MAX(browser_count)::INT,
	MAX(active_parties)::INT
FROM concurrency_snapshots
WHERE recorded_at >= $1
GROUP BY bucket
ORDER BY bucket ASC;`, bucket, bucket)
	if rows, err := s.pool.Query(ctx, seriesQ, since); err == nil {
		defer rows.Close()
		for rows.Next() {
			var pt SnapshotPoint
			if scanErr := rows.Scan(&pt.Timestamp, &pt.Online, &pt.Android, &pt.PC, &pt.Browser, &pt.ActiveParties); scanErr == nil {
				out.ConcurrencySeries = append(out.ConcurrencySeries, pt)
			}
		}
	}

	// 3. Hourly connection moments (0..23 UTC)
	const hourlyQ = `
SELECT
	EXTRACT(HOUR FROM created_at AT TIME ZONE 'UTC')::INT AS hr,
	COUNT(*)::INT AS conns,
	COUNT(DISTINCT install_id)::INT AS uniq
FROM connection_events
WHERE event_type = 'open' AND created_at >= $1
GROUP BY hr
ORDER BY hr ASC;`
	if rows, err := s.pool.Query(ctx, hourlyQ, since); err == nil {
		defer rows.Close()
		for rows.Next() {
			var hr, conns, uniq int
			if scanErr := rows.Scan(&hr, &conns, &uniq); scanErr == nil && hr >= 0 && hr < 24 {
				out.HourlyConnections[hr] = HourlyBucket{Hour: hr, Connections: conns, UniqueUsers: uniq}
			}
		}
	}

	// 4. Version adoption & errors per version
	const versionsQ = `
SELECT
	ver,
	cnt,
	act24,
	COALESCE(err_cnt, 0)::INT AS err_cnt
FROM (
	SELECT
		CASE WHEN app_version = '' THEN 'unknown' ELSE app_version END AS ver,
		COUNT(*)::INT AS cnt,
		COUNT(*) FILTER (WHERE last_seen_at >= NOW() - INTERVAL '24 hours')::INT AS act24
	FROM installs
	GROUP BY ver
) i
LEFT JOIN (
	SELECT
		CASE WHEN app_version = '' THEN 'unknown' ELSE app_version END AS ever,
		COUNT(*)::INT AS err_cnt
	FROM error_reports
	WHERE created_at >= $1
	GROUP BY ever
) e ON e.ever = i.ver
ORDER BY cnt DESC, ver DESC
LIMIT 15;`
	if rows, err := s.pool.Query(ctx, versionsQ, since); err == nil {
		defer rows.Close()
		for rows.Next() {
			var v VersionStat
			if scanErr := rows.Scan(&v.Version, &v.Count, &v.Active24h, &v.ErrorCount); scanErr == nil {
				if out.Summary.TotalInstalls > 0 {
					v.Pct = float64(v.Count) * 100.0 / float64(out.Summary.TotalInstalls)
				}
				out.Versions = append(out.Versions, v)
			}
		}
	}

	// 4b. Version upgrades & outdated active installs
	const upgradesQ = `
SELECT previous_version, app_version, COUNT(*)::INT AS cnt
FROM installs
WHERE previous_version <> '' AND upgraded_at >= $1
GROUP BY previous_version, app_version
ORDER BY cnt DESC
LIMIT 10;`
	if rows, err := s.pool.Query(ctx, upgradesQ, since); err == nil {
		defer rows.Close()
		for rows.Next() {
			var u UpgradeStat
			if rows.Scan(&u.FromVersion, &u.ToVersion, &u.Count) == nil {
				out.UpgradedInPeriod += u.Count
				out.Upgrades = append(out.Upgrades, u)
			}
		}
	}
	latestVer := s.version
	if latestVer == "" || latestVer == "dev" {
		_ = s.pool.QueryRow(ctx, `SELECT app_version FROM installs WHERE app_version <> '' ORDER BY app_version DESC LIMIT 1`).Scan(&latestVer)
	}
	out.LatestVersion = latestVer
	if latestVer != "" {
		_ = s.pool.QueryRow(ctx, `
SELECT COUNT(*)::INT FROM installs
WHERE last_seen_at >= NOW() - INTERVAL '7 days' AND app_version <> '' AND app_version <> $1;`, latestVer).Scan(&out.OutdatedActive7d)
	}

	// 5. Platform split
	const platformsQ = `SELECT platform, COUNT(*)::INT FROM installs GROUP BY platform ORDER BY COUNT(*) DESC;`
	if rows, err := s.pool.Query(ctx, platformsQ); err == nil {
		defer rows.Close()
		for rows.Next() {
			var p PlatformStat
			if scanErr := rows.Scan(&p.Platform, &p.Count); scanErr == nil {
				if out.Summary.TotalInstalls > 0 {
					p.Pct = float64(p.Count) * 100.0 / float64(out.Summary.TotalInstalls)
				}
				out.Platforms = append(out.Platforms, p)
			}
		}
	}

	// 6. Top error types
	const topErrorsQ = `
SELECT
	error_type,
	COUNT(*)::INT AS cnt,
	COUNT(*) FILTER (WHERE NOT resolved)::INT AS unres,
	COUNT(DISTINCT NULLIF(install_id, ''))::INT AS affected,
	MAX(created_at) AS last_seen
FROM error_reports
WHERE created_at >= $1
GROUP BY error_type
ORDER BY cnt DESC
LIMIT 10;`
	if rows, err := s.pool.Query(ctx, topErrorsQ, since); err == nil {
		defer rows.Close()
		for rows.Next() {
			var g ErrorGroupStat
			if scanErr := rows.Scan(&g.ErrorType, &g.Count, &g.Unresolved, &g.AffectedInstalls, &g.LastSeen); scanErr == nil {
				out.TopErrors = append(out.TopErrors, g)
			}
		}
	}

	// 6b. Minimal level: device-days and errors by type
	_ = s.pool.QueryRow(ctx, `SELECT COALESCE(SUM(devices), 0)::BIGINT FROM minimal_usage WHERE day >= $1::date`, since).Scan(&out.Minimal.DeviceDays)
	if rows, err := s.pool.Query(ctx, `SELECT error_type, SUM(count)::BIGINT AS c FROM minimal_errors WHERE day >= $1::date GROUP BY error_type ORDER BY c DESC LIMIT 10`, since); err == nil {
		defer rows.Close()
		for rows.Next() {
			var m MinimalErrorStat
			if scanErr := rows.Scan(&m.ErrorType, &m.Count); scanErr == nil {
				out.Minimal.Errors = append(out.Minimal.Errors, m)
			}
		}
	}

	// 7. Affected devices
	const devQ = `
SELECT
	CASE WHEN device_model = '' THEN 'Unknown device' ELSE device_model END AS dev,
	CASE WHEN os_version = '' THEN 'Unknown OS' ELSE os_version END AS osv,
	COUNT(*)::INT AS cnt
FROM error_reports
WHERE created_at >= $1
GROUP BY dev, osv
ORDER BY cnt DESC
LIMIT 10;`
	if rows, err := s.pool.Query(ctx, devQ, since); err == nil {
		defer rows.Close()
		for rows.Next() {
			var d DeviceStat
			if scanErr := rows.Scan(&d.DeviceModel, &d.OSVersion, &d.Count); scanErr == nil {
				out.ErrorDevices = append(out.ErrorDevices, d)
			}
		}
	}

	return out, nil
}

func (s *Store) ListErrors(ctx context.Context, f ErrorFilter) (ErrorListResponse, error) {
	out := ErrorListResponse{
		Items:      []ErrorReport{},
		Versions:   []string{},
		ErrorTypes: []string{},
	}
	if s.pool == nil {
		return out, nil
	}

	if f.Limit <= 0 || f.Limit > 200 {
		f.Limit = 50
	}
	if f.Offset < 0 {
		f.Offset = 0
	}

	var conds []string
	var args []interface{}
	addArg := func(v interface{}) string {
		args = append(args, v)
		return fmt.Sprintf("$%d", len(args))
	}

	if f.Version != "" {
		conds = append(conds, "app_version = "+addArg(f.Version))
	}
	if f.Platform != "" {
		conds = append(conds, "platform = "+addArg(f.Platform))
	}
	if f.ErrorType != "" {
		conds = append(conds, "error_type = "+addArg(f.ErrorType))
	}
	if f.UnresolvedOnly {
		conds = append(conds, "NOT resolved")
	}
	if f.Search != "" {
		p := addArg("%" + f.Search + "%")
		conds = append(conds, fmt.Sprintf("(error_message ILIKE %s OR device_model ILIKE %s OR error_type ILIKE %s)", p, p, p))
	}

	where := ""
	if len(conds) > 0 {
		where = "WHERE " + strings.Join(conds, " AND ")
	}

	countQ := "SELECT COUNT(*)::INT FROM error_reports " + where
	if err := s.pool.QueryRow(ctx, countQ, args...).Scan(&out.Total); err != nil {
		return out, err
	}

	limitArg := addArg(f.Limit)
	offsetArg := addArg(f.Offset)
	listQ := fmt.Sprintf(`
SELECT id, created_at, install_id, platform, app_version, error_type, error_message,
       stacktrace, device_model, os_version, abi, resolved
FROM error_reports %s
ORDER BY created_at DESC
LIMIT %s OFFSET %s;`, where, limitArg, offsetArg)

	rows, err := s.pool.Query(ctx, listQ, args...)
	if err != nil {
		return out, err
	}
	defer rows.Close()
	for rows.Next() {
		var r ErrorReport
		if err := rows.Scan(
			&r.ID, &r.CreatedAt, &r.InstallID, &r.Platform, &r.AppVersion,
			&r.ErrorType, &r.ErrorMessage, &r.Stacktrace, &r.DeviceModel,
			&r.OSVersion, &r.ABI, &r.Resolved,
		); err == nil {
			out.Items = append(out.Items, r)
		}
	}

	if vRows, err := s.pool.Query(ctx, `SELECT DISTINCT app_version FROM error_reports WHERE app_version <> '' ORDER BY app_version DESC LIMIT 30`); err == nil {
		defer vRows.Close()
		for vRows.Next() {
			var v string
			if vRows.Scan(&v) == nil {
				out.Versions = append(out.Versions, v)
			}
		}
	}

	if tRows, err := s.pool.Query(ctx, `SELECT DISTINCT error_type FROM error_reports ORDER BY error_type ASC LIMIT 50`); err == nil {
		defer tRows.Close()
		for tRows.Next() {
			var t string
			if tRows.Scan(&t) == nil {
				out.ErrorTypes = append(out.ErrorTypes, t)
			}
		}
	}

	return out, nil
}

func (s *Store) SetErrorResolved(ctx context.Context, id int64, resolved bool) error {
	if s.pool == nil {
		return nil
	}
	_, err := s.pool.Exec(ctx, `UPDATE error_reports SET resolved = $2 WHERE id = $1`, id, resolved)
	return err
}

func (s *Store) DeleteError(ctx context.Context, id int64) error {
	if s.pool == nil {
		return nil
	}
	_, err := s.pool.Exec(ctx, `DELETE FROM error_reports WHERE id = $1`, id)
	return err
}

func (s *Store) ExportErrorsCSV(ctx context.Context) ([]byte, error) {
	var buf bytes.Buffer
	w := csv.NewWriter(&buf)
	_ = w.Write([]string{"id", "created_at", "platform", "app_version", "error_type", "error_message", "device_model", "os_version", "abi", "resolved"})
	if s.pool != nil {
		rows, err := s.pool.Query(ctx, `
SELECT id, created_at, platform, app_version, error_type, error_message, device_model, os_version, abi, resolved
FROM error_reports ORDER BY created_at DESC LIMIT 5000;`)
		if err != nil {
			return nil, err
		}
		defer rows.Close()
		for rows.Next() {
			var id int64
			var createdAt time.Time
			var platform, ver, etype, msg, dev, osVer, abi string
			var resolved bool
			if rows.Scan(&id, &createdAt, &platform, &ver, &etype, &msg, &dev, &osVer, &abi, &resolved) == nil {
				_ = w.Write([]string{
					fmt.Sprint(id),
					createdAt.UTC().Format(time.RFC3339),
					platform, ver, etype, msg, dev, osVer, abi,
					fmt.Sprint(resolved),
				})
			}
		}
	}
	w.Flush()
	return buf.Bytes(), nil
}

func (s *Store) GetError(ctx context.Context, id int64) (ErrorReport, bool, error) {
	var r ErrorReport
	if s.pool == nil {
		return r, false, nil
	}
	const q = `
SELECT id, created_at, install_id, platform, app_version, error_type, error_message,
       stacktrace, device_model, os_version, abi, resolved
FROM error_reports WHERE id = $1;`
	err := s.pool.QueryRow(ctx, q, id).Scan(
		&r.ID, &r.CreatedAt, &r.InstallID, &r.Platform, &r.AppVersion,
		&r.ErrorType, &r.ErrorMessage, &r.Stacktrace, &r.DeviceModel,
		&r.OSVersion, &r.ABI, &r.Resolved,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return r, false, nil
		}
		return r, false, err
	}
	return r, true, nil
}

func (s *Store) BulkResolveByType(ctx context.Context, errorType string, resolved bool) (int64, error) {
	if s.pool == nil {
		return 0, nil
	}
	tag, err := s.pool.Exec(ctx, `UPDATE error_reports SET resolved = $2 WHERE error_type = $1`, errorType, resolved)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

type InstallRecord struct {
	InstallID       string    `json:"installId"`
	Platform        string    `json:"platform"`
	AppVersion      string    `json:"appVersion"`
	FirstVersion    string    `json:"firstVersion"`
	PreviousVersion string    `json:"previousVersion"`
	FirstSeenAt     time.Time `json:"firstSeenAt"`
	LastSeenAt      time.Time `json:"lastSeenAt"`
	SessionCount    int64     `json:"sessionCount"`
	Status          string    `json:"status"`
}

type InstallFilter struct {
	Platform string
	Version  string
	Status   string // "active24h" | "returning" | "lost" | "all"
	Limit    int
	Offset   int
}

type InstallListResponse struct {
	Items    []InstallRecord `json:"items"`
	Total    int             `json:"total"`
	Versions []string        `json:"versions"`
}

func (s *Store) ListInstalls(ctx context.Context, f InstallFilter) (InstallListResponse, error) {
	out := InstallListResponse{
		Items:    []InstallRecord{},
		Versions: []string{},
	}
	if s.pool == nil {
		return out, nil
	}
	if f.Limit <= 0 || f.Limit > 200 {
		f.Limit = 50
	}
	if f.Offset < 0 {
		f.Offset = 0
	}

	var conds []string
	var args []interface{}
	addArg := func(v interface{}) string {
		args = append(args, v)
		return fmt.Sprintf("$%d", len(args))
	}

	if f.Platform != "" {
		conds = append(conds, "platform = "+addArg(f.Platform))
	}
	if f.Version != "" {
		conds = append(conds, "app_version = "+addArg(f.Version))
	}
	switch f.Status {
	case "active24h":
		conds = append(conds, "last_seen_at >= NOW() - INTERVAL '24 hours'")
	case "returning":
		conds = append(conds, "session_count > 1")
	case "lost":
		conds = append(conds, "last_seen_at < NOW() - INTERVAL '14 days'")
	}

	where := ""
	if len(conds) > 0 {
		where = "WHERE " + strings.Join(conds, " AND ")
	}

	if err := s.pool.QueryRow(ctx, "SELECT COUNT(*)::INT FROM installs "+where, args...).Scan(&out.Total); err != nil {
		return out, err
	}

	limitArg := addArg(f.Limit)
	offsetArg := addArg(f.Offset)
	listQ := fmt.Sprintf(`
SELECT install_id, platform, app_version, first_version, previous_version, first_seen_at, last_seen_at, session_count,
	CASE
		WHEN last_seen_at >= NOW() - INTERVAL '24 hours' THEN 'active24h'
		WHEN last_seen_at < NOW() - INTERVAL '14 days' THEN 'lost'
		ELSE 'active'
	END AS status
FROM installs %s
ORDER BY last_seen_at DESC
LIMIT %s OFFSET %s;`, where, limitArg, offsetArg)

	rows, err := s.pool.Query(ctx, listQ, args...)
	if err != nil {
		return out, err
	}
	defer rows.Close()
	for rows.Next() {
		var rec InstallRecord
		if rows.Scan(&rec.InstallID, &rec.Platform, &rec.AppVersion, &rec.FirstVersion, &rec.PreviousVersion, &rec.FirstSeenAt, &rec.LastSeenAt, &rec.SessionCount, &rec.Status) == nil {
			out.Items = append(out.Items, rec)
		}
	}

	if vRows, err := s.pool.Query(ctx, `SELECT DISTINCT app_version FROM installs WHERE app_version <> '' ORDER BY app_version DESC LIMIT 30`); err == nil {
		defer vRows.Close()
		for vRows.Next() {
			var v string
			if vRows.Scan(&v) == nil {
				out.Versions = append(out.Versions, v)
			}
		}
	}

	return out, nil
}

func (s *Store) FormatOverviewMarkdown(ctx context.Context, rangeStr string) (string, error) {
	d, err := s.Overview(ctx, rangeStr)
	if err != nil {
		return "", err
	}
	var b strings.Builder
	fmt.Fprintf(&b, "# BitChord-X Telemetry Brief (%s)\n", d.Range)
	fmt.Fprintf(&b, "- Generated: %s\n", d.GeneratedAt.Format(time.RFC3339))
	fmt.Fprintf(&b, "- Server Version: %s | Uptime: %ds | DB Enabled: %v\n\n", d.ServerVersion, d.UptimeSec, d.DatabaseEnabled)

	b.WriteString("## Live\n")
	fmt.Fprintf(&b, "- Online Now: %d (android=%d, pc=%d, browser=%d)\n", d.Live.Online, d.Live.Android, d.Live.PC, d.Live.Browser)
	fmt.Fprintf(&b, "- Listen Together: %d active parties, %d members, %d sockets\n\n", d.Live.ActiveParties, d.Live.TotalPartyMembers, d.Live.ActiveSockets)

	b.WriteString("## Users & Retention\n")
	fmt.Fprintf(&b, "- Total Installs: %d | DAU: %d | WAU: %d | MAU: %d\n", d.Summary.TotalInstalls, d.Summary.DAU, d.Summary.WAU, d.Summary.MAU)
	fmt.Fprintf(&b, "- New (%s): %d | Returning (%s): %d | Lost (>14d): %d | Retention: %.1f%%\n", d.Range, d.Summary.NewInPeriod, d.Range, d.Summary.ReturningInPeriod, d.Summary.LostInstalls, d.Summary.RetentionRatePct)
	fmt.Fprintf(&b, "- Peak Online (%s): %d | Total Sessions: %d\n\n", d.Range, d.Summary.PeakOnline, d.Summary.TotalSessions)

	b.WriteString("## Version Adoption & Upgrades\n")
	if d.LatestVersion != "" {
		fmt.Fprintf(&b, "- Latest Version: %s | Upgraded (%s): %d | Outdated active (7d): %d\n", d.LatestVersion, d.Range, d.UpgradedInPeriod, d.OutdatedActive7d)
	}
	for _, u := range d.Upgrades {
		fmt.Fprintf(&b, "- Upgrade %s -> %s: %d installs\n", u.FromVersion, u.ToVersion, u.Count)
	}
	if len(d.Versions) == 0 {
		b.WriteString("- (none)\n")
	} else {
		for _, v := range d.Versions {
			fmt.Fprintf(&b, "- %s: %d installs (%.1f%%), %d active 24h, %d errors (%s)\n", v.Version, v.Count, v.Pct, v.Active24h, v.ErrorCount, d.Range)
		}
	}

	b.WriteString("\n## Top Errors\n")
	fmt.Fprintf(&b, "- Errors in %s: %d | Unresolved total: %d\n", d.Range, d.Summary.ErrorsInPeriod, d.Summary.UnresolvedErrors)
	for _, e := range d.TopErrors {
		fmt.Fprintf(&b, "- %s: %d total (%d unresolved), %d affected installs, last=%s\n", e.ErrorType, e.Count, e.Unresolved, e.AffectedInstalls, e.LastSeen.Format(time.RFC3339))
	}

	if len(d.ErrorDevices) > 0 {
		b.WriteString("\n## Top Affected Devices\n")
		for _, dev := range d.ErrorDevices {
			fmt.Fprintf(&b, "- %s (%s): %d\n", dev.DeviceModel, dev.OSVersion, dev.Count)
		}
	}
	return b.String(), nil
}

func (s *Store) FormatErrorsMarkdown(ctx context.Context, f ErrorFilter) (string, error) {
	res, err := s.ListErrors(ctx, f)
	if err != nil {
		return "", err
	}
	var b strings.Builder
	fmt.Fprintf(&b, "# BitChord-X Error Reports (%d matching, showing %d)\n\n", res.Total, len(res.Items))
	for _, r := range res.Items {
		status := "OPEN"
		if r.Resolved {
			status = "RESOLVED"
		}
		fmt.Fprintf(&b, "## #%d [%s] %s (%s / %s)\n", r.ID, status, r.ErrorType, r.AppVersion, r.Platform)
		fmt.Fprintf(&b, "- Time: %s\n", r.CreatedAt.Format(time.RFC3339))
		fmt.Fprintf(&b, "- Device: %s | OS: %s | ABI: %s\n", r.DeviceModel, r.OSVersion, r.ABI)
		fmt.Fprintf(&b, "- Message: %s\n", r.ErrorMessage)
		if r.Stacktrace != "" {
			fmt.Fprintf(&b, "```\n%s\n```\n", truncate(r.Stacktrace, 1500))
		}
		b.WriteByte('\n')
	}
	return b.String(), nil
}

func FormatErrorDetailMarkdown(r ErrorReport) string {
	var b strings.Builder
	status := "OPEN"
	if r.Resolved {
		status = "RESOLVED"
	}
	fmt.Fprintf(&b, "# Error #%d [%s] %s\n", r.ID, status, r.ErrorType)
	fmt.Fprintf(&b, "- Created: %s\n", r.CreatedAt.Format(time.RFC3339))
	fmt.Fprintf(&b, "- Version: %s | Platform: %s\n", r.AppVersion, r.Platform)
	fmt.Fprintf(&b, "- Device: %s | OS: %s | ABI: %s\n", r.DeviceModel, r.OSVersion, r.ABI)
	fmt.Fprintf(&b, "- Install ID: %s\n", r.InstallID)
	fmt.Fprintf(&b, "\n## Message\n```\n%s\n```\n", r.ErrorMessage)
	if r.Stacktrace != "" {
		fmt.Fprintf(&b, "\n## Stacktrace\n```\n%s\n```\n", r.Stacktrace)
	}
	return b.String()
}

type DatabaseStats struct {
	DatabaseEnabled       bool  `json:"databaseEnabled"`
	DatabaseSizeBytes     int64 `json:"databaseSizeBytes"`
	InstallsCount         int64 `json:"installsCount"`
	ConnectionEventsCount int64 `json:"connectionEventsCount"`
	SnapshotsCount        int64 `json:"snapshotsCount"`
	ErrorsCount           int64 `json:"errorsCount"`
	ResolvedErrorsCount   int64 `json:"resolvedErrorsCount"`
}

func (s *Store) GetDatabaseStats(ctx context.Context) (DatabaseStats, error) {
	out := DatabaseStats{DatabaseEnabled: s.pool != nil}
	if s.pool == nil {
		return out, nil
	}
	const q = `
SELECT
	pg_database_size(current_database())::BIGINT,
	(SELECT COUNT(*)::BIGINT FROM installs),
	(SELECT COUNT(*)::BIGINT FROM connection_events),
	(SELECT COUNT(*)::BIGINT FROM concurrency_snapshots),
	(SELECT COUNT(*)::BIGINT FROM error_reports),
	(SELECT COUNT(*)::BIGINT FROM error_reports WHERE resolved);`
	err := s.pool.QueryRow(ctx, q).Scan(
		&out.DatabaseSizeBytes,
		&out.InstallsCount,
		&out.ConnectionEventsCount,
		&out.SnapshotsCount,
		&out.ErrorsCount,
		&out.ResolvedErrorsCount,
	)
	return out, err
}

func (s *Store) PruneResolvedErrors(ctx context.Context) (int64, error) {
	if s.pool == nil {
		return 0, nil
	}
	tag, err := s.pool.Exec(ctx, `DELETE FROM error_reports WHERE resolved`)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

func (s *Store) PruneOldHistory(ctx context.Context, days int) (int64, error) {
	if s.pool == nil {
		return 0, nil
	}
	if days <= 0 {
		days = 30
	}
	cutoff := time.Now().UTC().Add(-time.Duration(days) * 24 * time.Hour)
	t1, err := s.pool.Exec(ctx, `DELETE FROM concurrency_snapshots WHERE recorded_at < $1`, cutoff)
	if err != nil {
		return 0, err
	}
	t2, err := s.pool.Exec(ctx, `DELETE FROM connection_events WHERE created_at < $1`, cutoff)
	if err != nil {
		return t1.RowsAffected(), err
	}
	// The minimal counters carry no identity and are tiny, so they are kept a year.
	yearAgo := time.Now().UTC().Add(-365 * 24 * time.Hour)
	t3, _ := s.pool.Exec(ctx, `DELETE FROM minimal_usage WHERE day < $1::date`, yearAgo)
	t4, _ := s.pool.Exec(ctx, `DELETE FROM minimal_errors WHERE day < $1::date`, yearAgo)
	return t1.RowsAffected() + t2.RowsAffected() + t3.RowsAffected() + t4.RowsAffected(), nil
}

func (s *Store) ResetDatabase(ctx context.Context) error {
	if s.pool == nil {
		return nil
	}
	_, err := s.pool.Exec(ctx, `TRUNCATE TABLE installs, connection_events, concurrency_snapshots, error_reports, minimal_usage, minimal_errors RESTART IDENTITY;`)
	return err
}

func truncate(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen]
}
