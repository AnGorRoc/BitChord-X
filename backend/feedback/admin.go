package feedback

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// The dashboard's side. These routes are mounted on the private admin server, behind its
// authentication and CSRF check (telemetry.NewAdminHandler); nothing here is reachable from the
// public port.

var tagPattern = regexp.MustCompile(`^[\p{L}\p{N}][\p{L}\p{N} _-]{0,23}$`)

// RegisterAdmin adds the dashboard's routes to mux.
func (s *Service) RegisterAdmin(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/admin/feedback/summary", s.adminSummary)
	mux.HandleFunc("GET /api/admin/feedback/tickets", s.adminList)
	mux.HandleFunc("GET /api/admin/feedback/tickets/{tid}", s.adminDetail)
	mux.HandleFunc("PATCH /api/admin/feedback/tickets/{tid}", s.adminPatch)
	mux.HandleFunc("DELETE /api/admin/feedback/tickets/{tid}", s.adminDelete)
	mux.HandleFunc("POST /api/admin/feedback/tickets/{tid}/messages", s.adminReply)
	mux.HandleFunc("POST /api/admin/feedback/tickets/{tid}/block", s.adminBlock)
	mux.HandleFunc("POST /api/admin/feedback/bulk", s.adminBulk)
	mux.HandleFunc("GET /api/admin/feedback/attachments/{aid}", s.adminAttachment)
	mux.HandleFunc("GET /api/admin/feedback/templates", s.adminTemplates)
	mux.HandleFunc("POST /api/admin/feedback/templates", s.adminSaveTemplate)
	mux.HandleFunc("PUT /api/admin/feedback/templates/{id}", s.adminSaveTemplate)
	mux.HandleFunc("DELETE /api/admin/feedback/templates/{id}", s.adminDeleteTemplate)
	mux.HandleFunc("GET /api/admin/ai/feedback", s.adminAIDigest)
}

// SeedTemplates gives an empty template list the default replies, once.
func (s *Service) SeedTemplates(ctx context.Context) error {
	list, err := s.repo.Templates(ctx)
	if err != nil || len(list) > 0 {
		return err
	}
	for _, t := range defaultTemplates {
		if _, err := s.repo.SaveTemplate(ctx, t); err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) adminTicket(w http.ResponseWriter, r *http.Request) (Ticket, bool) {
	pid := r.PathValue("tid")
	if !publicIDPattern.MatchString(pid) {
		writeErr(w, http.StatusNotFound, "not_found", "No such ticket.")
		return Ticket{}, false
	}
	t, err := s.repo.AdminTicket(r.Context(), pid)
	if errors.Is(err, ErrNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "No such ticket.")
		return t, false
	}
	if err != nil {
		s.serverErr(w, "admin ticket", err)
		return t, false
	}
	return t, true
}

func (s *Service) changed(id string) {
	s.opt.OnEvent("feedback", map[string]any{"event": "updated", "id": id})
}

func (s *Service) adminSummary(w http.ResponseWriter, r *http.Request) {
	sum, err := s.repo.AdminSummary(r.Context())
	if err != nil {
		s.serverErr(w, "summary", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"summary": sum, "statuses": Statuses, "variables": TemplateVars,
		"limits": map[string]int64{"maxDiskBytes": s.opt.MaxDiskBytes}})
}

func (s *Service) adminList(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit <= 0 || limit > 100 {
		limit = 40
	}
	page, err := s.repo.AdminList(r.Context(), AdminFilter{
		Status: q.Get("status"), Kind: q.Get("kind"), Platform: q.Get("platform"), Version: q.Get("version"),
		Tag: q.Get("tag"), Query: strings.TrimSpace(q.Get("q")), Cursor: q.Get("cursor"), Limit: limit,
	})
	if err != nil {
		s.serverErr(w, "list", err)
		return
	}
	writeJSON(w, http.StatusOK, page)
}

func (s *Service) adminDetail(w http.ResponseWriter, r *http.Request) {
	d, err := s.repo.AdminDetail(r.Context(), r.PathValue("tid"))
	if errors.Is(err, ErrNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "No such ticket.")
		return
	}
	if err != nil {
		s.serverErr(w, "detail", err)
		return
	}
	if r.URL.Query().Get("format") == "md" {
		writeText(w, "text/markdown; charset=utf-8", ticketMarkdown(d))
		return
	}
	writeJSON(w, http.StatusOK, d)
}

// cleanPatch validates a patch in place. It answers itself when it fails.
func cleanPatch(w http.ResponseWriter, p *AdminPatch) bool {
	if p.Status != nil && !contains(Statuses, *p.Status) {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_status", "Unknown status.")
		return false
	}
	if p.Priority != nil && (*p.Priority < 0 || *p.Priority > maxPriority) {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_priority", "priority must be 0 to 4.")
		return false
	}
	if p.Tags != nil {
		seen := map[string]bool{}
		clean := []string{}
		for _, t := range *p.Tags {
			t = strings.ToLower(cleanLine(t))
			if t == "" || seen[t] {
				continue
			}
			if !tagPattern.MatchString(t) || len(clean) == 8 {
				writeErr(w, http.StatusUnprocessableEntity, "invalid_tags", "Up to 8 tags of letters, digits, spaces and dashes, 24 characters each.")
				return false
			}
			seen[t] = true
			clean = append(clean, t)
		}
		p.Tags = &clean
	}
	if p.DuplicateOf != nil && *p.DuplicateOf != "" && !publicIDPattern.MatchString(*p.DuplicateOf) {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_duplicate", "duplicateOf must be a ticket ID.")
		return false
	}
	return true
}

func (s *Service) adminPatch(w http.ResponseWriter, r *http.Request) {
	t, ok := s.adminTicket(w, r)
	if !ok {
		return
	}
	var p AdminPatch
	if !decode(w, r, &p) || !cleanPatch(w, &p) {
		return
	}
	if err := s.repo.AdminUpdate(r.Context(), t.ID, p); errors.Is(err, ErrNotFound) {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_duplicate", "The ticket it duplicates does not exist.")
		return
	} else if err != nil {
		s.serverErr(w, "patch", err)
		return
	}
	s.changed(t.PublicID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Service) adminDelete(w http.ResponseWriter, r *http.Request) {
	t, ok := s.adminTicket(w, r)
	if !ok {
		return
	}
	shas, err := s.repo.DeleteTicket(r.Context(), t.ID)
	if err != nil && !errors.Is(err, ErrNotFound) {
		s.serverErr(w, "admin delete", err)
		return
	}
	s.dropUnused(r.Context(), shas)
	s.changed(t.PublicID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Service) adminReply(w http.ResponseWriter, r *http.Request) {
	t, ok := s.adminTicket(w, r)
	if !ok {
		return
	}
	var body struct {
		Kind   string  `json:"kind"`
		Body   string  `json:"body"`
		Status *string `json:"status"`
	}
	if !decode(w, r, &body) {
		return
	}
	text := cleanText(body.Body)
	if body.Kind == "" {
		body.Kind = MsgText
	}
	if body.Kind != MsgText && body.Kind != MsgThanks {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_kind", "kind must be message or thanks.")
		return
	}
	if text == "" || runeLen(text) > maxBody {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_body", "body must be 1 to 8000 characters.")
		return
	}
	// A variable the app cannot fill would reach the person as "{{nobre}}"; refuse it here.
	if problems := LintTemplate(text); len(problems) > 0 {
		writeJSON(w, http.StatusUnprocessableEntity, map[string]any{"error": "invalid_template", "message": "The reply has variables the app cannot fill.", "problems": problems})
		return
	}
	unread := false
	patch := AdminPatch{Status: body.Status, UnreadAdmin: &unread}
	if !cleanPatch(w, &patch) {
		return
	}
	m, err := s.repo.AddAdminMessage(r.Context(), t.ID, body.Kind, text)
	if err != nil {
		s.serverErr(w, "reply", err)
		return
	}
	if err := s.repo.AdminUpdate(r.Context(), t.ID, patch); err != nil {
		s.serverErr(w, "reply status", err)
		return
	}
	s.changed(t.PublicID)
	writeJSON(w, http.StatusCreated, map[string]any{"message": m})
}

func (s *Service) adminBlock(w http.ResponseWriter, r *http.Request) {
	t, ok := s.adminTicket(w, r)
	if !ok {
		return
	}
	var body struct {
		Blocked bool `json:"blocked"`
	}
	if !decode(w, r, &body) {
		return
	}
	if err := s.repo.SetReporterBlocked(r.Context(), t.ID, body.Blocked); err != nil {
		s.serverErr(w, "block", err)
		return
	}
	s.changed(t.PublicID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Service) adminBulk(w http.ResponseWriter, r *http.Request) {
	var body struct {
		IDs         []string `json:"ids"`
		Status      *string  `json:"status"`
		UnreadAdmin *bool    `json:"unreadAdmin"`
	}
	if !decode(w, r, &body) {
		return
	}
	if len(body.IDs) == 0 || len(body.IDs) > 100 {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_ids", "ids must list 1 to 100 tickets.")
		return
	}
	patch := AdminPatch{Status: body.Status, UnreadAdmin: body.UnreadAdmin}
	if !cleanPatch(w, &patch) {
		return
	}
	n := 0
	for _, pid := range body.IDs {
		if !publicIDPattern.MatchString(pid) {
			continue
		}
		t, err := s.repo.AdminTicket(r.Context(), pid)
		if err != nil {
			continue
		}
		if err := s.repo.AdminUpdate(r.Context(), t.ID, patch); err != nil {
			s.serverErr(w, "bulk", err)
			return
		}
		n++
	}
	s.changed("")
	writeJSON(w, http.StatusOK, map[string]int{"updated": n})
}

func extFor(mime string) string {
	switch {
	case mime == "image/png":
		return "png"
	case mime == "image/jpeg":
		return "jpg"
	case strings.HasPrefix(mime, "text/plain"):
		return "txt"
	}
	return ""
}

// adminAttachment serves a stored file to the dashboard. The bytes were re-encoded or checked on
// the way in; the headers make sure a browser treats them as nothing but an image or text even
// so: the type is the one the server decided, never sniffed, and the page it would make has no
// scripts, no requests and no origin.
func (s *Service) adminAttachment(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("aid"), 10, 64)
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "No such attachment.")
		return
	}
	a, err := s.repo.AttachmentByID(r.Context(), id)
	if errors.Is(err, ErrNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "No such attachment.")
		return
	}
	if err != nil {
		s.serverErr(w, "attachment", err)
		return
	}
	ext := extFor(a.Mime)
	path, err := s.files.path(a.SHA256, "."+ext)
	mime := a.Mime
	if err == nil && r.URL.Query().Get("thumb") == "1" && a.HasThumb {
		path, _ = s.files.path(a.SHA256, "_t.jpg")
		mime, ext = "image/jpeg", "jpg"
	}
	f, err := os.Open(path)
	if err != nil {
		writeErr(w, http.StatusNotFound, "file_missing", "The file is gone (purged or deleted).")
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		s.serverErr(w, "attachment stat", err)
		return
	}
	h := w.Header()
	h.Set("Content-Type", mime)
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox")
	h.Set("Cross-Origin-Resource-Policy", "same-origin")
	// The name is the content's hash, so the bytes behind this URL never change.
	h.Set("Cache-Control", "private, max-age=31536000, immutable")
	h.Set("Content-Disposition", fmt.Sprintf(`inline; filename="feedback-%d.%s"`, a.ID, ext))
	http.ServeContent(w, r, "", st.ModTime(), f)
}

func (s *Service) adminTemplates(w http.ResponseWriter, r *http.Request) {
	list, err := s.repo.Templates(r.Context())
	if err != nil {
		s.serverErr(w, "templates", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"templates": list, "variables": TemplateVars})
}

func (s *Service) adminSaveTemplate(w http.ResponseWriter, r *http.Request) {
	var t Template
	if !decode(w, r, &t) {
		return
	}
	t.ID = 0
	if raw := r.PathValue("id"); raw != "" {
		id, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || id <= 0 {
			writeErr(w, http.StatusNotFound, "not_found", "No such template.")
			return
		}
		t.ID = id
	}
	t.Name, t.Body = cleanLine(t.Name), cleanText(t.Body)
	if t.Kind == "" {
		t.Kind = MsgText
	}
	switch {
	case t.Name == "" || runeLen(t.Name) > 60:
		writeErr(w, http.StatusUnprocessableEntity, "invalid_name", "name must be 1 to 60 characters.")
		return
	case t.Kind != MsgText && t.Kind != MsgThanks:
		writeErr(w, http.StatusUnprocessableEntity, "invalid_kind", "kind must be message or thanks.")
		return
	case t.Body == "" || runeLen(t.Body) > maxBody:
		writeErr(w, http.StatusUnprocessableEntity, "invalid_body", "body must be 1 to 8000 characters.")
		return
	}
	if problems := LintTemplate(t.Body); len(problems) > 0 {
		writeJSON(w, http.StatusUnprocessableEntity, map[string]any{"error": "invalid_template", "message": "The template has variables the app cannot fill.", "problems": problems})
		return
	}
	saved, err := s.repo.SaveTemplate(r.Context(), t)
	if errors.Is(err, ErrNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "No such template.")
		return
	}
	if err != nil {
		s.serverErr(w, "save template", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"template": saved})
}

func (s *Service) adminDeleteTemplate(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err == nil {
		err = s.repo.DeleteTemplate(r.Context(), id)
	}
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "No such template.")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// adminAIDigest is the feedback for an AI agent or a script: open tickets in Markdown, or JSON
// with format=json.
func (s *Service) adminAIDigest(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	status := q.Get("status")
	if status == "" {
		status = "open"
	}
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit <= 0 || limit > 50 {
		limit = 20
	}
	page, err := s.repo.AdminList(r.Context(), AdminFilter{Status: status, Kind: q.Get("kind"), Query: q.Get("q"), Limit: limit})
	if err != nil {
		s.serverErr(w, "ai digest", err)
		return
	}
	if q.Get("format") == "json" {
		writeJSON(w, http.StatusOK, page)
		return
	}
	var b strings.Builder
	fmt.Fprintf(&b, "# BitChord feedback (%s, %d)\n\n", status, len(page.Items))
	for _, t := range page.Items {
		fmt.Fprintf(&b, "## %s · %s · %s\n", t.PublicID, t.Kind, t.Title)
		fmt.Fprintf(&b, "- status: %s · priority: %d · %s %s · %d messages · %d attachments · updated %s\n",
			t.Status, t.Priority, t.Platform, t.AppVersion, t.Messages, t.Attachments, time.UnixMilli(t.UpdatedAt).UTC().Format(time.RFC3339))
		if len(t.Tags) > 0 {
			fmt.Fprintf(&b, "- tags: %s\n", strings.Join(t.Tags, ", "))
		}
		fmt.Fprintf(&b, "- last (%s): %s\n\n", t.LastAuthor, t.Snippet)
	}
	b.WriteString("Full thread: GET /api/admin/feedback/tickets/{id}?format=md\n")
	writeText(w, "text/markdown; charset=utf-8", b.String())
}

func ticketMarkdown(d AdminDetail) string {
	var b strings.Builder
	fmt.Fprintf(&b, "# %s — %s\n\n", d.PublicID, d.Title)
	fmt.Fprintf(&b, "- kind: %s · status: %s · priority: %d\n", d.Kind, d.Status, d.Priority)
	fmt.Fprintf(&b, "- app: %s %s · locale: %s\n", d.Platform, d.AppVersion, d.Locale)
	dg := d.Diagnostics
	if dg.DeviceModel != "" || dg.OSVersion != "" {
		fmt.Fprintf(&b, "- device: %s · OS %s · %s · screen %s\n", dg.DeviceModel, dg.OSVersion, dg.ABI, dg.Screen)
	}
	if len(d.Tags) > 0 {
		fmt.Fprintf(&b, "- tags: %s\n", strings.Join(d.Tags, ", "))
	}
	b.WriteString("\n## Thread\n\n")
	for _, m := range d.Thread {
		when := time.UnixMilli(m.CreatedAt).UTC().Format("2006-01-02 15:04")
		fmt.Fprintf(&b, "**%s** (%s, %s)\n\n", m.Author, m.Kind, when)
		b.WriteString(m.Body)
		b.WriteString("\n\n")
		for _, a := range m.Attachments {
			fmt.Fprintf(&b, "- attachment %d: %s %dx%d, %d bytes\n", a.ID, a.Mime, a.Width, a.Height, a.Bytes)
		}
	}
	if len(d.Errors) > 0 {
		b.WriteString("## Error reports from the same install\n\n")
		for _, e := range d.Errors {
			fmt.Fprintf(&b, "- #%d %s (%s): %s\n", e.ID, e.ErrorType, e.AppVersion, e.Message)
		}
	}
	return b.String()
}

func writeText(w http.ResponseWriter, contentType, body string) {
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(body))
}
