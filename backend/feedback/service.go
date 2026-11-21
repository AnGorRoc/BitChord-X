package feedback

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/KabirSinghBhatia/BitChord/backend/config"
	"github.com/KabirSinghBhatia/BitChord/backend/presence"
)

// Limiter is a per-key rate limit (the server's per-address limiter).
type Limiter interface{ Allow(key string) bool }

// Options configures the service. Zero values take the defaults in New.
type Options struct {
	Dir            string
	MaxDiskBytes   int64
	MaxUploadBytes int64
	Limits         Limits
	RetentionDays  int

	ClientIP func(*http.Request) string
	// Per-address limits on creating tickets, writing messages, uploading and reading.
	CreateLimiter, MessageLimiter, UploadLimiter, ReadLimiter Limiter
	// OnEvent tells the dashboard's live view about new tickets and messages.
	OnEvent func(kind string, payload any)
}

// Service answers the apps' feedback requests.
type Service struct {
	repo  Repo
	files *fileStore
	opt   Options
	// processing bounds how many uploads decode images at once (CPU and memory); receiving
	// bounds how many upload bodies are held in memory at once.
	processing chan struct{}
	receiving  chan struct{}
	now        func() time.Time
}

type allowAll struct{}

func (allowAll) Allow(string) bool { return true }

// New makes the service and its attachment folder.
func New(repo Repo, opt Options) (*Service, error) {
	if opt.Dir == "" {
		opt.Dir = "data/feedback"
	}
	if opt.MaxDiskBytes <= 0 {
		opt.MaxDiskBytes = 5 << 30
	}
	if opt.MaxUploadBytes <= 0 {
		opt.MaxUploadBytes = 8 << 20
	}
	if opt.Limits.MaxPerMessage <= 0 {
		opt.Limits.MaxPerMessage = 6
	}
	if opt.Limits.MaxTicketBytes <= 0 {
		opt.Limits.MaxTicketBytes = 25 << 20
	}
	if opt.RetentionDays <= 0 {
		opt.RetentionDays = 180
	}
	if opt.ClientIP == nil {
		opt.ClientIP = func(r *http.Request) string { return r.RemoteAddr }
	}
	for _, l := range []*Limiter{&opt.CreateLimiter, &opt.MessageLimiter, &opt.UploadLimiter, &opt.ReadLimiter} {
		if *l == nil {
			*l = allowAll{}
		}
	}
	if opt.OnEvent == nil {
		opt.OnEvent = func(string, any) {}
	}
	files, err := newFileStore(opt.Dir)
	if err != nil {
		return nil, err
	}
	return &Service{
		repo: repo, files: files, opt: opt,
		processing: make(chan struct{}, 2),
		receiving:  make(chan struct{}, 8),
		now:        time.Now,
	}, nil
}

// Register adds the public routes to mux.
func (s *Service) Register(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/feedback/tickets", s.handleCreate)
	mux.HandleFunc("GET /api/feedback/inbox", s.handleInbox)
	mux.HandleFunc("GET /api/feedback/tickets/{tid}", s.handleThread)
	mux.HandleFunc("DELETE /api/feedback/tickets/{tid}", s.handleDelete)
	mux.HandleFunc("POST /api/feedback/tickets/{tid}/messages", s.handleMessage)
	mux.HandleFunc("PUT /api/feedback/tickets/{tid}/messages/{mid}/attachments/{aid}", s.handleUpload)
	mux.HandleFunc("POST /api/feedback/tickets/{tid}/rating", s.handleRating)
	mux.HandleFunc("POST /api/feedback/tickets/{tid}/read", s.handleRead)
}

// Run purges old attachments until ctx ends.
func (s *Service) Run(ctx context.Context) {
	t := time.NewTicker(6 * time.Hour)
	defer t.Stop()
	for {
		s.purge(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func (s *Service) purge(ctx context.Context) {
	ctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	cutoff := s.now().Add(-time.Duration(s.opt.RetentionDays) * 24 * time.Hour)
	shas, err := s.repo.PurgeClosedAttachments(ctx, cutoff)
	if err != nil {
		log.Printf("feedback: purge: %v", err)
		return
	}
	s.dropUnused(ctx, shas)
}

// dropUnused removes the files of shas that no attachment uses any more.
func (s *Service) dropUnused(ctx context.Context, shas []string) {
	if len(shas) == 0 {
		return
	}
	s.files.mu.Lock()
	defer s.files.mu.Unlock()
	seen := map[string]bool{}
	for _, sha := range shas {
		if seen[sha] {
			continue
		}
		seen[sha] = true
		if used, err := s.repo.SHAInUse(ctx, sha); err == nil && !used {
			s.files.remove(sha)
		}
	}
}

// ---- helpers ----

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]string{"error": code, "message": msg})
}

func (s *Service) serverErr(w http.ResponseWriter, where string, err error) {
	log.Printf("feedback: %s: %v", where, err)
	writeErr(w, http.StatusInternalServerError, "store_error", "Could not save that. Try again later.")
}

const maxJSONBytes = 64 << 10

func decode(w http.ResponseWriter, r *http.Request, dst any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxJSONBytes)
	defer r.Body.Close()
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(dst); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeErr(w, http.StatusRequestEntityTooLarge, "request_too_large", "Request body is too large.")
		} else {
			writeErr(w, http.StatusUnprocessableEntity, "invalid_json", "Malformed JSON body.")
		}
		return false
	}
	if err := dec.Decode(&struct{}{}); err != io.EOF {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_json", "Request body must contain one JSON object.")
		return false
	}
	return true
}

func bearer(r *http.Request) string {
	parts := strings.SplitN(r.Header.Get("Authorization"), " ", 2)
	if len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") {
		return strings.TrimSpace(parts[1])
	}
	return ""
}

// originOK is the rule the presence and error endpoints use: the apps send no Origin, the web
// player sends one of the configured origins, and nothing else gets in, so a web page cannot make
// its visitors file reports.
func originOK(r *http.Request, platform string) bool {
	origin := r.Header.Get("Origin")
	if origin != "" && !config.IsAllowedOrigin(origin) {
		return false
	}
	if platform == "" {
		return true
	}
	return (platform == "browser") == (origin != "")
}

// reporter authenticates the request's token. It answers 401 itself when it fails.
func (s *Service) reporter(w http.ResponseWriter, r *http.Request) (Reporter, bool) {
	tok := bearer(r)
	if !tokenPattern.MatchString(tok) {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "A reporter token is required.")
		return Reporter{}, false
	}
	rep, err := s.repo.Reporter(r.Context(), hashToken(tok))
	if errors.Is(err, ErrNotFound) {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "Unknown reporter token.")
		return Reporter{}, false
	}
	if err != nil {
		s.serverErr(w, "reporter", err)
		return Reporter{}, false
	}
	return rep, true
}

// ticket authenticates and finds the path's ticket among the reporter's. It answers itself
// when it fails; someone else's ticket is a 404, the same as one that does not exist.
func (s *Service) ticket(w http.ResponseWriter, r *http.Request) (Reporter, Ticket, bool) {
	if !originOK(r, "") {
		writeErr(w, http.StatusForbidden, "origin_not_allowed", "Feedback is only accepted from the apps.")
		return Reporter{}, Ticket{}, false
	}
	rep, ok := s.reporter(w, r)
	if !ok {
		return rep, Ticket{}, false
	}
	pid := r.PathValue("tid")
	if !publicIDPattern.MatchString(pid) {
		writeErr(w, http.StatusNotFound, "not_found", "No such ticket.")
		return rep, Ticket{}, false
	}
	t, err := s.repo.Ticket(r.Context(), rep.ID, pid)
	if errors.Is(err, ErrNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "No such ticket.")
		return rep, Ticket{}, false
	}
	if err != nil {
		s.serverErr(w, "ticket", err)
		return rep, Ticket{}, false
	}
	return rep, t, true
}

func (s *Service) limited(w http.ResponseWriter, r *http.Request, l Limiter) bool {
	if l.Allow(s.opt.ClientIP(r)) {
		return false
	}
	w.Header().Set("Retry-After", "600")
	writeErr(w, http.StatusTooManyRequests, "rate_limited", "Too many requests from this address. Try again later.")
	return true
}

func (s *Service) limitsJSON() map[string]int64 {
	return map[string]int64{
		"maxAttachmentsPerMessage": int64(s.opt.Limits.MaxPerMessage),
		"maxTicketBytes":           s.opt.Limits.MaxTicketBytes,
		"maxUploadBytes":           s.opt.MaxUploadBytes,
	}
}

// ---- handlers ----

func (s *Service) handleCreate(w http.ResponseWriter, r *http.Request) {
	if s.limited(w, r, s.opt.CreateLimiter) {
		return
	}
	var body struct {
		ClientTicketID string       `json:"clientTicketId"`
		Kind           string       `json:"kind"`
		Title          string       `json:"title"`
		Body           string       `json:"body"`
		Platform       string       `json:"platform"`
		AppVersion     string       `json:"appVersion"`
		Locale         string       `json:"locale"`
		Diagnostics    *Diagnostics `json:"diagnostics"`
	}
	if !decode(w, r, &body) {
		return
	}
	if _, ok := presence.ParsePlatform(body.Platform); !ok {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_platform", "platform must be android, pc or browser.")
		return
	}
	if !originOK(r, body.Platform) {
		writeErr(w, http.StatusForbidden, "origin_not_allowed", "Feedback is only accepted from the apps.")
		return
	}
	in := NewTicket{
		ClientTicketID: strings.ToLower(body.ClientTicketID),
		Kind:           body.Kind,
		Title:          cleanLine(body.Title),
		Body:           cleanText(body.Body),
		Platform:       body.Platform,
		AppVersion:     strings.TrimSpace(body.AppVersion),
		Locale:         strings.TrimSpace(body.Locale),
		Diagnostics:    cleanDiagnostics(body.Diagnostics),
	}
	switch {
	case !validUUID(in.ClientTicketID):
		writeErr(w, http.StatusUnprocessableEntity, "invalid_id", "clientTicketId must be a UUID.")
		return
	case !kinds[in.Kind]:
		writeErr(w, http.StatusUnprocessableEntity, "invalid_kind", "kind must be bug, idea, question or other.")
		return
	case runeLen(in.Title) < minTitle || runeLen(in.Title) > maxTitle:
		writeErr(w, http.StatusUnprocessableEntity, "invalid_title", "title must be 3 to 140 characters.")
		return
	case in.Body == "" || runeLen(in.Body) > maxBody:
		writeErr(w, http.StatusUnprocessableEntity, "invalid_body", "body must be 1 to 8000 characters.")
		return
	case !versionPattern.MatchString(in.AppVersion):
		writeErr(w, http.StatusUnprocessableEntity, "invalid_version", "appVersion contains invalid characters.")
		return
	}
	if !localePattern.MatchString(in.Locale) {
		in.Locale = ""
	}

	// A token the server does not know (never issued, or issued before the database was reset)
	// is not an error: the person just gets a new one, as on their first report.
	ctx := r.Context()
	var rep Reporter
	var issued string
	if tok := bearer(r); tokenPattern.MatchString(tok) {
		found, err := s.repo.Reporter(ctx, hashToken(tok))
		if err != nil && !errors.Is(err, ErrNotFound) {
			s.serverErr(w, "reporter", err)
			return
		}
		rep = found
	}
	if rep.ID == 0 {
		issued = newToken()
		created, err := s.repo.CreateReporter(ctx, hashToken(issued), in.Platform, in.Locale)
		if err != nil {
			s.serverErr(w, "create reporter", err)
			return
		}
		rep = created
	}

	resp := map[string]any{"limits": s.limitsJSON()}
	if issued != "" {
		resp["reporterToken"] = issued
	}
	if rep.Blocked {
		// Accepted, as far as the sender can tell, and dropped: a blocked sender who saw a
		// refusal would just reinstall and come back with a new token.
		now := s.now().UnixMilli()
		resp["ticket"] = Ticket{PublicID: newPublicID(), Kind: in.Kind, Title: in.Title, Status: StatusNew, Platform: in.Platform, AppVersion: in.AppVersion, CreatedAt: now, UpdatedAt: now}
		resp["message"] = Message{Author: AuthorUser, Kind: MsgText, Body: in.Body, CreatedAt: now, Attachments: []Attachment{}}
		writeJSON(w, http.StatusCreated, resp)
		return
	}

	in.ReporterID = rep.ID
	t, first, created, err := s.repo.CreateTicket(ctx, in, newPublicID())
	if err != nil {
		s.serverErr(w, "create ticket", err)
		return
	}
	resp["ticket"], resp["message"] = t, first
	status := http.StatusOK
	if created {
		status = http.StatusCreated
		s.opt.OnEvent("feedback", map[string]any{"event": "ticket", "id": t.PublicID, "kind": t.Kind, "title": t.Title, "platform": t.Platform})
	}
	writeJSON(w, status, resp)
}

func (s *Service) handleInbox(w http.ResponseWriter, r *http.Request) {
	if s.limited(w, r, s.opt.ReadLimiter) {
		return
	}
	if !originOK(r, "") {
		writeErr(w, http.StatusForbidden, "origin_not_allowed", "Feedback is only accepted from the apps.")
		return
	}
	rep, ok := s.reporter(w, r)
	if !ok {
		return
	}
	_ = s.repo.TouchReporter(r.Context(), rep.ID)
	etag := `"v` + strconv.FormatInt(rep.InboxVersion, 10) + `"`
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", "no-cache")
	// Nothing changed since the version the app already has: the common case, one indexed
	// lookup and no body.
	if r.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	in, err := s.repo.Inbox(r.Context(), rep.ID, max(since, 0), 50)
	if err != nil {
		s.serverErr(w, "inbox", err)
		return
	}
	w.Header().Set("ETag", `"v`+strconv.FormatInt(in.Version, 10)+`"`)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(in)
}

func (s *Service) handleThread(w http.ResponseWriter, r *http.Request) {
	if s.limited(w, r, s.opt.ReadLimiter) {
		return
	}
	_, t, ok := s.ticket(w, r)
	if !ok {
		return
	}
	msgs, err := s.repo.Thread(r.Context(), t.ID)
	if err != nil {
		s.serverErr(w, "thread", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ticket": t, "messages": msgs, "limits": s.limitsJSON()})
}

func (s *Service) handleDelete(w http.ResponseWriter, r *http.Request) {
	if s.limited(w, r, s.opt.MessageLimiter) {
		return
	}
	_, t, ok := s.ticket(w, r)
	if !ok {
		return
	}
	shas, err := s.repo.DeleteTicket(r.Context(), t.ID)
	if err != nil && !errors.Is(err, ErrNotFound) {
		s.serverErr(w, "delete", err)
		return
	}
	s.dropUnused(r.Context(), shas)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Service) handleMessage(w http.ResponseWriter, r *http.Request) {
	if s.limited(w, r, s.opt.MessageLimiter) {
		return
	}
	rep, t, ok := s.ticket(w, r)
	if !ok {
		return
	}
	var body struct {
		ClientMessageID string `json:"clientMessageId"`
		Body            string `json:"body"`
	}
	if !decode(w, r, &body) {
		return
	}
	clientID := strings.ToLower(body.ClientMessageID)
	text := cleanText(body.Body)
	if !validUUID(clientID) {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_id", "clientMessageId must be a UUID.")
		return
	}
	if text == "" || runeLen(text) > maxBody {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_body", "body must be 1 to 8000 characters.")
		return
	}
	if rep.Blocked {
		writeJSON(w, http.StatusCreated, map[string]any{"message": Message{Author: AuthorUser, Kind: MsgText, Body: text, CreatedAt: s.now().UnixMilli(), Attachments: []Attachment{}}})
		return
	}
	m, created, err := s.repo.AddUserMessage(r.Context(), t, clientID, text)
	if err != nil {
		s.serverErr(w, "message", err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
		s.opt.OnEvent("feedback", map[string]any{"event": "message", "id": t.PublicID, "title": t.Title})
	}
	writeJSON(w, status, map[string]any{"message": m})
}

func (s *Service) handleRating(w http.ResponseWriter, r *http.Request) {
	if s.limited(w, r, s.opt.MessageLimiter) {
		return
	}
	rep, t, ok := s.ticket(w, r)
	if !ok {
		return
	}
	var body struct {
		ClientMessageID string `json:"clientMessageId"`
		Resolved        *bool  `json:"resolved"`
		Comment         string `json:"comment"`
	}
	if !decode(w, r, &body) {
		return
	}
	clientID := strings.ToLower(body.ClientMessageID)
	comment := cleanText(body.Comment)
	if !validUUID(clientID) {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_id", "clientMessageId must be a UUID.")
		return
	}
	if body.Resolved == nil {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_rating", "resolved must be true or false.")
		return
	}
	if runeLen(comment) > maxComment {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_body", "comment must be at most 1000 characters.")
		return
	}
	if rep.Blocked {
		writeJSON(w, http.StatusCreated, map[string]any{"message": Message{Author: AuthorUser, Kind: MsgRating, Body: comment, CreatedAt: s.now().UnixMilli(), Attachments: []Attachment{}}})
		return
	}
	m, created, err := s.repo.SetRating(r.Context(), t, clientID, *body.Resolved, comment)
	if err != nil {
		s.serverErr(w, "rating", err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
		s.opt.OnEvent("feedback", map[string]any{"event": "rating", "id": t.PublicID, "resolved": *body.Resolved})
	}
	writeJSON(w, status, map[string]any{"message": m})
}

func (s *Service) handleRead(w http.ResponseWriter, r *http.Request) {
	if s.limited(w, r, s.opt.ReadLimiter) {
		return
	}
	_, t, ok := s.ticket(w, r)
	if !ok {
		return
	}
	var body struct {
		UpTo int64 `json:"upTo"`
	}
	if !decode(w, r, &body) {
		return
	}
	if err := s.repo.MarkRead(r.Context(), t, body.UpTo); err != nil {
		s.serverErr(w, "read", err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
