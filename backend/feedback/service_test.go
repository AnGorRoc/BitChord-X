package feedback

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/KabirSinghBhatia/BitChord/backend/config"
)

type countLimiter struct {
	mu    sync.Mutex
	limit int
	n     map[string]int
}

func (l *countLimiter) Allow(k string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.n == nil {
		l.n = map[string]int{}
	}
	l.n[k]++
	return l.n[k] <= l.limit
}

type harness struct {
	t      *testing.T
	repo   *memRepo
	svc    *Service
	srv    *httptest.Server
	dir    string
	events []string
	mu     sync.Mutex
}

func newHarness(t *testing.T, mod func(*Options)) *harness {
	h := &harness{t: t, repo: newMemRepo(time.Now), dir: t.TempDir()}
	opt := Options{
		Dir:            h.dir,
		MaxUploadBytes: 2 << 20,
		Limits:         Limits{MaxPerMessage: 2, MaxTicketBytes: 10 << 20},
		OnEvent: func(kind string, payload any) {
			h.mu.Lock()
			defer h.mu.Unlock()
			h.events = append(h.events, fmt.Sprint(payload.(map[string]any)["event"]))
		},
	}
	if mod != nil {
		mod(&opt)
	}
	svc, err := New(h.repo, opt)
	if err != nil {
		t.Fatal(err)
	}
	h.svc = svc
	mux := http.NewServeMux()
	svc.Register(mux)
	svc.RegisterAdmin(mux)
	h.srv = httptest.NewServer(mux)
	t.Cleanup(h.srv.Close)
	return h
}

type resp struct {
	status int
	header http.Header
	body   map[string]any
}

func (h *harness) do(method, path, token string, body any, hdr map[string]string) resp {
	h.t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case []byte:
		rd = bytes.NewReader(b)
	default:
		js, _ := json.Marshal(b)
		rd = bytes.NewReader(js)
	}
	req, _ := http.NewRequest(method, h.srv.URL+path, rd)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer res.Body.Close()
	out := resp{status: res.StatusCode, header: res.Header}
	raw, _ := io.ReadAll(res.Body)
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &out.body)
	}
	return out
}

func ticketBody(clientID string) map[string]any {
	return map[string]any{
		"clientTicketId": clientID, "kind": "bug", "title": "  Crash al abrir  ", "body": "Se cierra\r\nal abrir",
		"platform": "android", "appVersion": "2.1.0", "locale": "es-MX",
		"diagnostics": map[string]any{"deviceModel": "Pixel 8", "telemetryLevel": "minimal", "installId": "6f1c3a1e-2b9d-4c55-8a77-3e2f1b0c9d11"},
	}
}

const (
	uuid1 = "11111111-1111-4111-8111-111111111111"
	uuid2 = "22222222-2222-4222-8222-222222222222"
	uuid3 = "33333333-3333-4333-8333-333333333333"
	uuid4 = "44444444-4444-4444-8444-444444444444"
)

// create files a ticket and returns the token, ticket ID and first message ID.
func (h *harness) create() (string, string, int64) {
	h.t.Helper()
	r := h.do("POST", "/api/feedback/tickets", "", ticketBody(uuid1), nil)
	if r.status != http.StatusCreated {
		h.t.Fatalf("create: %d %v", r.status, r.body)
	}
	tok, _ := r.body["reporterToken"].(string)
	tk := r.body["ticket"].(map[string]any)
	msg := r.body["message"].(map[string]any)
	return tok, tk["id"].(string), int64(msg["id"].(float64))
}

func TestCreateTicket(t *testing.T) {
	h := newHarness(t, nil)
	tok, pid, _ := h.create()
	if !tokenPattern.MatchString(tok) || !publicIDPattern.MatchString(pid) {
		t.Fatalf("token %q id %q", tok, pid)
	}
	tk, _ := h.repo.Ticket(context.Background(), 1, pid)
	if tk.Title != "Crash al abrir" {
		t.Fatalf("title not cleaned: %q", tk.Title)
	}
	thread, _ := h.repo.Thread(context.Background(), tk.ID)
	if thread[0].Body != "Se cierra\nal abrir" {
		t.Fatalf("body %q", thread[0].Body)
	}
	for _, rp := range h.repo.reporters {
		if rp.hash != hashToken(tok) {
			t.Fatal("the server must keep only the token's hash")
		}
	}

	// A retry with the same token and client ID returns the same ticket, 200, no new token.
	r := h.do("POST", "/api/feedback/tickets", tok, ticketBody(uuid1), nil)
	if r.status != http.StatusOK || r.body["ticket"].(map[string]any)["id"] != pid || r.body["reporterToken"] != nil {
		t.Fatalf("retry: %d %v", r.status, r.body)
	}
	// Same token, new client ID: a second ticket for the same reporter.
	r = h.do("POST", "/api/feedback/tickets", tok, ticketBody(uuid2), nil)
	if r.status != http.StatusCreated || r.body["reporterToken"] != nil {
		t.Fatalf("second: %d %v", r.status, r.body)
	}
	// An unknown token is replaced, not refused.
	r = h.do("POST", "/api/feedback/tickets", "fbr_"+strings.Repeat("A", 43), ticketBody(uuid1), nil)
	if r.status != http.StatusCreated || r.body["reporterToken"] == nil {
		t.Fatalf("unknown token: %d %v", r.status, r.body)
	}
	if len(h.events) != 3 {
		t.Fatalf("events %v", h.events)
	}
}

func TestCreateValidation(t *testing.T) {
	h := newHarness(t, nil)
	cases := map[string]func(map[string]any){
		"bad uuid":     func(b map[string]any) { b["clientTicketId"] = "x" },
		"bad kind":     func(b map[string]any) { b["kind"] = "rant" },
		"short title":  func(b map[string]any) { b["title"] = "ab" },
		"long title":   func(b map[string]any) { b["title"] = strings.Repeat("a", 141) },
		"empty body":   func(b map[string]any) { b["body"] = " \n " },
		"long body":    func(b map[string]any) { b["body"] = strings.Repeat("é", 8001) },
		"bad version":  func(b map[string]any) { b["appVersion"] = "1.0<script>" },
		"bad platform": func(b map[string]any) { b["platform"] = "ios" },
	}
	for name, mod := range cases {
		b := ticketBody(uuid1)
		mod(b)
		if r := h.do("POST", "/api/feedback/tickets", "", b, nil); r.status != http.StatusUnprocessableEntity {
			t.Errorf("%s: %d %v", name, r.status, r.body)
		}
	}
	if r := h.do("POST", "/api/feedback/tickets", "", []byte(`{"a":1}{"b":2}`), nil); r.status != http.StatusUnprocessableEntity {
		t.Errorf("two objects: %d", r.status)
	}
	if r := h.do("POST", "/api/feedback/tickets", "", []byte(`{"body":"`+strings.Repeat("a", maxJSONBytes)+`"}`), nil); r.status != http.StatusRequestEntityTooLarge {
		t.Errorf("huge body: %d", r.status)
	}
}

func TestOrigins(t *testing.T) {
	orig := config.AllowedOrigins
	config.AllowedOrigins = []string{"https://web.example"}
	defer func() { config.AllowedOrigins = orig }()
	h := newHarness(t, nil)

	if r := h.do("POST", "/api/feedback/tickets", "", ticketBody(uuid1), map[string]string{"Origin": "https://evil.example"}); r.status != http.StatusForbidden {
		t.Fatalf("foreign origin: %d", r.status)
	}
	// An app (android) does not send an Origin; one that does is a page pretending.
	if r := h.do("POST", "/api/feedback/tickets", "", ticketBody(uuid1), map[string]string{"Origin": "https://web.example"}); r.status != http.StatusForbidden {
		t.Fatalf("android with origin: %d", r.status)
	}
	b := ticketBody(uuid1)
	b["platform"] = "browser"
	if r := h.do("POST", "/api/feedback/tickets", "", b, nil); r.status != http.StatusForbidden {
		t.Fatalf("browser without origin: %d", r.status)
	}
	if r := h.do("POST", "/api/feedback/tickets", "", b, map[string]string{"Origin": "https://web.example"}); r.status != http.StatusCreated {
		t.Fatalf("web player: %d", r.status)
	}
	tok, pid, _ := h.create()
	if r := h.do("GET", "/api/feedback/tickets/"+pid, tok, nil, map[string]string{"Origin": "https://evil.example"}); r.status != http.StatusForbidden {
		t.Fatalf("read from foreign origin: %d", r.status)
	}
}

func TestTicketsArePrivate(t *testing.T) {
	h := newHarness(t, nil)
	tok, pid, mid := h.create()
	other := h.do("POST", "/api/feedback/tickets", "", ticketBody(uuid2), nil).body["reporterToken"].(string)

	if r := h.do("GET", "/api/feedback/tickets/"+pid, "", nil, nil); r.status != http.StatusUnauthorized {
		t.Fatalf("no token: %d", r.status)
	}
	if r := h.do("GET", "/api/feedback/inbox", "fbr_bad", nil, nil); r.status != http.StatusUnauthorized {
		t.Fatalf("malformed token: %d", r.status)
	}
	for _, c := range []struct{ method, path string }{
		{"GET", "/api/feedback/tickets/" + pid},
		{"DELETE", "/api/feedback/tickets/" + pid},
		{"POST", "/api/feedback/tickets/" + pid + "/messages"},
		{"PUT", fmt.Sprintf("/api/feedback/tickets/%s/messages/%d/attachments/%s", pid, mid, uuid3)},
	} {
		if r := h.do(c.method, c.path, other, map[string]any{"clientMessageId": uuid3, "body": "x"}, nil); r.status != http.StatusNotFound {
			t.Errorf("%s %s as someone else: %d", c.method, c.path, r.status)
		}
	}
	if r := h.do("GET", "/api/feedback/tickets/fb_../../etc", tok, nil, nil); r.status != http.StatusNotFound {
		t.Fatalf("odd id: %d", r.status)
	}
	if r := h.do("GET", "/api/feedback/tickets/"+pid, tok, nil, nil); r.status != http.StatusOK || len(r.body["messages"].([]any)) != 1 {
		t.Fatalf("own ticket: %d %v", r.status, r.body)
	}
}

func (h *harness) upload(tok, pid string, mid int64, aid string, data []byte, hdr map[string]string) resp {
	return h.do("PUT", fmt.Sprintf("/api/feedback/tickets/%s/messages/%d/attachments/%s", pid, mid, aid), tok, data, hdr)
}

func TestUpload(t *testing.T) {
	h := newHarness(t, nil)
	tok, pid, mid := h.create()

	img := pngBytes(t, 40, 30, false)
	r := h.upload(tok, pid, mid, uuid1, img, nil)
	if r.status != http.StatusCreated {
		t.Fatalf("upload: %d %v", r.status, r.body)
	}
	a := r.body["attachment"].(map[string]any)
	if a["mime"] != "image/png" || a["width"].(float64) != 40 || a["clientId"] != uuid1 {
		t.Fatalf("%v", a)
	}
	att, _ := h.repo.Attachment(context.Background(), mid, uuid1)
	if _, err := os.Stat(filepath.Join(h.dir, att.SHA256[:2], att.SHA256+".png")); err != nil {
		t.Fatalf("file not on disk: %v", err)
	}
	if _, err := os.Stat(filepath.Join(h.dir, att.SHA256[:2], att.SHA256+"_t.jpg")); err != nil {
		t.Fatalf("thumb not on disk: %v", err)
	}
	// Retry: same answer, nothing new.
	if r := h.upload(tok, pid, mid, uuid1, img, nil); r.status != http.StatusOK {
		t.Fatalf("retry: %d", r.status)
	}
	// Same picture as another attachment: stored once on disk.
	if r := h.upload(tok, pid, mid, uuid2, img, nil); r.status != http.StatusCreated {
		t.Fatalf("second: %d", r.status)
	}
	if r := h.upload(tok, pid, mid, uuid3, img, nil); r.status != http.StatusRequestEntityTooLarge {
		t.Fatalf("over per-message count: %d %v", r.status, r.body)
	}
	entries, _ := os.ReadDir(filepath.Join(h.dir, att.SHA256[:2]))
	if len(entries) != 2 {
		t.Fatalf("expected one file and its thumb, got %d", len(entries))
	}

	// A reply message can carry its own files.
	reply := h.do("POST", "/api/feedback/tickets/"+pid+"/messages", tok, map[string]any{"clientMessageId": uuid4, "body": "otra captura"}, nil)
	rmid := int64(reply.body["message"].(map[string]any)["id"].(float64))
	if r := h.upload(tok, pid, rmid, uuid1, []byte("a log line\n"), nil); r.status != http.StatusCreated || r.body["attachment"].(map[string]any)["kind"] != AttachLog {
		t.Fatalf("log: %d %v", r.status, r.body)
	}
	if r := h.upload(tok, pid, rmid, uuid2, []byte("<svg></svg>"), nil); r.status != http.StatusUnsupportedMediaType {
		t.Fatalf("svg: %d", r.status)
	}
	if r := h.upload(tok, pid, rmid, uuid2, []byte("\x89PNG\r\n\x1a\nbroken"), nil); r.status != http.StatusUnprocessableEntity {
		t.Fatalf("corrupt: %d", r.status)
	}
	if r := h.upload(tok, pid, rmid, uuid2, bytes.Repeat([]byte("a"), 3<<20), nil); r.status != http.StatusRequestEntityTooLarge {
		t.Fatalf("too big: %d", r.status)
	}
	if r := h.upload(tok, pid, rmid, "not-a-uuid", img, nil); r.status != http.StatusUnprocessableEntity {
		t.Fatalf("bad aid: %d", r.status)
	}
	if r := h.upload(tok, pid, 999999, uuid2, img, nil); r.status != http.StatusNotFound {
		t.Fatalf("unknown message: %d", r.status)
	}

	// Deleting the ticket removes its files from disk.
	if r := h.do("DELETE", "/api/feedback/tickets/"+pid, tok, nil, nil); r.status != http.StatusNoContent {
		t.Fatalf("delete: %d", r.status)
	}
	if _, err := os.Stat(filepath.Join(h.dir, att.SHA256[:2], att.SHA256+".png")); !os.IsNotExist(err) {
		t.Fatalf("file left behind: %v", err)
	}
	if r := h.do("GET", "/api/feedback/tickets/"+pid, tok, nil, nil); r.status != http.StatusNotFound {
		t.Fatalf("deleted ticket: %d", r.status)
	}
}

func TestUploadRequiresLength(t *testing.T) {
	h := newHarness(t, nil)
	tok, pid, mid := h.create()
	pr, pw := io.Pipe()
	go func() { pw.Write(pngBytes(t, 4, 4, false)); pw.Close() }()
	req, _ := http.NewRequest("PUT", fmt.Sprintf("%s/api/feedback/tickets/%s/messages/%d/attachments/%s", h.srv.URL, pid, mid, uuid1), pr)
	req.Header.Set("Authorization", "Bearer "+tok)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusLengthRequired {
		t.Fatalf("chunked upload: %d", res.StatusCode)
	}
}

func TestDiskQuota(t *testing.T) {
	h := newHarness(t, func(o *Options) { o.MaxDiskBytes = 100 })
	tok, pid, mid := h.create()
	if r := h.upload(tok, pid, mid, uuid1, pngBytes(t, 40, 40, false), nil); r.status != http.StatusInsufficientStorage {
		t.Fatalf("quota: %d", r.status)
	}
}

func TestInboxConversation(t *testing.T) {
	h := newHarness(t, nil)
	tok, pid, _ := h.create()
	ctx := context.Background()

	r := h.do("GET", "/api/feedback/inbox", tok, nil, nil)
	etag := r.header.Get("ETag")
	if r.status != http.StatusOK || etag == "" || len(r.body["tickets"].([]any)) != 1 || len(r.body["messages"].([]any)) != 0 {
		t.Fatalf("inbox: %d %v", r.status, r.body)
	}
	if r := h.do("GET", "/api/feedback/inbox", tok, nil, map[string]string{"If-None-Match": etag}); r.status != http.StatusNotModified {
		t.Fatalf("unchanged inbox: %d", r.status)
	}

	tk, _ := h.repo.Ticket(ctx, 1, pid)
	admin, _ := h.repo.AddAdminMessage(ctx, tk.ID, MsgText, "Hola {{nombre|amigo}}, ¿qué versión de Android?")
	r = h.do("GET", "/api/feedback/inbox", tok, nil, map[string]string{"If-None-Match": etag})
	if r.status != http.StatusOK || r.header.Get("ETag") == etag {
		t.Fatalf("after reply: %d", r.status)
	}
	msgs := r.body["messages"].([]any)
	if len(msgs) != 1 || msgs[0].(map[string]any)["body"] != "Hola {{nombre|amigo}}, ¿qué versión de Android?" {
		t.Fatalf("variables must reach the app unresolved: %v", msgs)
	}
	if r.body["tickets"].([]any)[0].(map[string]any)["unread"].(float64) != 1 {
		t.Fatal("unread count")
	}
	etag = r.header.Get("ETag")
	if r := h.do("POST", "/api/feedback/tickets/"+pid+"/read", tok, map[string]any{"upTo": admin.ID}, nil); r.status != http.StatusNoContent {
		t.Fatalf("read: %d", r.status)
	}
	r = h.do("GET", fmt.Sprintf("/api/feedback/inbox?since=%d", admin.ID), tok, nil, map[string]string{"If-None-Match": etag})
	if r.status != http.StatusOK || len(r.body["messages"].([]any)) != 0 || r.body["tickets"].([]any)[0].(map[string]any)["unread"].(float64) != 0 {
		t.Fatalf("after read: %d %v", r.status, r.body)
	}

	// The person answers, then says it is solved.
	if r := h.do("POST", "/api/feedback/tickets/"+pid+"/messages", tok, map[string]any{"clientMessageId": uuid2, "body": "Android 15"}, nil); r.status != http.StatusCreated {
		t.Fatalf("reply: %d", r.status)
	}
	_ = h.repo.SetStatus(ctx, tk.ID, StatusDone)
	if r := h.do("POST", "/api/feedback/tickets/"+pid+"/rating", tok, map[string]any{"clientMessageId": uuid3, "resolved": false, "comment": "sigue pasando"}, nil); r.status != http.StatusCreated {
		t.Fatalf("rating: %d", r.status)
	}
	if tk, _ := h.repo.Ticket(ctx, 1, pid); tk.Status != StatusReviewed || tk.Satisfaction != -1 {
		t.Fatalf("not solved should reopen: %+v", tk)
	}
	if r := h.do("POST", "/api/feedback/tickets/"+pid+"/rating", tok, map[string]any{"clientMessageId": uuid4}, nil); r.status != http.StatusUnprocessableEntity {
		t.Fatalf("rating without verdict: %d", r.status)
	}
}

func TestBlockedReporterIsShadowAccepted(t *testing.T) {
	h := newHarness(t, nil)
	tok, pid, mid := h.create()
	h.repo.SetBlocked(1, true)
	before := len(h.repo.tickets)
	r := h.do("POST", "/api/feedback/tickets", tok, ticketBody(uuid2), nil)
	if r.status != http.StatusCreated || len(h.repo.tickets) != before {
		t.Fatalf("blocked create: %d, tickets %d -> %d", r.status, before, len(h.repo.tickets))
	}
	if r := h.do("POST", "/api/feedback/tickets/"+pid+"/messages", tok, map[string]any{"clientMessageId": uuid3, "body": "spam"}, nil); r.status != http.StatusCreated {
		t.Fatalf("blocked message: %d", r.status)
	}
	if r := h.upload(tok, pid, mid, uuid1, pngBytes(t, 4, 4, false), nil); r.status != http.StatusCreated {
		t.Fatalf("blocked upload: %d", r.status)
	}
	tk, _ := h.repo.Ticket(context.Background(), 1, pid)
	thread, _ := h.repo.Thread(context.Background(), tk.ID)
	if len(thread) != 1 || len(thread[0].Attachments) != 0 {
		t.Fatalf("blocked sender's content was stored: %+v", thread)
	}
}

func TestRateLimited(t *testing.T) {
	h := newHarness(t, func(o *Options) { o.CreateLimiter = &countLimiter{limit: 1} })
	h.create()
	r := h.do("POST", "/api/feedback/tickets", "", ticketBody(uuid2), nil)
	if r.status != http.StatusTooManyRequests || r.header.Get("Retry-After") == "" {
		t.Fatalf("second create: %d", r.status)
	}
}

func TestPurgeRemovesOldFiles(t *testing.T) {
	h := newHarness(t, nil)
	tok, pid, mid := h.create()
	h.upload(tok, pid, mid, uuid1, pngBytes(t, 8, 8, false), nil)
	att, _ := h.repo.Attachment(context.Background(), mid, uuid1)
	tk, _ := h.repo.Ticket(context.Background(), 1, pid)
	_ = h.repo.SetStatus(context.Background(), tk.ID, StatusDone)
	h.svc.now = func() time.Time { return time.Now().Add(181 * 24 * time.Hour) }
	h.svc.purge(context.Background())
	if _, err := os.Stat(filepath.Join(h.dir, att.SHA256[:2], att.SHA256+".png")); !os.IsNotExist(err) {
		t.Fatalf("old attachment kept: %v", err)
	}
}
