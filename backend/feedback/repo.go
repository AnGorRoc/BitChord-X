package feedback

import (
	"context"
	"time"
)

// Repo is where tickets live. PostgreSQL in production (pgRepo); memory when the server runs
// without a database, which is how it is tried locally (memRepo). Both pass the same contract
// tests, see repo_contract_test.go.
//
// Every change to a reporter's tickets, from either side, bumps that reporter's InboxVersion,
// which is the ETag the app's inbox poll is answered with: an unchanged version is a 304.
type Repo interface {
	Migrate(ctx context.Context) error

	// Reporter finds a reporter by the SHA-256 of their token, or ErrNotFound.
	Reporter(ctx context.Context, tokenHash string) (Reporter, error)
	CreateReporter(ctx context.Context, tokenHash, platform, locale string) (Reporter, error)
	TouchReporter(ctx context.Context, id int64) error

	// CreateTicket stores a ticket and its first message (the description). It is idempotent on
	// (reporter, ClientTicketID): a retry returns the ticket made the first time and false.
	CreateTicket(ctx context.Context, in NewTicket, publicID string) (Ticket, Message, bool, error)
	// Ticket finds one of the reporter's tickets, or ErrNotFound (also for someone else's).
	Ticket(ctx context.Context, reporterID int64, publicID string) (Ticket, error)

	// AddUserMessage adds the person's reply, idempotent on (ticket, clientMessageID).
	AddUserMessage(ctx context.Context, t Ticket, clientMessageID, body string) (Message, bool, error)
	// AddAdminMessage adds the developer's message; body is stored as written, variables and all.
	AddAdminMessage(ctx context.Context, ticketID int64, kind, body string) (Message, error)
	// UserMessage finds a message the person wrote in this ticket, or ErrNotFound.
	UserMessage(ctx context.Context, ticketID, messageID int64) (Message, error)
	// SetRating records whether the person says it was solved. Saying no reopens a closed ticket.
	SetRating(ctx context.Context, t Ticket, clientMessageID string, resolved bool, comment string) (Message, bool, error)
	SetStatus(ctx context.Context, ticketID int64, status string) error
	// MarkRead marks the developer's messages up to upTo as read by the person.
	MarkRead(ctx context.Context, t Ticket, upTo int64) error

	// Attachment finds an attachment by the client's ID for it, or ErrNotFound.
	Attachment(ctx context.Context, messageID int64, clientID string) (Attachment, error)
	TicketAttachmentBytes(ctx context.Context, ticketID int64) (int64, error)
	// AddAttachment records a stored file, enforcing lim (ErrLimit). Idempotent on
	// (message, ClientAttachmentID).
	AddAttachment(ctx context.Context, in NewAttachment, lim Limits) (Attachment, bool, error)
	TotalAttachmentBytes(ctx context.Context) (int64, error)

	Inbox(ctx context.Context, reporterID, sinceMessageID int64, limit int) (Inbox, error)
	// Thread is every message of a ticket, oldest first, with its attachments.
	Thread(ctx context.Context, ticketID int64) ([]Message, error)

	// DeleteTicket removes a ticket with everything in it and returns the SHA-256 of the files
	// it referenced, for the caller to remove those no other ticket uses.
	DeleteTicket(ctx context.Context, ticketID int64) ([]string, error)
	SHAInUse(ctx context.Context, sha string) (bool, error)
	// PurgeClosedAttachments drops the attachments of tickets closed before cutoff (the text
	// stays) and returns their SHA-256.
	PurgeClosedAttachments(ctx context.Context, cutoff time.Time) ([]string, error)
}
