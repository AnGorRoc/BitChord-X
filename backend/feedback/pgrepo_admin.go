package feedback

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const adminDDL = `
CREATE TABLE IF NOT EXISTS feedback_templates (
	id         BIGSERIAL PRIMARY KEY,
	name       TEXT NOT NULL,
	kind       TEXT NOT NULL DEFAULT 'message',
	body       TEXT NOT NULL,
	updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fb_tickets_kind ON feedback_tickets (kind, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_fb_tickets_tags ON feedback_tickets USING GIN (tags);
`

// adminCols are ticketCols plus what the dashboard's list shows. The counts and the latest
// message are correlated subqueries: with idx_fb_messages_ticket each is an index probe, and a
// page is at most 100 rows.
const adminCols = ticketCols + `, t.priority, t.tags, t.unread_admin,
	(SELECT COUNT(*) FROM feedback_messages m WHERE m.ticket_id = t.id),
	(SELECT COUNT(*) FROM feedback_attachments a WHERE a.ticket_id = t.id),
	COALESCE((SELECT m.body FROM feedback_messages m WHERE m.ticket_id = t.id ORDER BY m.id DESC LIMIT 1), ''),
	COALESCE((SELECT m.author FROM feedback_messages m WHERE m.ticket_id = t.id ORDER BY m.id DESC LIMIT 1), '')`

func scanAdminTicket(row pgx.Row, extra ...any) (AdminTicket, error) {
	var at AdminTicket
	var body string
	dst := append([]any{&at.Priority, &at.Tags, &at.UnreadAdmin, &at.Messages, &at.Attachments, &body, &at.LastAuthor}, extra...)
	t, err := scanTicket(row, dst...)
	if err != nil {
		return at, err
	}
	at.Ticket = t
	at.Snippet = snippet(body)
	if at.Tags == nil {
		at.Tags = []string{}
	}
	return at, nil
}

// likePattern escapes s for ILIKE so a search for "100%" means those characters.
func likePattern(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return "%" + r.Replace(s) + "%"
}

func (p *pgRepo) AdminList(ctx context.Context, f AdminFilter) (AdminPage, error) {
	var where []string
	var args []any
	arg := func(v any) string {
		args = append(args, v)
		return fmt.Sprintf("$%d", len(args))
	}
	closed := []string{StatusDone, StatusWontFix, StatusDuplicate}
	switch f.Status {
	case "":
	case "open":
		where = append(where, "NOT (t.status = ANY("+arg(closed)+"))")
	case "closed":
		where = append(where, "t.status = ANY("+arg(closed)+")")
	case "unread":
		where = append(where, "t.unread_admin")
	default:
		where = append(where, "t.status = "+arg(f.Status))
	}
	if f.Kind != "" {
		where = append(where, "t.kind = "+arg(f.Kind))
	}
	if f.Platform != "" {
		where = append(where, "t.platform = "+arg(f.Platform))
	}
	if f.Version != "" {
		where = append(where, "t.app_version = "+arg(f.Version))
	}
	if f.Tag != "" {
		where = append(where, arg(f.Tag)+" = ANY(t.tags)")
	}
	if f.Query != "" {
		q := arg(likePattern(f.Query))
		where = append(where, "(t.title ILIKE "+q+" OR t.public_id ILIKE "+q+
			" OR EXISTS (SELECT 1 FROM feedback_messages m WHERE m.ticket_id = t.id AND m.body ILIKE "+q+"))")
	}
	if f.Cursor != "" {
		at, id, ok := parseCursor(f.Cursor)
		if !ok {
			return AdminPage{Items: []AdminTicket{}}, nil
		}
		where = append(where, "(t.updated_at, t.id) < ("+arg(time.UnixMicro(at).UTC())+", "+arg(id)+")")
	}
	sql := `SELECT ` + adminCols + `, t.updated_at FROM feedback_tickets t`
	if len(where) > 0 {
		sql += " WHERE " + strings.Join(where, " AND ")
	}
	sql += " ORDER BY t.updated_at DESC, t.id DESC LIMIT " + arg(f.Limit+1)

	rows, err := p.pool.Query(ctx, sql, args...)
	if err != nil {
		return AdminPage{}, err
	}
	defer rows.Close()
	page := AdminPage{Items: []AdminTicket{}}
	var lastAt time.Time
	var lastID int64
	for rows.Next() {
		var updated time.Time
		at, err := scanAdminTicket(rows, &updated)
		if err != nil {
			return AdminPage{}, err
		}
		if len(page.Items) == f.Limit {
			// The cursor carries microseconds, which is what PostgreSQL keeps.
			page.Next = makeCursor(lastAt.UnixMicro(), lastID)
			break
		}
		page.Items = append(page.Items, at)
		lastAt, lastID = updated, at.ID
	}
	return page, rows.Err()
}

func (p *pgRepo) AdminSummary(ctx context.Context) (AdminSummary, error) {
	s := AdminSummary{ByStatus: map[string]int{}, ByKind: map[string]int{}, Versions: []string{}, Tags: []string{}}
	rows, err := p.pool.Query(ctx, `
		SELECT status, kind, COUNT(*), COUNT(*) FILTER (WHERE unread_admin),
			COUNT(*) FILTER (WHERE satisfaction = 1), COUNT(*) FILTER (WHERE satisfaction = -1)
		FROM feedback_tickets GROUP BY status, kind`)
	if err != nil {
		return s, err
	}
	for rows.Next() {
		var status, kind string
		var n, unread, up, down int
		if err := rows.Scan(&status, &kind, &n, &unread, &up, &down); err != nil {
			rows.Close()
			return s, err
		}
		s.Total += n
		s.ByStatus[status] += n
		s.ByKind[kind] += n
		if !IsClosed(status) {
			s.Open += n
		}
		s.Unread += unread
		s.ThumbsUp += up
		s.ThumbsDn += down
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return s, err
	}
	if s.Versions, err = collectStrings(p.pool.Query(ctx, `
		SELECT app_version FROM feedback_tickets WHERE app_version <> ''
		GROUP BY app_version ORDER BY MAX(created_at) DESC LIMIT 30`)); err != nil {
		return s, err
	}
	if s.Tags, err = collectStrings(p.pool.Query(ctx, `
		SELECT DISTINCT tag FROM feedback_tickets, unnest(tags) AS tag ORDER BY tag LIMIT 100`)); err != nil {
		return s, err
	}
	err = p.pool.QueryRow(ctx, `SELECT COALESCE(SUM(bytes), 0) FROM feedback_attachments`).Scan(&s.DiskBytes)
	return s, err
}

func collectStrings(rows pgx.Rows, err error) ([]string, error) {
	out, err := collectSHAs(rows, err)
	if out == nil {
		out = []string{}
	}
	return out, err
}

func (p *pgRepo) AdminTicket(ctx context.Context, publicID string) (Ticket, error) {
	t, err := scanTicket(p.pool.QueryRow(ctx, `SELECT `+ticketCols+` FROM feedback_tickets t WHERE t.public_id = $1`, publicID))
	return t, notFound(err)
}

func (p *pgRepo) AdminDetail(ctx context.Context, publicID string) (AdminDetail, error) {
	var d AdminDetail
	var dup *string
	var reporterID int64
	var since time.Time
	at, err := scanAdminTicket(p.pool.QueryRow(ctx, `
		SELECT `+adminCols+`, t.locale, t.device_model, t.os_version, t.abi, t.screen, t.telemetry_level, t.install_id,
			(SELECT d.public_id FROM feedback_tickets d WHERE d.id = t.duplicate_of),
			r.id, r.blocked, r.created_at,
			(SELECT COUNT(*) FROM feedback_tickets o WHERE o.reporter_id = r.id)
		FROM feedback_tickets t JOIN feedback_reporters r ON r.id = t.reporter_id
		WHERE t.public_id = $1`, publicID),
		&d.Locale, &d.Diagnostics.DeviceModel, &d.Diagnostics.OSVersion, &d.Diagnostics.ABI, &d.Diagnostics.Screen,
		&d.Diagnostics.TelemetryLevel, &d.Diagnostics.InstallID,
		&dup, &reporterID, &d.Reporter.Blocked, &since, &d.Reporter.Tickets)
	if err != nil {
		return d, notFound(err)
	}
	d.AdminTicket = at
	d.Reporter.Since = ms(since)
	if dup != nil {
		d.DuplicateOf = *dup
	}
	if d.Thread, err = p.Thread(ctx, at.ID); err != nil {
		return d, err
	}
	d.Errors = []LinkedError{}
	if d.Diagnostics.InstallID != "" {
		d.Errors, err = p.linkedErrors(ctx, d.Diagnostics.InstallID)
	}
	return d, err
}

// linkedErrors reads the telemetry error reports of the same install, which live in the same
// database. A server whose telemetry tables are missing simply has none.
func (p *pgRepo) linkedErrors(ctx context.Context, installID string) ([]LinkedError, error) {
	out := []LinkedError{}
	var exists bool
	if err := p.pool.QueryRow(ctx, `SELECT to_regclass('error_reports') IS NOT NULL`).Scan(&exists); err != nil || !exists {
		return out, err
	}
	rows, err := p.pool.Query(ctx, `
		SELECT id, created_at, error_type, error_message, app_version FROM error_reports
		WHERE install_id = $1 ORDER BY created_at DESC LIMIT 10`, installID)
	if err != nil {
		return out, err
	}
	defer rows.Close()
	for rows.Next() {
		var e LinkedError
		var created time.Time
		if err := rows.Scan(&e.ID, &created, &e.ErrorType, &e.Message, &e.AppVersion); err != nil {
			return out, err
		}
		e.CreatedAt = ms(created)
		e.Message = snippet(e.Message)
		out = append(out, e)
	}
	return out, rows.Err()
}

func (p *pgRepo) AdminUpdate(ctx context.Context, ticketID int64, pt AdminPatch) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var reporterID int64
	var status string
	if err := tx.QueryRow(ctx, `SELECT reporter_id, status FROM feedback_tickets WHERE id = $1 FOR UPDATE`, ticketID).Scan(&reporterID, &status); err != nil {
		return notFound(err)
	}
	if pt.DuplicateOf != nil {
		var dupID *int64
		if *pt.DuplicateOf != "" {
			var id int64
			if err := tx.QueryRow(ctx, `SELECT id FROM feedback_tickets WHERE public_id = $1 AND id <> $2`, *pt.DuplicateOf, ticketID).Scan(&id); err != nil {
				return notFound(err)
			}
			dupID = &id
		}
		if _, err := tx.Exec(ctx, `UPDATE feedback_tickets SET duplicate_of = $2 WHERE id = $1`, ticketID, dupID); err != nil {
			return err
		}
	}
	if pt.Priority != nil {
		if _, err := tx.Exec(ctx, `UPDATE feedback_tickets SET priority = $2 WHERE id = $1`, ticketID, *pt.Priority); err != nil {
			return err
		}
	}
	if pt.Tags != nil {
		if _, err := tx.Exec(ctx, `UPDATE feedback_tickets SET tags = $2 WHERE id = $1`, ticketID, *pt.Tags); err != nil {
			return err
		}
	}
	if pt.UnreadAdmin != nil {
		if _, err := tx.Exec(ctx, `UPDATE feedback_tickets SET unread_admin = $2 WHERE id = $1`, ticketID, *pt.UnreadAdmin); err != nil {
			return err
		}
	}
	if pt.Status != nil && *pt.Status != status {
		closed := []string{StatusDone, StatusWontFix, StatusDuplicate}
		if _, err := tx.Exec(ctx, `
			UPDATE feedback_tickets SET
				closed_at = CASE WHEN NOT $3 THEN NULL WHEN status = ANY($4) THEN closed_at ELSE NOW() END,
				status = $2, updated_at = NOW()
			WHERE id = $1`, ticketID, *pt.Status, IsClosed(*pt.Status), closed); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO feedback_messages (ticket_id, author, kind, body) VALUES ($1, $2, $3, $4)`,
			ticketID, AuthorAdmin, MsgStatus, *pt.Status); err != nil {
			return err
		}
		if err := bump(ctx, tx, reporterID); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

func (p *pgRepo) SetReporterBlocked(ctx context.Context, ticketID int64, blocked bool) error {
	tag, err := p.pool.Exec(ctx, `
		UPDATE feedback_reporters SET blocked = $2
		WHERE id = (SELECT reporter_id FROM feedback_tickets WHERE id = $1)`, ticketID, blocked)
	if err == nil && tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}

func (p *pgRepo) AttachmentByID(ctx context.Context, id int64) (Attachment, error) {
	a, err := scanAttachment(p.pool.QueryRow(ctx, `SELECT `+attachmentCols+` FROM feedback_attachments a WHERE a.id = $1`, id))
	return a, notFound(err)
}

func scanTemplate(row pgx.Row) (Template, error) {
	var t Template
	var updated time.Time
	err := row.Scan(&t.ID, &t.Name, &t.Kind, &t.Body, &updated)
	t.UpdatedAt = ms(updated)
	return t, err
}

func (p *pgRepo) Templates(ctx context.Context) ([]Template, error) {
	rows, err := p.pool.Query(ctx, `SELECT id, name, kind, body, updated_at FROM feedback_templates ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Template{}
	for rows.Next() {
		t, err := scanTemplate(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func (p *pgRepo) SaveTemplate(ctx context.Context, t Template) (Template, error) {
	if t.ID == 0 {
		return scanTemplate(p.pool.QueryRow(ctx, `
			INSERT INTO feedback_templates (name, kind, body) VALUES ($1, $2, $3)
			RETURNING id, name, kind, body, updated_at`, t.Name, t.Kind, t.Body))
	}
	saved, err := scanTemplate(p.pool.QueryRow(ctx, `
		UPDATE feedback_templates SET name = $2, kind = $3, body = $4, updated_at = NOW() WHERE id = $1
		RETURNING id, name, kind, body, updated_at`, t.ID, t.Name, t.Kind, t.Body))
	if errors.Is(err, pgx.ErrNoRows) {
		return saved, ErrNotFound
	}
	return saved, err
}

func (p *pgRepo) DeleteTemplate(ctx context.Context, id int64) error {
	tag, err := p.pool.Exec(ctx, `DELETE FROM feedback_templates WHERE id = $1`, id)
	if err == nil && tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}
