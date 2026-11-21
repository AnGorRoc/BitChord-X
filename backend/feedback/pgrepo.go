package feedback

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const maxInboxMessages = 200

// pgRepo keeps tickets in the telemetry database.
type pgRepo struct {
	pool *pgxpool.Pool
}

// NewPGRepo returns a repository on pool. Call Migrate before using it.
func NewPGRepo(pool *pgxpool.Pool) Repo { return &pgRepo{pool: pool} }

const ddl = `
CREATE TABLE IF NOT EXISTS feedback_reporters (
	id            BIGSERIAL PRIMARY KEY,
	token_hash    TEXT NOT NULL UNIQUE,
	platform      TEXT NOT NULL DEFAULT '',
	locale        TEXT NOT NULL DEFAULT '',
	blocked       BOOLEAN NOT NULL DEFAULT FALSE,
	inbox_version BIGINT NOT NULL DEFAULT 1,
	created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
	last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS feedback_tickets (
	id               BIGSERIAL PRIMARY KEY,
	public_id        TEXT NOT NULL UNIQUE,
	reporter_id      BIGINT NOT NULL REFERENCES feedback_reporters(id) ON DELETE CASCADE,
	client_ticket_id TEXT NOT NULL,
	kind             TEXT NOT NULL,
	title            TEXT NOT NULL,
	status           TEXT NOT NULL DEFAULT 'new',
	priority         SMALLINT NOT NULL DEFAULT 0,
	tags             TEXT[] NOT NULL DEFAULT '{}',
	duplicate_of     BIGINT REFERENCES feedback_tickets(id) ON DELETE SET NULL,
	platform         TEXT NOT NULL DEFAULT '',
	app_version      TEXT NOT NULL DEFAULT '',
	locale           TEXT NOT NULL DEFAULT '',
	device_model     TEXT NOT NULL DEFAULT '',
	os_version       TEXT NOT NULL DEFAULT '',
	abi              TEXT NOT NULL DEFAULT '',
	screen           TEXT NOT NULL DEFAULT '',
	telemetry_level  TEXT NOT NULL DEFAULT '',
	install_id       TEXT NOT NULL DEFAULT '',
	unread_admin     BOOLEAN NOT NULL DEFAULT TRUE,
	satisfaction     SMALLINT NOT NULL DEFAULT 0,
	created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
	updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
	closed_at        TIMESTAMPTZ,
	UNIQUE (reporter_id, client_ticket_id)
);

CREATE INDEX IF NOT EXISTS idx_fb_tickets_status ON feedback_tickets (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_fb_tickets_updated ON feedback_tickets (updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_fb_tickets_unread ON feedback_tickets (updated_at DESC) WHERE unread_admin;
CREATE INDEX IF NOT EXISTS idx_fb_tickets_reporter ON feedback_tickets (reporter_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_fb_tickets_closed ON feedback_tickets (closed_at) WHERE closed_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS feedback_messages (
	id                BIGSERIAL PRIMARY KEY,
	ticket_id         BIGINT NOT NULL REFERENCES feedback_tickets(id) ON DELETE CASCADE,
	author            TEXT NOT NULL,
	kind              TEXT NOT NULL,
	client_message_id TEXT,
	body              TEXT NOT NULL DEFAULT '',
	created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
	read_at           TIMESTAMPTZ,
	UNIQUE (ticket_id, client_message_id)
);

CREATE INDEX IF NOT EXISTS idx_fb_messages_ticket ON feedback_messages (ticket_id, id);

CREATE TABLE IF NOT EXISTS feedback_attachments (
	id                   BIGSERIAL PRIMARY KEY,
	ticket_id            BIGINT NOT NULL REFERENCES feedback_tickets(id) ON DELETE CASCADE,
	message_id           BIGINT NOT NULL REFERENCES feedback_messages(id) ON DELETE CASCADE,
	client_attachment_id TEXT NOT NULL,
	kind                 TEXT NOT NULL,
	mime                 TEXT NOT NULL,
	sha256               TEXT NOT NULL,
	bytes                BIGINT NOT NULL,
	width                INT NOT NULL DEFAULT 0,
	height               INT NOT NULL DEFAULT 0,
	has_thumb            BOOLEAN NOT NULL DEFAULT FALSE,
	created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
	UNIQUE (message_id, client_attachment_id)
);

CREATE INDEX IF NOT EXISTS idx_fb_attachments_ticket ON feedback_attachments (ticket_id);
CREATE INDEX IF NOT EXISTS idx_fb_attachments_sha ON feedback_attachments (sha256);
`

func (p *pgRepo) Migrate(ctx context.Context) error {
	_, err := p.pool.Exec(ctx, ddl)
	return err
}

func notFound(err error) error {
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	return err
}

func (p *pgRepo) Reporter(ctx context.Context, hash string) (Reporter, error) {
	var r Reporter
	err := p.pool.QueryRow(ctx,
		`SELECT id, blocked, inbox_version FROM feedback_reporters WHERE token_hash = $1`, hash,
	).Scan(&r.ID, &r.Blocked, &r.InboxVersion)
	return r, notFound(err)
}

func (p *pgRepo) CreateReporter(ctx context.Context, hash, platform, locale string) (Reporter, error) {
	var r Reporter
	err := p.pool.QueryRow(ctx, `
		INSERT INTO feedback_reporters (token_hash, platform, locale) VALUES ($1, $2, $3)
		ON CONFLICT (token_hash) DO UPDATE SET last_seen_at = NOW()
		RETURNING id, blocked, inbox_version`, hash, platform, locale,
	).Scan(&r.ID, &r.Blocked, &r.InboxVersion)
	return r, err
}

func (p *pgRepo) TouchReporter(ctx context.Context, id int64) error {
	// Once a minute at most: an inbox poll should not be a write every time.
	_, err := p.pool.Exec(ctx,
		`UPDATE feedback_reporters SET last_seen_at = NOW() WHERE id = $1 AND last_seen_at < NOW() - INTERVAL '1 minute'`, id)
	return err
}

func bump(ctx context.Context, tx pgx.Tx, reporterID int64) error {
	_, err := tx.Exec(ctx, `UPDATE feedback_reporters SET inbox_version = inbox_version + 1 WHERE id = $1`, reporterID)
	return err
}

const ticketCols = `t.id, t.public_id, t.reporter_id, t.kind, t.title, t.status, t.platform, t.app_version,
	t.satisfaction, t.created_at, t.updated_at, t.closed_at`

func scanTicket(row pgx.Row, extra ...any) (Ticket, error) {
	var t Ticket
	var created, updated time.Time
	var closed *time.Time
	dst := append([]any{&t.ID, &t.PublicID, &t.ReporterID, &t.Kind, &t.Title, &t.Status, &t.Platform, &t.AppVersion,
		&t.Satisfaction, &created, &updated, &closed}, extra...)
	if err := row.Scan(dst...); err != nil {
		return t, err
	}
	t.CreatedAt, t.UpdatedAt = ms(created), ms(updated)
	if closed != nil {
		t.ClosedAt = ms(*closed)
	}
	return t, nil
}

const messageCols = `m.id, m.ticket_id, m.author, m.kind, m.body, m.created_at, m.read_at`

func scanMessage(row pgx.Row, extra ...any) (Message, error) {
	var m Message
	var created time.Time
	var read *time.Time
	dst := append([]any{&m.ID, &m.TicketID, &m.Author, &m.Kind, &m.Body, &created, &read}, extra...)
	if err := row.Scan(dst...); err != nil {
		return m, err
	}
	m.CreatedAt = ms(created)
	if read != nil {
		m.ReadAt = ms(*read)
	}
	m.Attachments = []Attachment{}
	return m, nil
}

const attachmentCols = `a.id, a.ticket_id, a.message_id, a.client_attachment_id, a.kind, a.mime, a.sha256, a.bytes,
	a.width, a.height, a.has_thumb`

func scanAttachment(row pgx.Row) (Attachment, error) {
	var a Attachment
	err := row.Scan(&a.ID, &a.TicketID, &a.MessageID, &a.ClientAttachmentID, &a.Kind, &a.Mime, &a.SHA256, &a.Bytes,
		&a.Width, &a.Height, &a.HasThumb)
	return a, err
}

// fillAttachments attaches each message's files, in one query for all of them.
func (p *pgRepo) fillAttachments(ctx context.Context, q interface {
	Query(context.Context, string, ...any) (pgx.Rows, error)
}, msgs []*Message) error {
	if len(msgs) == 0 {
		return nil
	}
	ids := make([]int64, len(msgs))
	byID := make(map[int64]*Message, len(msgs))
	for i, m := range msgs {
		ids[i] = m.ID
		byID[m.ID] = m
	}
	rows, err := q.Query(ctx, `SELECT `+attachmentCols+` FROM feedback_attachments a WHERE a.message_id = ANY($1) ORDER BY a.id`, ids)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		a, err := scanAttachment(rows)
		if err != nil {
			return err
		}
		if m := byID[a.MessageID]; m != nil {
			m.Attachments = append(m.Attachments, a)
		}
	}
	return rows.Err()
}

func (p *pgRepo) CreateTicket(ctx context.Context, in NewTicket, publicID string) (Ticket, Message, bool, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return Ticket{}, Message{}, false, err
	}
	defer tx.Rollback(ctx)

	d := in.Diagnostics
	var id int64
	err = tx.QueryRow(ctx, `
		INSERT INTO feedback_tickets (public_id, reporter_id, client_ticket_id, kind, title, platform, app_version, locale,
			device_model, os_version, abi, screen, telemetry_level, install_id)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
		ON CONFLICT (reporter_id, client_ticket_id) DO NOTHING
		RETURNING id`,
		publicID, in.ReporterID, in.ClientTicketID, in.Kind, in.Title, in.Platform, in.AppVersion, in.Locale,
		d.DeviceModel, d.OSVersion, d.ABI, d.Screen, d.TelemetryLevel, d.InstallID,
	).Scan(&id)
	created := err == nil
	if errors.Is(err, pgx.ErrNoRows) {
		// A retry: hand back what the first attempt made.
		err = tx.QueryRow(ctx, `SELECT id FROM feedback_tickets WHERE reporter_id = $1 AND client_ticket_id = $2`,
			in.ReporterID, in.ClientTicketID).Scan(&id)
	}
	if err != nil {
		return Ticket{}, Message{}, false, err
	}
	if created {
		if _, err := tx.Exec(ctx, `INSERT INTO feedback_messages (ticket_id, author, kind, body) VALUES ($1, $2, $3, $4)`,
			id, AuthorUser, MsgText, in.Body); err != nil {
			return Ticket{}, Message{}, false, err
		}
		if err := bump(ctx, tx, in.ReporterID); err != nil {
			return Ticket{}, Message{}, false, err
		}
	}
	t, err := scanTicket(tx.QueryRow(ctx, `SELECT `+ticketCols+` FROM feedback_tickets t WHERE t.id = $1`, id))
	if err != nil {
		return Ticket{}, Message{}, false, err
	}
	first, err := scanMessage(tx.QueryRow(ctx,
		`SELECT `+messageCols+` FROM feedback_messages m WHERE m.ticket_id = $1 ORDER BY m.id LIMIT 1`, id))
	if err != nil {
		return Ticket{}, Message{}, false, err
	}
	if err := p.fillAttachments(ctx, tx, []*Message{&first}); err != nil {
		return Ticket{}, Message{}, false, err
	}
	return t, first, created, tx.Commit(ctx)
}

func (p *pgRepo) Ticket(ctx context.Context, reporterID int64, publicID string) (Ticket, error) {
	t, err := scanTicket(p.pool.QueryRow(ctx,
		`SELECT `+ticketCols+` FROM feedback_tickets t WHERE t.public_id = $1 AND t.reporter_id = $2`, publicID, reporterID))
	return t, notFound(err)
}

// addUser inserts a message from the person inside tx, idempotent on clientID.
func addUser(ctx context.Context, tx pgx.Tx, t Ticket, clientID, kind, body string) (Message, bool, error) {
	m, err := scanMessage(tx.QueryRow(ctx, `
		INSERT INTO feedback_messages AS m (ticket_id, author, kind, client_message_id, body) VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (ticket_id, client_message_id) DO NOTHING
		RETURNING `+messageCols, t.ID, AuthorUser, kind, clientID, body))
	if errors.Is(err, pgx.ErrNoRows) {
		m, err = scanMessage(tx.QueryRow(ctx,
			`SELECT `+messageCols+` FROM feedback_messages m WHERE m.ticket_id = $1 AND m.client_message_id = $2`, t.ID, clientID))
		return m, false, err
	}
	if err != nil {
		return m, false, err
	}
	if _, err := tx.Exec(ctx, `UPDATE feedback_tickets SET updated_at = NOW(), unread_admin = TRUE WHERE id = $1`, t.ID); err != nil {
		return m, false, err
	}
	return m, true, bump(ctx, tx, t.ReporterID)
}

func (p *pgRepo) AddUserMessage(ctx context.Context, t Ticket, clientID, body string) (Message, bool, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return Message{}, false, err
	}
	defer tx.Rollback(ctx)
	m, created, err := addUser(ctx, tx, t, clientID, MsgText, body)
	if err != nil {
		return m, false, err
	}
	if !created {
		if err := p.fillAttachments(ctx, tx, []*Message{&m}); err != nil {
			return m, false, err
		}
	}
	return m, created, tx.Commit(ctx)
}

func (p *pgRepo) AddAdminMessage(ctx context.Context, ticketID int64, kind, body string) (Message, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return Message{}, err
	}
	defer tx.Rollback(ctx)
	var reporterID int64
	err = tx.QueryRow(ctx, `UPDATE feedback_tickets SET updated_at = NOW() WHERE id = $1 RETURNING reporter_id`, ticketID).Scan(&reporterID)
	if err != nil {
		return Message{}, notFound(err)
	}
	m, err := scanMessage(tx.QueryRow(ctx, `
		INSERT INTO feedback_messages AS m (ticket_id, author, kind, body) VALUES ($1, $2, $3, $4)
		RETURNING `+messageCols, ticketID, AuthorAdmin, kind, body))
	if err != nil {
		return m, err
	}
	if err := bump(ctx, tx, reporterID); err != nil {
		return m, err
	}
	return m, tx.Commit(ctx)
}

func (p *pgRepo) UserMessage(ctx context.Context, ticketID, messageID int64) (Message, error) {
	m, err := scanMessage(p.pool.QueryRow(ctx,
		`SELECT `+messageCols+` FROM feedback_messages m WHERE m.id = $1 AND m.ticket_id = $2 AND m.author = $3`,
		messageID, ticketID, AuthorUser))
	return m, notFound(err)
}

func (p *pgRepo) SetRating(ctx context.Context, t Ticket, clientID string, resolved bool, comment string) (Message, bool, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return Message{}, false, err
	}
	defer tx.Rollback(ctx)
	m, created, err := addUser(ctx, tx, t, clientID, MsgRating, comment)
	if err != nil || !created {
		return m, created, err
	}
	if resolved {
		_, err = tx.Exec(ctx, `UPDATE feedback_tickets SET satisfaction = 1 WHERE id = $1`, t.ID)
	} else {
		_, err = tx.Exec(ctx, `
			UPDATE feedback_tickets SET satisfaction = -1,
				status = CASE WHEN status = ANY($2) THEN $3 ELSE status END,
				closed_at = CASE WHEN status = ANY($2) THEN NULL ELSE closed_at END
			WHERE id = $1`, t.ID, []string{StatusDone, StatusWontFix, StatusDuplicate}, StatusReviewed)
	}
	if err != nil {
		return m, false, err
	}
	return m, true, tx.Commit(ctx)
}

func (p *pgRepo) SetStatus(ctx context.Context, ticketID int64, status string) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var reporterID int64
	err = tx.QueryRow(ctx, `
		UPDATE feedback_tickets SET
			closed_at = CASE WHEN NOT $3 THEN NULL WHEN status = ANY($4) THEN closed_at ELSE NOW() END,
			status = $2, updated_at = NOW()
		WHERE id = $1 RETURNING reporter_id`,
		ticketID, status, IsClosed(status), []string{StatusDone, StatusWontFix, StatusDuplicate}).Scan(&reporterID)
	if err != nil {
		return notFound(err)
	}
	if err := bump(ctx, tx, reporterID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (p *pgRepo) MarkRead(ctx context.Context, t Ticket, upTo int64) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	tag, err := tx.Exec(ctx, `
		UPDATE feedback_messages SET read_at = NOW()
		WHERE ticket_id = $1 AND author = $2 AND id <= $3 AND read_at IS NULL`, t.ID, AuthorAdmin, upTo)
	if err != nil {
		return err
	}
	if tag.RowsAffected() > 0 {
		if err := bump(ctx, tx, t.ReporterID); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

func (p *pgRepo) Attachment(ctx context.Context, messageID int64, clientID string) (Attachment, error) {
	a, err := scanAttachment(p.pool.QueryRow(ctx,
		`SELECT `+attachmentCols+` FROM feedback_attachments a WHERE a.message_id = $1 AND a.client_attachment_id = $2`,
		messageID, clientID))
	return a, notFound(err)
}

func (p *pgRepo) TicketAttachmentBytes(ctx context.Context, ticketID int64) (int64, error) {
	var n int64
	err := p.pool.QueryRow(ctx, `SELECT COALESCE(SUM(bytes), 0) FROM feedback_attachments WHERE ticket_id = $1`, ticketID).Scan(&n)
	return n, err
}

func (p *pgRepo) AddAttachment(ctx context.Context, in NewAttachment, lim Limits) (Attachment, bool, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return Attachment{}, false, err
	}
	defer tx.Rollback(ctx)
	// Locking the ticket row makes two uploads to one ticket take turns, so neither can slip past
	// the limits the other is about to fill.
	var reporterID int64
	if err := tx.QueryRow(ctx, `SELECT reporter_id FROM feedback_tickets WHERE id = $1 FOR UPDATE`, in.TicketID).Scan(&reporterID); err != nil {
		return Attachment{}, false, notFound(err)
	}
	existing, err := scanAttachment(tx.QueryRow(ctx,
		`SELECT `+attachmentCols+` FROM feedback_attachments a WHERE a.message_id = $1 AND a.client_attachment_id = $2`,
		in.MessageID, in.ClientAttachmentID))
	if err == nil {
		return existing, false, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return Attachment{}, false, err
	}
	var count int
	var bytes int64
	if err := tx.QueryRow(ctx, `
		SELECT COUNT(*) FILTER (WHERE message_id = $2), COALESCE(SUM(bytes), 0)
		FROM feedback_attachments WHERE ticket_id = $1`, in.TicketID, in.MessageID).Scan(&count, &bytes); err != nil {
		return Attachment{}, false, err
	}
	if count >= lim.MaxPerMessage || bytes+in.Bytes > lim.MaxTicketBytes {
		return Attachment{}, false, ErrLimit
	}
	a, err := scanAttachment(tx.QueryRow(ctx, `
		INSERT INTO feedback_attachments AS a (ticket_id, message_id, client_attachment_id, kind, mime, sha256, bytes, width, height, has_thumb)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
		RETURNING `+attachmentCols,
		in.TicketID, in.MessageID, in.ClientAttachmentID, in.Kind, in.Mime, in.SHA256, in.Bytes, in.Width, in.Height, in.HasThumb))
	if err != nil {
		return Attachment{}, false, err
	}
	if _, err := tx.Exec(ctx, `UPDATE feedback_tickets SET updated_at = NOW(), unread_admin = TRUE WHERE id = $1`, in.TicketID); err != nil {
		return Attachment{}, false, err
	}
	if err := bump(ctx, tx, reporterID); err != nil {
		return Attachment{}, false, err
	}
	return a, true, tx.Commit(ctx)
}

func (p *pgRepo) TotalAttachmentBytes(ctx context.Context) (int64, error) {
	var n int64
	err := p.pool.QueryRow(ctx, `SELECT COALESCE(SUM(bytes), 0) FROM feedback_attachments`).Scan(&n)
	return n, err
}

func (p *pgRepo) Inbox(ctx context.Context, reporterID, since int64, limit int) (Inbox, error) {
	in := Inbox{Tickets: []InboxTicket{}, Messages: []InboxMsg{}}
	if err := p.pool.QueryRow(ctx, `SELECT inbox_version FROM feedback_reporters WHERE id = $1`, reporterID).Scan(&in.Version); err != nil {
		return in, notFound(err)
	}
	rows, err := p.pool.Query(ctx, `
		SELECT `+ticketCols+`,
			(SELECT COUNT(*) FROM feedback_messages m WHERE m.ticket_id = t.id AND m.author = $2 AND m.read_at IS NULL)
		FROM feedback_tickets t WHERE t.reporter_id = $1
		ORDER BY t.updated_at DESC, t.id DESC LIMIT $3`, reporterID, AuthorAdmin, limit)
	if err != nil {
		return in, err
	}
	for rows.Next() {
		var it InboxTicket
		t, err := scanTicket(rows, &it.Unread)
		if err != nil {
			rows.Close()
			return in, err
		}
		it.Ticket = t
		in.Tickets = append(in.Tickets, it)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return in, err
	}

	rows, err = p.pool.Query(ctx, `
		SELECT `+messageCols+`, t.public_id
		FROM feedback_messages m JOIN feedback_tickets t ON t.id = m.ticket_id
		WHERE t.reporter_id = $1 AND m.author = $2 AND m.id > $3
		ORDER BY m.id LIMIT $4`, reporterID, AuthorAdmin, since, maxInboxMessages)
	if err != nil {
		return in, err
	}
	for rows.Next() {
		var im InboxMsg
		m, err := scanMessage(rows, &im.TicketID)
		if err != nil {
			rows.Close()
			return in, err
		}
		im.Message = m
		in.Messages = append(in.Messages, im)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return in, err
	}
	ptrs := make([]*Message, len(in.Messages))
	for i := range in.Messages {
		ptrs[i] = &in.Messages[i].Message
	}
	return in, p.fillAttachments(ctx, p.pool, ptrs)
}

func (p *pgRepo) Thread(ctx context.Context, ticketID int64) ([]Message, error) {
	rows, err := p.pool.Query(ctx, `SELECT `+messageCols+` FROM feedback_messages m WHERE m.ticket_id = $1 ORDER BY m.id`, ticketID)
	if err != nil {
		return nil, err
	}
	out := []Message{}
	for rows.Next() {
		m, err := scanMessage(rows)
		if err != nil {
			rows.Close()
			return nil, err
		}
		out = append(out, m)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	ptrs := make([]*Message, len(out))
	for i := range out {
		ptrs[i] = &out[i]
	}
	return out, p.fillAttachments(ctx, p.pool, ptrs)
}

func collectSHAs(rows pgx.Rows, err error) ([]string, error) {
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var shas []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			return nil, err
		}
		shas = append(shas, s)
	}
	return shas, rows.Err()
}

func (p *pgRepo) DeleteTicket(ctx context.Context, ticketID int64) ([]string, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	shas, err := collectSHAs(tx.Query(ctx, `SELECT sha256 FROM feedback_attachments WHERE ticket_id = $1`, ticketID))
	if err != nil {
		return nil, err
	}
	var reporterID int64
	if err := tx.QueryRow(ctx, `DELETE FROM feedback_tickets WHERE id = $1 RETURNING reporter_id`, ticketID).Scan(&reporterID); err != nil {
		return nil, notFound(err)
	}
	if err := bump(ctx, tx, reporterID); err != nil {
		return nil, err
	}
	return shas, tx.Commit(ctx)
}

func (p *pgRepo) SHAInUse(ctx context.Context, sha string) (bool, error) {
	var used bool
	err := p.pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM feedback_attachments WHERE sha256 = $1)`, sha).Scan(&used)
	return used, err
}

func (p *pgRepo) PurgeClosedAttachments(ctx context.Context, cutoff time.Time) ([]string, error) {
	return collectSHAs(p.pool.Query(ctx, `
		DELETE FROM feedback_attachments a USING feedback_tickets t
		WHERE a.ticket_id = t.id AND t.closed_at IS NOT NULL AND t.closed_at < $1
		RETURNING a.sha256`, cutoff))
}
