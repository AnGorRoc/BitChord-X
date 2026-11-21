package feedback

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"
)

func TestAdminConversation(t *testing.T) {
	h := newHarness(t, nil)
	tok, pid, mid := h.create()
	h.upload(tok, pid, mid, uuid1, pngBytes(t, 50, 40, false), nil)

	// The list shows it as unread, with its attachment counted.
	r := h.do("GET", "/api/admin/feedback/tickets?status=unread", "", nil, nil)
	items := r.body["items"].([]any)
	if r.status != 200 || len(items) != 1 {
		t.Fatalf("list %d %v", r.status, r.body)
	}
	it := items[0].(map[string]any)
	if it["id"] != pid || it["attachmentCount"].(float64) != 1 || it["unreadAdmin"] != true {
		t.Fatalf("item %v", it)
	}
	if r := h.do("GET", "/api/admin/feedback/summary", "", nil, nil); r.body["summary"].(map[string]any)["unread"].(float64) != 1 {
		t.Fatalf("summary %v", r.body)
	}

	// A reply with a misspelt variable is refused with the reason.
	r = h.do("POST", "/api/admin/feedback/tickets/"+pid+"/messages", "", map[string]any{"body": "Hola {{nobre}}"}, nil)
	if r.status != http.StatusUnprocessableEntity || fmt.Sprint(r.body["problems"]) != "[unknown:nobre]" {
		t.Fatalf("lint: %d %v", r.status, r.body)
	}
	// A real reply that also asks for more info.
	r = h.do("POST", "/api/admin/feedback/tickets/"+pid+"/messages", "", map[string]any{"body": "Hola {{nombre}}, ¿qué Android usas?", "status": StatusNeedInfo}, nil)
	if r.status != http.StatusCreated {
		t.Fatalf("reply %d %v", r.status, r.body)
	}
	d := h.do("GET", "/api/admin/feedback/tickets/"+pid, "", nil, nil).body
	thread := d["thread"].([]any)
	if d["status"] != StatusNeedInfo || d["unreadAdmin"] != false || len(thread) != 3 {
		t.Fatalf("after reply %v", d)
	}
	if thread[1].(map[string]any)["body"] != "Hola {{nombre}}, ¿qué Android usas?" || thread[2].(map[string]any)["kind"] != MsgStatus {
		t.Fatalf("thread %v", thread)
	}
	// The app sees the reply (unresolved) and the status change.
	in := h.do("GET", "/api/feedback/inbox", tok, nil, nil).body
	if len(in["messages"].([]any)) != 2 {
		t.Fatalf("inbox %v", in)
	}

	md, _ := http.Get(h.srv.URL + "/api/admin/feedback/tickets/" + pid + "?format=md")
	raw, _ := io.ReadAll(md.Body)
	md.Body.Close()
	if !strings.Contains(string(raw), "Hola {{nombre}}") || !strings.Contains(md.Header.Get("Content-Type"), "markdown") {
		t.Fatalf("markdown %s", raw)
	}
	ai, _ := http.Get(h.srv.URL + "/api/admin/ai/feedback")
	raw, _ = io.ReadAll(ai.Body)
	ai.Body.Close()
	if !strings.Contains(string(raw), pid) {
		t.Fatalf("ai digest %s", raw)
	}
}

func TestAdminPatchAndBulk(t *testing.T) {
	h := newHarness(t, nil)
	_, pid, _ := h.create()
	other := h.do("POST", "/api/feedback/tickets", "", ticketBody(uuid2), nil).body["ticket"].(map[string]any)["id"].(string)

	for name, body := range map[string]map[string]any{
		"status":    {"status": "lost"},
		"priority":  {"priority": 9},
		"tags":      {"tags": []string{"<b>"}},
		"many tags": {"tags": []string{"a", "b", "c", "d", "e", "f", "g", "h", "i"}},
		"duplicate": {"duplicateOf": "nope"},
	} {
		if r := h.do("PATCH", "/api/admin/feedback/tickets/"+pid, "", body, nil); r.status != http.StatusUnprocessableEntity {
			t.Errorf("%s: %d", name, r.status)
		}
	}
	r := h.do("PATCH", "/api/admin/feedback/tickets/"+pid, "", map[string]any{"priority": 4, "tags": []string{" Audio ", "audio", "Crash"}, "duplicateOf": other}, nil)
	if r.status != http.StatusNoContent {
		t.Fatalf("patch %d %v", r.status, r.body)
	}
	d := h.do("GET", "/api/admin/feedback/tickets/"+pid, "", nil, nil).body
	if fmt.Sprint(d["tags"]) != "[audio crash]" || d["priority"].(float64) != 4 || d["duplicateOf"] != other {
		t.Fatalf("patched %v", d)
	}
	r = h.do("POST", "/api/admin/feedback/bulk", "", map[string]any{"ids": []string{pid, other, "fb_zzzzzzzzzzzzzzzz"}, "status": StatusDone}, nil)
	if r.status != 200 || r.body["updated"].(float64) != 2 {
		t.Fatalf("bulk %d %v", r.status, r.body)
	}
	if s := h.do("GET", "/api/admin/feedback/summary", "", nil, nil).body["summary"].(map[string]any); s["open"].(float64) != 0 {
		t.Fatalf("after bulk %v", s)
	}
	if r := h.do("PATCH", "/api/admin/feedback/tickets/fb_aaaaaaaaaaaaaaaa", "", map[string]any{"priority": 1}, nil); r.status != http.StatusNotFound {
		t.Fatalf("missing ticket %d", r.status)
	}
}

func TestAdminAttachmentHeaders(t *testing.T) {
	h := newHarness(t, nil)
	tok, pid, mid := h.create()
	up := h.upload(tok, pid, mid, uuid1, pngBytes(t, 50, 40, false), nil)
	aid := int64(up.body["attachment"].(map[string]any)["id"].(float64))

	for _, q := range []string{"", "?thumb=1"} {
		res, err := http.Get(fmt.Sprintf("%s/api/admin/feedback/attachments/%d%s", h.srv.URL, aid, q))
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(res.Body)
		res.Body.Close()
		want := "image/png"
		if q != "" {
			want = "image/jpeg"
		}
		if res.StatusCode != 200 || res.Header.Get("Content-Type") != want || len(body) == 0 {
			t.Fatalf("%s: %d %s", q, res.StatusCode, res.Header.Get("Content-Type"))
		}
		if res.Header.Get("X-Content-Type-Options") != "nosniff" || !strings.Contains(res.Header.Get("Content-Security-Policy"), "sandbox") {
			t.Fatalf("%s: missing hardening headers %v", q, res.Header)
		}
	}
	if r := h.do("GET", "/api/admin/feedback/attachments/999", "", nil, nil); r.status != http.StatusNotFound {
		t.Fatalf("missing: %d", r.status)
	}
}

func TestAdminTemplatesAndBlock(t *testing.T) {
	h := newHarness(t, nil)
	if err := h.svc.SeedTemplates(context.Background()); err != nil {
		t.Fatal(err)
	}
	_ = h.svc.SeedTemplates(context.Background()) // a second start adds nothing
	r := h.do("GET", "/api/admin/feedback/templates", "", nil, nil)
	if n := len(r.body["templates"].([]any)); n != len(defaultTemplates) {
		t.Fatalf("seeded %d", n)
	}
	if r := h.do("POST", "/api/admin/feedback/templates", "", map[string]any{"name": "Malo", "body": "Hola {{nombr}}"}, nil); r.status != http.StatusUnprocessableEntity {
		t.Fatalf("bad template %d", r.status)
	}
	r = h.do("POST", "/api/admin/feedback/templates", "", map[string]any{"name": "Nuevo", "kind": "thanks", "body": "Gracias {{nombre}}"}, nil)
	id := int64(r.body["template"].(map[string]any)["id"].(float64))
	if r := h.do("PUT", fmt.Sprintf("/api/admin/feedback/templates/%d", id), "", map[string]any{"name": "Nuevo", "body": "Gracias {{nombre|crack}}"}, nil); r.status != 200 {
		t.Fatalf("update %d", r.status)
	}
	if r := h.do("DELETE", fmt.Sprintf("/api/admin/feedback/templates/%d", id), "", nil, nil); r.status != http.StatusNoContent {
		t.Fatalf("delete %d", r.status)
	}

	tok, pid, _ := h.create()
	if r := h.do("POST", "/api/admin/feedback/tickets/"+pid+"/block", "", map[string]any{"blocked": true}, nil); r.status != http.StatusNoContent {
		t.Fatalf("block %d", r.status)
	}
	before := len(h.repo.tickets)
	h.do("POST", "/api/feedback/tickets", tok, ticketBody(uuid3), nil)
	if len(h.repo.tickets) != before {
		t.Fatal("blocked sender still files tickets")
	}
	if d := h.do("GET", "/api/admin/feedback/tickets/"+pid, "", nil, nil).body; d["reporter"].(map[string]any)["blocked"] != true {
		t.Fatalf("detail %v", d["reporter"])
	}
	if r := h.do("DELETE", "/api/admin/feedback/tickets/"+pid, "", nil, nil); r.status != http.StatusNoContent {
		t.Fatalf("admin delete %d", r.status)
	}
}
