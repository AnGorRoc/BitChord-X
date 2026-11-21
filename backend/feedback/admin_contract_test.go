package feedback

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"
)

func runAdminContract(t *testing.T, newRepo func(*testing.T) Repo) {
	ctx := context.Background()

	// seed makes six tickets from two reporters, oldest first, a few ms apart so updated_at
	// orders them.
	seed := func(t *testing.T) (Repo, []Ticket) {
		r := newRepo(t)
		if err := r.Migrate(ctx); err != nil {
			t.Fatal(err)
		}
		a, _ := r.CreateReporter(ctx, hashToken("a"), "android", "es")
		b, _ := r.CreateReporter(ctx, hashToken("b"), "pc", "en")
		var out []Ticket
		for i := 0; i < 6; i++ {
			rep, kind, plat, ver := a, KindBug, "android", "2.0"
			if i%2 == 1 {
				rep, kind, plat, ver = b, KindIdea, "pc", "2.1"
			}
			tk, _, _, err := r.CreateTicket(ctx, NewTicket{
				ReporterID: rep.ID, ClientTicketID: fmt.Sprint("c", i), Kind: kind, Title: fmt.Sprint("Título ", i),
				Body: fmt.Sprint("Cuerpo número ", i, " con 100% de detalle"), Platform: plat, AppVersion: ver, Locale: "es",
				Diagnostics: Diagnostics{DeviceModel: "Pixel", TelemetryLevel: "full", InstallID: "6f1c3a1e-2b9d-4c55-8a77-3e2f1b0c9d11"},
			}, fmt.Sprintf("fb_%016d", i))
			if err != nil {
				t.Fatal(err)
			}
			out = append(out, tk)
			time.Sleep(3 * time.Millisecond)
		}
		return r, out
	}
	ids := func(p AdminPage) []string {
		var s []string
		for _, it := range p.Items {
			s = append(s, it.PublicID)
		}
		return s
	}

	t.Run("list, filter and page", func(t *testing.T) {
		r, tks := seed(t)
		all, err := r.AdminList(ctx, AdminFilter{Limit: 4})
		if err != nil {
			t.Fatal(err)
		}
		if len(all.Items) != 4 || all.Items[0].PublicID != tks[5].PublicID || all.Next == "" {
			t.Fatalf("first page %v next %q", ids(all), all.Next)
		}
		it := all.Items[0]
		if it.Messages != 1 || it.LastAuthor != AuthorUser || it.Snippet == "" || !it.UnreadAdmin || it.Tags == nil {
			t.Fatalf("item %+v", it)
		}
		rest, err := r.AdminList(ctx, AdminFilter{Limit: 4, Cursor: all.Next})
		if err != nil || len(rest.Items) != 2 || rest.Next != "" || rest.Items[1].PublicID != tks[0].PublicID {
			t.Fatalf("second page %v %q %v", ids(rest), rest.Next, err)
		}
		if p, _ := r.AdminList(ctx, AdminFilter{Limit: 10, Kind: KindIdea}); len(p.Items) != 3 {
			t.Fatalf("kind %v", ids(p))
		}
		if p, _ := r.AdminList(ctx, AdminFilter{Limit: 10, Platform: "pc", Version: "2.1"}); len(p.Items) != 3 {
			t.Fatalf("platform+version %v", ids(p))
		}
		if p, _ := r.AdminList(ctx, AdminFilter{Limit: 10, Query: "número 4"}); len(p.Items) != 1 || p.Items[0].PublicID != tks[4].PublicID {
			t.Fatalf("search body %v", ids(p))
		}
		if p, _ := r.AdminList(ctx, AdminFilter{Limit: 10, Query: "TÍTULO 2"}); len(p.Items) != 1 {
			t.Fatalf("search title, any case %v", ids(p))
		}
		if p, _ := r.AdminList(ctx, AdminFilter{Limit: 10, Query: "100%"}); len(p.Items) != 6 {
			t.Fatalf("literal %% %v", ids(p))
		}
		if p, _ := r.AdminList(ctx, AdminFilter{Limit: 10, Query: "1_0"}); len(p.Items) != 0 {
			t.Fatalf("_ is not a wildcard %v", ids(p))
		}
		if p, _ := r.AdminList(ctx, AdminFilter{Limit: 10, Cursor: "garbage"}); len(p.Items) != 0 {
			t.Fatal("bad cursor")
		}
	})

	t.Run("update, status messages and summary", func(t *testing.T) {
		r, tks := seed(t)
		tk := tks[1]
		v0, _ := r.Reporter(ctx, hashToken("b"))
		done, prio, tags, unread, dup := StatusDone, 3, []string{"audio", "crash"}, false, tks[3].PublicID
		if err := r.AdminUpdate(ctx, tk.ID, AdminPatch{Status: &done, Priority: &prio, Tags: &tags, UnreadAdmin: &unread, DuplicateOf: &dup}); err != nil {
			t.Fatal(err)
		}
		d, err := r.AdminDetail(ctx, tk.PublicID)
		if err != nil {
			t.Fatal(err)
		}
		if d.Status != StatusDone || d.ClosedAt == 0 || d.Priority != 3 || len(d.Tags) != 2 || d.UnreadAdmin || d.DuplicateOf != tks[3].PublicID {
			t.Fatalf("detail %+v", d.AdminTicket)
		}
		if d.Locale != "es" || d.Diagnostics.DeviceModel != "Pixel" || d.Reporter.Tickets != 3 || d.Reporter.Blocked || d.Errors == nil {
			t.Fatalf("detail extras %+v %+v", d.Diagnostics, d.Reporter)
		}
		last := d.Thread[len(d.Thread)-1]
		if last.Author != AuthorAdmin || last.Kind != MsgStatus || last.Body != StatusDone {
			t.Fatalf("status message %+v", last)
		}
		if v, _ := r.Reporter(ctx, hashToken("b")); v.InboxVersion <= v0.InboxVersion {
			t.Fatal("a status change must reach the app's inbox")
		}
		// The same status again posts nothing.
		if err := r.AdminUpdate(ctx, tk.ID, AdminPatch{Status: &done}); err != nil {
			t.Fatal(err)
		}
		if d2, _ := r.AdminDetail(ctx, tk.PublicID); len(d2.Thread) != len(d.Thread) {
			t.Fatal("unchanged status posted a message")
		}
		self := tk.PublicID
		if err := r.AdminUpdate(ctx, tk.ID, AdminPatch{DuplicateOf: &self}); !errors.Is(err, ErrNotFound) {
			t.Fatalf("duplicate of itself: %v", err)
		}
		if p, _ := r.AdminList(ctx, AdminFilter{Limit: 10, Tag: "crash"}); len(p.Items) != 1 {
			t.Fatal("tag filter")
		}
		if p, _ := r.AdminList(ctx, AdminFilter{Limit: 10, Status: "closed"}); len(p.Items) != 1 {
			t.Fatal("closed filter")
		}
		if p, _ := r.AdminList(ctx, AdminFilter{Limit: 10, Status: "open"}); len(p.Items) != 5 {
			t.Fatal("open filter")
		}
		if p, _ := r.AdminList(ctx, AdminFilter{Limit: 10, Status: "unread"}); len(p.Items) != 5 {
			t.Fatal("unread filter")
		}
		s, err := r.AdminSummary(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if s.Total != 6 || s.Open != 5 || s.Unread != 5 || s.ByStatus[StatusDone] != 1 || s.ByKind[KindBug] != 3 || len(s.Versions) != 2 || len(s.Tags) != 2 {
			t.Fatalf("summary %+v", s)
		}
		if _, err := r.AdminTicket(ctx, tks[0].PublicID); err != nil {
			t.Fatal(err)
		}
		if _, err := r.AdminDetail(ctx, "fb_nope"); !errors.Is(err, ErrNotFound) {
			t.Fatal("missing detail")
		}
	})

	t.Run("block and templates", func(t *testing.T) {
		r, tks := seed(t)
		if err := r.SetReporterBlocked(ctx, tks[0].ID, true); err != nil {
			t.Fatal(err)
		}
		if rep, _ := r.Reporter(ctx, hashToken("a")); !rep.Blocked {
			t.Fatal("not blocked")
		}
		if rep, _ := r.Reporter(ctx, hashToken("b")); rep.Blocked {
			t.Fatal("blocked the wrong person")
		}
		tp, err := r.SaveTemplate(ctx, Template{Name: "Hola", Kind: MsgText, Body: "Hola {{nombre}}"})
		if err != nil || tp.ID == 0 {
			t.Fatal(err)
		}
		tp.Body = "Chau {{nombre}}"
		if saved, err := r.SaveTemplate(ctx, tp); err != nil || saved.Body != "Chau {{nombre}}" {
			t.Fatal(err)
		}
		if _, err := r.SaveTemplate(ctx, Template{ID: 99999, Name: "x", Body: "y"}); !errors.Is(err, ErrNotFound) {
			t.Fatalf("update missing: %v", err)
		}
		list, _ := r.Templates(ctx)
		if len(list) != 1 {
			t.Fatalf("%+v", list)
		}
		if err := r.DeleteTemplate(ctx, tp.ID); err != nil {
			t.Fatal(err)
		}
		if err := r.DeleteTemplate(ctx, tp.ID); !errors.Is(err, ErrNotFound) {
			t.Fatal("delete twice")
		}
	})
}
