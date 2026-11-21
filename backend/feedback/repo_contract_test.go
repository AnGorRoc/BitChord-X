package feedback

import (
	"context"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// The same behaviour is required of both repositories; the in-memory one always runs, the
// PostgreSQL one when FEEDBACK_TEST_DATABASE_URL names a database it may create schemas in.

func TestMemRepoContract(t *testing.T) {
	runContract(t, func(t *testing.T) Repo { return NewMemRepo() })
}

func TestPGRepoContract(t *testing.T) {
	url := os.Getenv("FEEDBACK_TEST_DATABASE_URL")
	if url == "" {
		t.Skip("FEEDBACK_TEST_DATABASE_URL not set")
	}
	runContract(t, func(t *testing.T) Repo {
		ctx := context.Background()
		schema := fmt.Sprintf("fbtest_%d", time.Now().UnixNano())
		admin, err := pgxpool.New(ctx, url)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
			t.Fatal(err)
		}
		cfg, err := pgxpool.ParseConfig(url)
		if err != nil {
			t.Fatal(err)
		}
		cfg.ConnConfig.RuntimeParams["search_path"] = schema
		pool, err := pgxpool.NewWithConfig(ctx, cfg)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			pool.Close()
			_, _ = admin.Exec(ctx, "DROP SCHEMA "+schema+" CASCADE")
			admin.Close()
		})
		return NewPGRepo(pool)
	})
}

func runContract(t *testing.T, newRepo func(*testing.T) Repo) {
	ctx := context.Background()
	lim := Limits{MaxPerMessage: 2, MaxTicketBytes: 1000}

	setup := func(t *testing.T) (Repo, Reporter, Ticket, Message) {
		r := newRepo(t)
		if err := r.Migrate(ctx); err != nil {
			t.Fatalf("migrate: %v", err)
		}
		if err := r.Migrate(ctx); err != nil {
			t.Fatalf("migrate twice: %v", err)
		}
		rep, err := r.CreateReporter(ctx, hashToken("a"), "android", "es")
		if err != nil {
			t.Fatal(err)
		}
		tk, first, created, err := r.CreateTicket(ctx, NewTicket{
			ReporterID: rep.ID, ClientTicketID: "c1", Kind: KindBug, Title: "Crash", Body: "It crashes",
			Platform: "android", AppVersion: "1.0", Diagnostics: Diagnostics{DeviceModel: "Pixel"},
		}, "fb_aaaaaaaaaaaaaaaa")
		if err != nil || !created {
			t.Fatalf("create: %v %v", created, err)
		}
		return r, rep, tk, first
	}

	t.Run("ticket and reporter", func(t *testing.T) {
		r, rep, tk, first := setup(t)
		if tk.Status != StatusNew || tk.PublicID != "fb_aaaaaaaaaaaaaaaa" || tk.CreatedAt == 0 {
			t.Fatalf("ticket %+v", tk)
		}
		if first.Author != AuthorUser || first.Body != "It crashes" || first.Attachments == nil {
			t.Fatalf("first message %+v", first)
		}
		again, firstAgain, created, err := r.CreateTicket(ctx, NewTicket{ReporterID: rep.ID, ClientTicketID: "c1", Kind: KindIdea, Title: "x", Body: "y"}, "fb_bbbbbbbbbbbbbbbb")
		if err != nil || created || again.PublicID != tk.PublicID || firstAgain.ID != first.ID {
			t.Fatalf("retry should return the first ticket: %+v %v %v", again, created, err)
		}
		got, err := r.Reporter(ctx, hashToken("a"))
		if err != nil || got.ID != rep.ID {
			t.Fatalf("reporter lookup %+v %v", got, err)
		}
		if _, err := r.Reporter(ctx, hashToken("nobody")); !errors.Is(err, ErrNotFound) {
			t.Fatalf("unknown token: %v", err)
		}
		other, _ := r.CreateReporter(ctx, hashToken("b"), "pc", "")
		if _, err := r.Ticket(ctx, other.ID, tk.PublicID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("someone else's ticket must not be found: %v", err)
		}
		if _, err := r.Ticket(ctx, rep.ID, tk.PublicID); err != nil {
			t.Fatal(err)
		}
	})

	t.Run("messages are idempotent and bump the inbox", func(t *testing.T) {
		r, rep, tk, first := setup(t)
		v0 := mustReporter(t, r).InboxVersion
		m, created, err := r.AddUserMessage(ctx, tk, "m1", "More detail")
		if err != nil || !created {
			t.Fatal(err)
		}
		again, created, err := r.AddUserMessage(ctx, tk, "m1", "Different")
		if err != nil || created || again.ID != m.ID || again.Body != "More detail" {
			t.Fatalf("retry %+v %v %v", again, created, err)
		}
		if v := mustReporter(t, r).InboxVersion; v <= v0 {
			t.Fatalf("inbox version did not move: %d -> %d", v0, v)
		}
		if _, err := r.UserMessage(ctx, tk.ID, first.ID); err != nil {
			t.Fatal(err)
		}
		admin, err := r.AddAdminMessage(ctx, tk.ID, MsgText, "Hola {{nombre}}")
		if err != nil {
			t.Fatal(err)
		}
		if _, err := r.UserMessage(ctx, tk.ID, admin.ID); !errors.Is(err, ErrNotFound) {
			t.Fatal("the developer's message is not the person's to attach files to")
		}
		in, err := r.Inbox(ctx, rep.ID, 0, 50)
		if err != nil {
			t.Fatal(err)
		}
		if len(in.Tickets) != 1 || in.Tickets[0].Unread != 1 || len(in.Messages) != 1 || in.Messages[0].Body != "Hola {{nombre}}" || in.Messages[0].TicketID != tk.PublicID {
			t.Fatalf("inbox %+v", in)
		}
		if in2, _ := r.Inbox(ctx, rep.ID, admin.ID, 50); len(in2.Messages) != 0 {
			t.Fatalf("since cursor ignored: %+v", in2.Messages)
		}
		before := mustReporter(t, r).InboxVersion
		if err := r.MarkRead(ctx, tk, admin.ID); err != nil {
			t.Fatal(err)
		}
		in, _ = r.Inbox(ctx, rep.ID, 0, 50)
		if in.Tickets[0].Unread != 0 || in.Messages[0].ReadAt == 0 || in.Version <= before {
			t.Fatalf("after read %+v", in)
		}
		thread, err := r.Thread(ctx, tk.ID)
		if err != nil || len(thread) != 3 || thread[0].ID != first.ID || thread[2].Author != AuthorAdmin {
			t.Fatalf("thread %+v %v", thread, err)
		}
	})

	t.Run("attachments respect limits", func(t *testing.T) {
		r, _, tk, first := setup(t)
		add := func(client string, bytes int64, sha string) (Attachment, bool, error) {
			return r.AddAttachment(ctx, NewAttachment{TicketID: tk.ID, MessageID: first.ID, ClientAttachmentID: client,
				Kind: AttachImage, Mime: "image/png", SHA256: sha, Bytes: bytes, Width: 2, Height: 3, HasThumb: true}, lim)
		}
		a, created, err := add("a1", 400, "s1")
		if err != nil || !created || a.Width != 2 {
			t.Fatalf("%+v %v %v", a, created, err)
		}
		if b, created, err := add("a1", 400, "s1"); err != nil || created || b.ID != a.ID {
			t.Fatalf("retry %+v %v %v", b, created, err)
		}
		if _, _, err := add("a2", 700, "s2"); !errors.Is(err, ErrLimit) {
			t.Fatalf("ticket bytes: %v", err)
		}
		if _, _, err := add("a2", 500, "s2"); err != nil {
			t.Fatal(err)
		}
		if _, _, err := add("a3", 1, "s3"); !errors.Is(err, ErrLimit) {
			t.Fatalf("per-message count: %v", err)
		}
		if n, _ := r.TicketAttachmentBytes(ctx, tk.ID); n != 900 {
			t.Fatalf("ticket bytes %d", n)
		}
		if n, _ := r.TotalAttachmentBytes(ctx); n != 900 {
			t.Fatalf("total bytes %d", n)
		}
		if got, err := r.Attachment(ctx, first.ID, "a2"); err != nil || got.SHA256 != "s2" {
			t.Fatalf("%+v %v", got, err)
		}
		thread, _ := r.Thread(ctx, tk.ID)
		if len(thread[0].Attachments) != 2 {
			t.Fatalf("thread attachments %+v", thread[0].Attachments)
		}
		shas, err := r.DeleteTicket(ctx, tk.ID)
		if err != nil || len(shas) != 2 {
			t.Fatalf("delete %v %v", shas, err)
		}
		if used, _ := r.SHAInUse(ctx, "s1"); used {
			t.Fatal("sha still in use after delete")
		}
		if _, err := r.Thread(ctx, tk.ID); err != nil {
			t.Fatal(err)
		}
	})

	t.Run("rating and status", func(t *testing.T) {
		r, _, tk, first := setup(t)
		if err := r.SetStatus(ctx, tk.ID, StatusDone); err != nil {
			t.Fatal(err)
		}
		tk = mustTicket(t, r, tk)
		if tk.ClosedAt == 0 {
			t.Fatal("closing should stamp closedAt")
		}
		if _, _, err := r.AddAttachment(ctx, NewAttachment{TicketID: tk.ID, MessageID: first.ID, ClientAttachmentID: "x", Kind: AttachImage, Mime: "image/png", SHA256: "old", Bytes: 1}, lim); err != nil {
			t.Fatal(err)
		}
		if _, created, err := r.SetRating(ctx, tk, "r1", false, "Still broken"); err != nil || !created {
			t.Fatal(err)
		}
		tk = mustTicket(t, r, tk)
		if tk.Status != StatusReviewed || tk.ClosedAt != 0 || tk.Satisfaction != -1 {
			t.Fatalf("not solved should reopen: %+v", tk)
		}
		if err := r.SetStatus(ctx, tk.ID, StatusDone); err != nil {
			t.Fatal(err)
		}
		if _, _, err := r.SetRating(ctx, tk, "r2", true, ""); err != nil {
			t.Fatal(err)
		}
		tk = mustTicket(t, r, tk)
		if tk.Status != StatusDone || tk.Satisfaction != 1 {
			t.Fatalf("solved %+v", tk)
		}
		if shas, err := r.PurgeClosedAttachments(ctx, time.Now().Add(-time.Hour)); err != nil || len(shas) != 0 {
			t.Fatalf("recently closed must keep attachments: %v %v", shas, err)
		}
		shas, err := r.PurgeClosedAttachments(ctx, time.Now().Add(time.Hour))
		if err != nil || len(shas) != 1 || shas[0] != "old" {
			t.Fatalf("purge %v %v", shas, err)
		}
		if thread, _ := r.Thread(ctx, tk.ID); len(thread[0].Attachments) != 0 || thread[0].Body != "It crashes" {
			t.Fatal("purge drops files, keeps text")
		}
	})
}

func mustReporter(t *testing.T, r Repo) Reporter {
	t.Helper()
	rep, err := r.Reporter(context.Background(), hashToken("a"))
	if err != nil {
		t.Fatal(err)
	}
	return rep
}

func mustTicket(t *testing.T, r Repo, tk Ticket) Ticket {
	t.Helper()
	got, err := r.Ticket(context.Background(), tk.ReporterID, tk.PublicID)
	if err != nil {
		t.Fatal(err)
	}
	return got
}
