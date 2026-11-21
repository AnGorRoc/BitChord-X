package feedback

import (
	"context"
	"sort"
	"sync"
	"time"
)

// memRepo keeps tickets in memory. It is what a server without DATABASE_URL uses (a local test
// server), and the reference the PostgreSQL repository is checked against.
type memRepo struct {
	mu          sync.Mutex
	now         func() time.Time
	seq         int64
	reporters   map[int64]*memReporter
	byHash      map[string]int64
	tickets     map[int64]*memTicket
	messages    map[int64]*Message
	attachments map[int64]*Attachment
	templates   map[int64]*Template
}

type memReporter struct {
	Reporter
	hash    string
	created int64
}

type memTicket struct {
	Ticket
	clientID    string
	unreadAdm   bool
	msgClients  map[string]int64
	priority    int
	tags        []string
	locale      string
	diag        Diagnostics
	duplicateOf int64
}

// NewMemRepo returns an empty in-memory repository.
func NewMemRepo() Repo { return newMemRepo(time.Now) }

func newMemRepo(now func() time.Time) *memRepo {
	return &memRepo{
		now:         now,
		reporters:   map[int64]*memReporter{},
		byHash:      map[string]int64{},
		tickets:     map[int64]*memTicket{},
		messages:    map[int64]*Message{},
		attachments: map[int64]*Attachment{},
		templates:   map[int64]*Template{},
	}
}

func (m *memRepo) next() int64 { m.seq++; return m.seq }

func (m *memRepo) Migrate(context.Context) error { return nil }

func (m *memRepo) Reporter(_ context.Context, hash string) (Reporter, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	id, ok := m.byHash[hash]
	if !ok {
		return Reporter{}, ErrNotFound
	}
	return m.reporters[id].Reporter, nil
}

func (m *memRepo) CreateReporter(_ context.Context, hash, _, _ string) (Reporter, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if id, ok := m.byHash[hash]; ok {
		return m.reporters[id].Reporter, nil
	}
	r := &memReporter{Reporter: Reporter{ID: m.next(), InboxVersion: 1}, hash: hash, created: m.now().UnixMilli()}
	m.reporters[r.ID] = r
	m.byHash[hash] = r.ID
	return r.Reporter, nil
}

func (m *memRepo) TouchReporter(context.Context, int64) error { return nil }

// SetBlocked is for tests (the dashboard does it in PostgreSQL).
func (m *memRepo) SetBlocked(id int64, blocked bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if r := m.reporters[id]; r != nil {
		r.Blocked = blocked
	}
}

func (m *memRepo) bump(reporterID int64) {
	if r := m.reporters[reporterID]; r != nil {
		r.InboxVersion++
	}
}

func (m *memRepo) CreateTicket(_ context.Context, in NewTicket, publicID string) (Ticket, Message, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, t := range m.tickets {
		if t.ReporterID == in.ReporterID && t.clientID == in.ClientTicketID {
			first := m.firstMessage(t.ID)
			return t.Ticket, first, false, nil
		}
	}
	now := m.now().UnixMilli()
	t := &memTicket{
		Ticket: Ticket{
			ID: m.next(), PublicID: publicID, ReporterID: in.ReporterID, Kind: in.Kind, Title: in.Title,
			Status: StatusNew, Platform: in.Platform, AppVersion: in.AppVersion, CreatedAt: now, UpdatedAt: now,
		},
		clientID: in.ClientTicketID, unreadAdm: true, msgClients: map[string]int64{},
		tags: []string{}, locale: in.Locale, diag: in.Diagnostics,
	}
	m.tickets[t.ID] = t
	msg := &Message{ID: m.next(), TicketID: t.ID, Author: AuthorUser, Kind: MsgText, Body: in.Body, CreatedAt: now}
	m.messages[msg.ID] = msg
	m.bump(in.ReporterID)
	return t.Ticket, m.withAttachments(*msg), true, nil
}

func (m *memRepo) firstMessage(ticketID int64) Message {
	var first *Message
	for _, msg := range m.messages {
		if msg.TicketID == ticketID && (first == nil || msg.ID < first.ID) {
			first = msg
		}
	}
	if first == nil {
		return Message{}
	}
	return m.withAttachments(*first)
}

func (m *memRepo) withAttachments(msg Message) Message {
	msg.Attachments = []Attachment{}
	for _, a := range m.attachments {
		if a.MessageID == msg.ID {
			msg.Attachments = append(msg.Attachments, *a)
		}
	}
	sort.Slice(msg.Attachments, func(i, j int) bool { return msg.Attachments[i].ID < msg.Attachments[j].ID })
	return msg
}

func (m *memRepo) Ticket(_ context.Context, reporterID int64, publicID string) (Ticket, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, t := range m.tickets {
		if t.PublicID == publicID && t.ReporterID == reporterID {
			return t.Ticket, nil
		}
	}
	return Ticket{}, ErrNotFound
}

func (m *memRepo) touch(t *memTicket, fromUser bool) {
	t.UpdatedAt = m.now().UnixMilli()
	if fromUser {
		t.unreadAdm = true
	}
	m.bump(t.ReporterID)
}

func (m *memRepo) addUser(t Ticket, clientID, kind, body string) (*memTicket, Message, bool, error) {
	mt := m.tickets[t.ID]
	if mt == nil {
		return nil, Message{}, false, ErrNotFound
	}
	if id, ok := mt.msgClients[clientID]; ok {
		return mt, m.withAttachments(*m.messages[id]), false, nil
	}
	msg := &Message{ID: m.next(), TicketID: t.ID, Author: AuthorUser, Kind: kind, Body: body, CreatedAt: m.now().UnixMilli()}
	m.messages[msg.ID] = msg
	mt.msgClients[clientID] = msg.ID
	m.touch(mt, true)
	return mt, m.withAttachments(*msg), true, nil
}

func (m *memRepo) AddUserMessage(_ context.Context, t Ticket, clientID, body string) (Message, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, msg, created, err := m.addUser(t, clientID, MsgText, body)
	return msg, created, err
}

func (m *memRepo) AddAdminMessage(_ context.Context, ticketID int64, kind, body string) (Message, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	mt := m.tickets[ticketID]
	if mt == nil {
		return Message{}, ErrNotFound
	}
	msg := &Message{ID: m.next(), TicketID: ticketID, Author: AuthorAdmin, Kind: kind, Body: body, CreatedAt: m.now().UnixMilli()}
	m.messages[msg.ID] = msg
	m.touch(mt, false)
	return m.withAttachments(*msg), nil
}

func (m *memRepo) UserMessage(_ context.Context, ticketID, messageID int64) (Message, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	msg := m.messages[messageID]
	if msg == nil || msg.TicketID != ticketID || msg.Author != AuthorUser {
		return Message{}, ErrNotFound
	}
	return m.withAttachments(*msg), nil
}

func (m *memRepo) SetRating(_ context.Context, t Ticket, clientID string, resolved bool, comment string) (Message, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	mt, msg, created, err := m.addUser(t, clientID, ratingKind(resolved), comment)
	if err != nil || !created {
		return msg, created, err
	}
	if resolved {
		mt.Satisfaction = 1
	} else {
		mt.Satisfaction = -1
		if IsClosed(mt.Status) {
			mt.Status = StatusReviewed
			mt.ClosedAt = 0
		}
	}
	return msg, true, nil
}

func (m *memRepo) SetStatus(_ context.Context, ticketID int64, status string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	mt := m.tickets[ticketID]
	if mt == nil {
		return ErrNotFound
	}
	if IsClosed(status) && !IsClosed(mt.Status) {
		mt.ClosedAt = m.now().UnixMilli()
	} else if !IsClosed(status) {
		mt.ClosedAt = 0
	}
	mt.Status = status
	m.touch(mt, false)
	return nil
}

func (m *memRepo) MarkRead(_ context.Context, t Ticket, upTo int64) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now().UnixMilli()
	changed := false
	for _, msg := range m.messages {
		if msg.TicketID == t.ID && msg.Author == AuthorAdmin && msg.ID <= upTo && msg.ReadAt == 0 {
			msg.ReadAt = now
			changed = true
		}
	}
	if changed {
		m.bump(t.ReporterID)
	}
	return nil
}

func (m *memRepo) Attachment(_ context.Context, messageID int64, clientID string) (Attachment, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, a := range m.attachments {
		if a.MessageID == messageID && a.ClientAttachmentID == clientID {
			return *a, nil
		}
	}
	return Attachment{}, ErrNotFound
}

func (m *memRepo) TicketAttachmentBytes(_ context.Context, ticketID int64) (int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var n int64
	for _, a := range m.attachments {
		if a.TicketID == ticketID {
			n += a.Bytes
		}
	}
	return n, nil
}

func (m *memRepo) AddAttachment(_ context.Context, in NewAttachment, lim Limits) (Attachment, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	mt := m.tickets[in.TicketID]
	if mt == nil {
		return Attachment{}, false, ErrNotFound
	}
	count, bytes := 0, int64(0)
	for _, a := range m.attachments {
		if a.MessageID == in.MessageID && a.ClientAttachmentID == in.ClientAttachmentID {
			return *a, false, nil
		}
		if a.MessageID == in.MessageID {
			count++
		}
		if a.TicketID == in.TicketID {
			bytes += a.Bytes
		}
	}
	if count >= lim.MaxPerMessage || bytes+in.Bytes > lim.MaxTicketBytes {
		return Attachment{}, false, ErrLimit
	}
	a := &Attachment{
		ID: m.next(), TicketID: in.TicketID, MessageID: in.MessageID, ClientAttachmentID: in.ClientAttachmentID,
		Kind: in.Kind, Mime: in.Mime, SHA256: in.SHA256, Bytes: in.Bytes, Width: in.Width, Height: in.Height, HasThumb: in.HasThumb,
	}
	m.attachments[a.ID] = a
	m.touch(mt, true)
	return *a, true, nil
}

func (m *memRepo) TotalAttachmentBytes(context.Context) (int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var n int64
	for _, a := range m.attachments {
		n += a.Bytes
	}
	return n, nil
}

func (m *memRepo) Inbox(_ context.Context, reporterID, since int64, limit int) (Inbox, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r := m.reporters[reporterID]
	if r == nil {
		return Inbox{}, ErrNotFound
	}
	in := Inbox{Version: r.InboxVersion, Tickets: []InboxTicket{}, Messages: []InboxMsg{}}
	for _, t := range m.tickets {
		if t.ReporterID != reporterID {
			continue
		}
		it := InboxTicket{Ticket: t.Ticket}
		for _, msg := range m.messages {
			if msg.TicketID == t.ID && msg.Author == AuthorAdmin {
				if msg.ReadAt == 0 {
					it.Unread++
				}
				if msg.ID > since {
					in.Messages = append(in.Messages, InboxMsg{TicketID: t.PublicID, Message: m.withAttachments(*msg)})
				}
			}
		}
		in.Tickets = append(in.Tickets, it)
	}
	sort.Slice(in.Tickets, func(i, j int) bool {
		if in.Tickets[i].UpdatedAt != in.Tickets[j].UpdatedAt {
			return in.Tickets[i].UpdatedAt > in.Tickets[j].UpdatedAt
		}
		return in.Tickets[i].ID > in.Tickets[j].ID
	})
	if len(in.Tickets) > limit {
		in.Tickets = in.Tickets[:limit]
	}
	sort.Slice(in.Messages, func(i, j int) bool { return in.Messages[i].ID < in.Messages[j].ID })
	if len(in.Messages) > maxInboxMessages {
		in.Messages = in.Messages[:maxInboxMessages]
	}
	return in, nil
}

func (m *memRepo) Thread(_ context.Context, ticketID int64) ([]Message, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []Message{}
	for _, msg := range m.messages {
		if msg.TicketID == ticketID {
			out = append(out, m.withAttachments(*msg))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}

func (m *memRepo) DeleteTicket(_ context.Context, ticketID int64) ([]string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	mt := m.tickets[ticketID]
	if mt == nil {
		return nil, ErrNotFound
	}
	var shas []string
	for id, a := range m.attachments {
		if a.TicketID == ticketID {
			shas = append(shas, a.SHA256)
			delete(m.attachments, id)
		}
	}
	for id, msg := range m.messages {
		if msg.TicketID == ticketID {
			delete(m.messages, id)
		}
	}
	delete(m.tickets, ticketID)
	m.bump(mt.ReporterID)
	return shas, nil
}

func (m *memRepo) SHAInUse(_ context.Context, sha string) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, a := range m.attachments {
		if a.SHA256 == sha {
			return true, nil
		}
	}
	return false, nil
}

func (m *memRepo) PurgeClosedAttachments(_ context.Context, cutoff time.Time) ([]string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var shas []string
	for id, a := range m.attachments {
		t := m.tickets[a.TicketID]
		if t != nil && IsClosed(t.Status) && t.ClosedAt != 0 && t.ClosedAt < cutoff.UnixMilli() {
			shas = append(shas, a.SHA256)
			delete(m.attachments, id)
		}
	}
	return shas, nil
}
