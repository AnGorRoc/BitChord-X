package feedback

import (
	"context"
	"fmt"
	"sort"
	"strconv"
	"strings"
)

func (m *memRepo) adminTicket(t *memTicket) AdminTicket {
	at := AdminTicket{Ticket: t.Ticket, Priority: t.priority, Tags: append([]string{}, t.tags...), UnreadAdmin: t.unreadAdm}
	var last *Message
	for _, msg := range m.messages {
		if msg.TicketID != t.ID {
			continue
		}
		at.Messages++
		if last == nil || msg.ID > last.ID {
			last = msg
		}
	}
	for _, a := range m.attachments {
		if a.TicketID == t.ID {
			at.Attachments++
		}
	}
	if last != nil {
		at.Snippet, at.LastAuthor = snippet(last.Body), last.Author
	}
	return at
}

func (m *memRepo) matches(t *memTicket, f AdminFilter) bool {
	if !statusMatches(f.Status, t.Status, t.unreadAdm) ||
		(f.Kind != "" && t.Kind != f.Kind) ||
		(f.Platform != "" && t.Platform != f.Platform) ||
		(f.Version != "" && t.AppVersion != f.Version) {
		return false
	}
	if f.Tag != "" && !contains(t.tags, f.Tag) {
		return false
	}
	if q := strings.ToLower(f.Query); q != "" {
		if strings.Contains(strings.ToLower(t.Title), q) || strings.Contains(t.PublicID, q) {
			return true
		}
		for _, msg := range m.messages {
			if msg.TicketID == t.ID && strings.Contains(strings.ToLower(msg.Body), q) {
				return true
			}
		}
		return false
	}
	return true
}

func statusMatches(filter, status string, unread bool) bool {
	switch filter {
	case "":
		return true
	case "open":
		return !IsClosed(status)
	case "closed":
		return IsClosed(status)
	case "unread":
		return unread
	default:
		return status == filter
	}
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

func (m *memRepo) AdminList(_ context.Context, f AdminFilter) (AdminPage, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var list []*memTicket
	for _, t := range m.tickets {
		if m.matches(t, f) {
			list = append(list, t)
		}
	}
	sort.Slice(list, func(i, j int) bool {
		if list[i].UpdatedAt != list[j].UpdatedAt {
			return list[i].UpdatedAt > list[j].UpdatedAt
		}
		return list[i].ID > list[j].ID
	})
	if f.Cursor != "" {
		at, id, ok := parseCursor(f.Cursor)
		if !ok {
			return AdminPage{Items: []AdminTicket{}}, nil
		}
		i := 0
		for i < len(list) && (list[i].UpdatedAt > at || (list[i].UpdatedAt == at && list[i].ID >= id)) {
			i++
		}
		list = list[i:]
	}
	page := AdminPage{Items: []AdminTicket{}}
	for i, t := range list {
		if i == f.Limit {
			last := list[i-1]
			page.Next = makeCursor(last.UpdatedAt, last.ID)
			break
		}
		page.Items = append(page.Items, m.adminTicket(t))
	}
	return page, nil
}

func makeCursor(at, id int64) string { return fmt.Sprintf("%d_%d", at, id) }

func parseCursor(c string) (int64, int64, bool) {
	a, b, ok := strings.Cut(c, "_")
	if !ok {
		return 0, 0, false
	}
	at, err1 := strconv.ParseInt(a, 10, 64)
	id, err2 := strconv.ParseInt(b, 10, 64)
	return at, id, err1 == nil && err2 == nil
}

func (m *memRepo) AdminSummary(context.Context) (AdminSummary, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	s := AdminSummary{ByStatus: map[string]int{}, ByKind: map[string]int{}, Versions: []string{}, Tags: []string{}}
	versions, tags := map[string]bool{}, map[string]bool{}
	for _, t := range m.tickets {
		s.Total++
		s.ByStatus[t.Status]++
		s.ByKind[t.Kind]++
		if !IsClosed(t.Status) {
			s.Open++
		}
		if t.unreadAdm {
			s.Unread++
		}
		switch t.Satisfaction {
		case 1:
			s.ThumbsUp++
		case -1:
			s.ThumbsDn++
		}
		if t.AppVersion != "" {
			versions[t.AppVersion] = true
		}
		for _, tg := range t.tags {
			tags[tg] = true
		}
	}
	for v := range versions {
		s.Versions = append(s.Versions, v)
	}
	for tg := range tags {
		s.Tags = append(s.Tags, tg)
	}
	sort.Sort(sort.Reverse(sort.StringSlice(s.Versions)))
	sort.Strings(s.Tags)
	for _, a := range m.attachments {
		s.DiskBytes += a.Bytes
	}
	return s, nil
}

func (m *memRepo) findPublic(publicID string) *memTicket {
	for _, t := range m.tickets {
		if t.PublicID == publicID {
			return t
		}
	}
	return nil
}

func (m *memRepo) AdminTicket(_ context.Context, publicID string) (Ticket, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if t := m.findPublic(publicID); t != nil {
		return t.Ticket, nil
	}
	return Ticket{}, ErrNotFound
}

func (m *memRepo) AdminDetail(_ context.Context, publicID string) (AdminDetail, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	t := m.findPublic(publicID)
	if t == nil {
		return AdminDetail{}, ErrNotFound
	}
	d := AdminDetail{AdminTicket: m.adminTicket(t), Locale: t.locale, Diagnostics: t.diag, Thread: []Message{}, Errors: []LinkedError{}}
	if dup := m.tickets[t.duplicateOf]; dup != nil {
		d.DuplicateOf = dup.PublicID
	}
	if r := m.reporters[t.ReporterID]; r != nil {
		d.Reporter = AdminReporter{Blocked: r.Blocked, Since: r.created}
		for _, o := range m.tickets {
			if o.ReporterID == r.ID {
				d.Reporter.Tickets++
			}
		}
	}
	for _, msg := range m.messages {
		if msg.TicketID == t.ID {
			d.Thread = append(d.Thread, m.withAttachments(*msg))
		}
	}
	sort.Slice(d.Thread, func(i, j int) bool { return d.Thread[i].ID < d.Thread[j].ID })
	return d, nil
}

func (m *memRepo) AdminUpdate(_ context.Context, ticketID int64, p AdminPatch) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	t := m.tickets[ticketID]
	if t == nil {
		return ErrNotFound
	}
	if p.DuplicateOf != nil {
		if *p.DuplicateOf == "" {
			t.duplicateOf = 0
		} else if dup := m.findPublic(*p.DuplicateOf); dup != nil && dup.ID != t.ID {
			t.duplicateOf = dup.ID
		} else {
			return ErrNotFound
		}
	}
	if p.Priority != nil {
		t.priority = *p.Priority
	}
	if p.Tags != nil {
		t.tags = append([]string{}, (*p.Tags)...)
	}
	if p.UnreadAdmin != nil {
		t.unreadAdm = *p.UnreadAdmin
	}
	if p.Status != nil && *p.Status != t.Status {
		now := m.now().UnixMilli()
		if IsClosed(*p.Status) && !IsClosed(t.Status) {
			t.ClosedAt = now
		} else if !IsClosed(*p.Status) {
			t.ClosedAt = 0
		}
		t.Status = *p.Status
		msg := &Message{ID: m.next(), TicketID: t.ID, Author: AuthorAdmin, Kind: MsgStatus, Body: *p.Status, CreatedAt: now}
		m.messages[msg.ID] = msg
		t.UpdatedAt = now
		m.bump(t.ReporterID)
	}
	return nil
}

func (m *memRepo) SetReporterBlocked(_ context.Context, ticketID int64, blocked bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	t := m.tickets[ticketID]
	if t == nil {
		return ErrNotFound
	}
	m.reporters[t.ReporterID].Blocked = blocked
	return nil
}

func (m *memRepo) AttachmentByID(_ context.Context, id int64) (Attachment, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if a := m.attachments[id]; a != nil {
		return *a, nil
	}
	return Attachment{}, ErrNotFound
}

func (m *memRepo) Templates(context.Context) ([]Template, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []Template{}
	for _, t := range m.templates {
		out = append(out, *t)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}

func (m *memRepo) SaveTemplate(_ context.Context, t Template) (Template, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if t.ID == 0 {
		t.ID = m.next()
	} else if m.templates[t.ID] == nil {
		return Template{}, ErrNotFound
	}
	t.UpdatedAt = m.now().UnixMilli()
	m.templates[t.ID] = &t
	return t, nil
}

func (m *memRepo) DeleteTemplate(_ context.Context, id int64) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.templates[id] == nil {
		return ErrNotFound
	}
	delete(m.templates, id)
	return nil
}

// snippet is the start of a message for the list: one line, at most 160 characters.
func snippet(body string) string {
	s := strings.Join(strings.Fields(body), " ")
	if r := []rune(s); len(r) > 160 {
		return string(r[:159]) + "…"
	}
	return s
}
