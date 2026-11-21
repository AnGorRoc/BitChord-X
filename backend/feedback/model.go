// Package feedback is how people send the developer bug reports, ideas and questions from the
// apps, with screenshots, and how they get an answer back.
//
// There are no user accounts on this server, so a person is known only by a reporter token the
// server hands out with their first report. The token is a 256-bit random value the client keeps;
// the server stores only its SHA-256, so a copy of the database cannot be used to read anyone's
// replies or write as them. The token is unrelated to the telemetry install ID: feedback works
// with telemetry off, and the two are tied together only when the person ticks "attach technical
// info" while their telemetry is on Full.
//
// The developer's replies are stored as written, with variables like {{nombre}} left in place:
// the app fills them from what it knows locally, so the server never learns the person's name.
package feedback

import (
	"errors"
	"time"
)

// Kinds a ticket can be.
const (
	KindBug      = "bug"
	KindIdea     = "idea"
	KindQuestion = "question"
	KindOther    = "other"
)

var kinds = map[string]bool{KindBug: true, KindIdea: true, KindQuestion: true, KindOther: true}

// Statuses a ticket moves through. Only the dashboard changes them, except that a person who
// says a closed ticket is not solved reopens it (see Repo.SetRating).
const (
	StatusNew        = "new"
	StatusReviewed   = "reviewed"
	StatusNeedInfo   = "need_info"
	StatusInProgress = "in_progress"
	StatusDone       = "done"
	StatusWontFix    = "wontfix"
	StatusDuplicate  = "duplicate"
)

// Statuses lists every status, in the order the dashboard offers them.
var Statuses = []string{StatusNew, StatusReviewed, StatusNeedInfo, StatusInProgress, StatusDone, StatusWontFix, StatusDuplicate}

// IsClosed reports whether a status ends the conversation (and starts the attachment retention clock).
func IsClosed(status string) bool {
	return status == StatusDone || status == StatusWontFix || status == StatusDuplicate
}

// Who wrote a message.
const (
	AuthorUser  = "user"
	AuthorAdmin = "admin"
)

// What a message is. The first message of a ticket is the person's description.
const (
	MsgText = "message"
	// The person's verdict on a closed ticket, with their comment as the body.
	MsgResolved   = "resolved"
	MsgUnresolved = "unresolved"
	MsgThanks     = "thanks"
	MsgStatus     = "status"
)

// What an attachment holds.
const (
	AttachImage = "image"
	AttachLog   = "log"
)

var (
	ErrNotFound = errors.New("not found")
	// ErrLimit is an attachment over the per-message count or per-ticket size.
	ErrLimit = errors.New("attachment limit reached")
)

// Reporter is one person (one app install that has sent feedback).
type Reporter struct {
	ID           int64
	Blocked      bool
	InboxVersion int64
}

// Diagnostics is the technical info a person chose to attach. Every field is short and fixed:
// there is deliberately no free-form map, so nothing else can ride along.
type Diagnostics struct {
	DeviceModel    string `json:"deviceModel,omitempty"`
	OSVersion      string `json:"osVersion,omitempty"`
	ABI            string `json:"abi,omitempty"`
	Screen         string `json:"screen,omitempty"`
	TelemetryLevel string `json:"telemetryLevel,omitempty"`
	// InstallID is kept only when TelemetryLevel is "full", so the report can be matched with
	// the error reports that install already sends.
	InstallID string `json:"installId,omitempty"`
}

// NewTicket is a validated report about to be stored.
type NewTicket struct {
	ReporterID     int64
	ClientTicketID string
	Kind           string
	Title          string
	Body           string
	Platform       string
	AppVersion     string
	Locale         string
	Diagnostics    Diagnostics
}

// Ticket is one report and its state.
type Ticket struct {
	ID           int64  `json:"-"`
	PublicID     string `json:"id"`
	ReporterID   int64  `json:"-"`
	Kind         string `json:"kind"`
	Title        string `json:"title"`
	Status       string `json:"status"`
	Platform     string `json:"platform"`
	AppVersion   string `json:"appVersion"`
	Satisfaction int    `json:"satisfaction"`
	CreatedAt    int64  `json:"createdAtMs"`
	UpdatedAt    int64  `json:"updatedAtMs"`
	ClosedAt     int64  `json:"closedAtMs,omitempty"`
}

// Message is one entry in a ticket's thread.
type Message struct {
	ID          int64        `json:"id"`
	TicketID    int64        `json:"-"`
	Author      string       `json:"author"`
	Kind        string       `json:"kind"`
	Body        string       `json:"body"`
	CreatedAt   int64        `json:"createdAtMs"`
	ReadAt      int64        `json:"readAtMs,omitempty"`
	Attachments []Attachment `json:"attachments"`
}

// NewAttachment is a processed file about to be recorded.
type NewAttachment struct {
	TicketID           int64
	MessageID          int64
	ClientAttachmentID string
	Kind               string
	Mime               string
	SHA256             string
	Bytes              int64
	Width, Height      int
	HasThumb           bool
}

// Attachment is a stored file. The file itself lives on disk, named by its SHA-256.
type Attachment struct {
	ID                 int64  `json:"id"`
	TicketID           int64  `json:"-"`
	MessageID          int64  `json:"-"`
	ClientAttachmentID string `json:"clientId"`
	Kind               string `json:"kind"`
	Mime               string `json:"mime"`
	SHA256             string `json:"-"`
	Bytes              int64  `json:"bytes"`
	Width              int    `json:"width,omitempty"`
	Height             int    `json:"height,omitempty"`
	HasThumb           bool   `json:"-"`
}

// Limits bounds what one ticket may hold.
type Limits struct {
	MaxPerMessage  int
	MaxTicketBytes int64
}

// InboxTicket is a ticket as the app's "My reports" list shows it.
type InboxTicket struct {
	Ticket
	Unread int `json:"unread"`
}

// Inbox is what the app polls: its tickets and the developer's messages it has not seen.
type Inbox struct {
	Version  int64         `json:"version"`
	Tickets  []InboxTicket `json:"tickets"`
	Messages []InboxMsg    `json:"messages"`
}

// InboxMsg is a developer message with the ticket it belongs to.
type InboxMsg struct {
	TicketID string `json:"ticketId"`
	Message
}

func ms(t time.Time) int64 {
	if t.IsZero() {
		return 0
	}
	return t.UnixMilli()
}

func ratingKind(resolved bool) string {
	if resolved {
		return MsgResolved
	}
	return MsgUnresolved
}
