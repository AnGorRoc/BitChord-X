package feedback

// What the dashboard reads and writes. The dashboard addresses tickets by their public ID, like
// the apps; reporters are never exposed, only reached through one of their tickets.

// AdminFilter narrows the dashboard's ticket list.
type AdminFilter struct {
	// Status is a status, or "open" (not closed), "closed", "unread" (new activity from the
	// person) or "" for everything.
	Status   string
	Kind     string
	Platform string
	Version  string
	Tag      string
	// Query matches the title and every message, case-insensitively.
	Query string
	// Cursor is the Next of the previous page.
	Cursor string
	Limit  int
}

// AdminTicket is a ticket as the dashboard lists it.
type AdminTicket struct {
	Ticket
	Priority    int      `json:"priority"`
	Tags        []string `json:"tags"`
	UnreadAdmin bool     `json:"unreadAdmin"`
	Messages    int      `json:"messageCount"`
	Attachments int      `json:"attachmentCount"`
	// Snippet is the start of the latest message, and LastAuthor who wrote it.
	Snippet    string `json:"snippet"`
	LastAuthor string `json:"lastAuthor"`
}

// AdminPage is one page of the list.
type AdminPage struct {
	Items []AdminTicket `json:"items"`
	Next  string        `json:"next,omitempty"`
}

// AdminSummary is what the sidebar badge and the filters need.
type AdminSummary struct {
	Total     int            `json:"total"`
	Open      int            `json:"open"`
	Unread    int            `json:"unread"`
	ByStatus  map[string]int `json:"byStatus"`
	ByKind    map[string]int `json:"byKind"`
	ThumbsUp  int            `json:"thumbsUp"`
	ThumbsDn  int            `json:"thumbsDown"`
	Versions  []string       `json:"versions"`
	Tags      []string       `json:"tags"`
	DiskBytes int64          `json:"diskBytes"`
}

// AdminDetail is one ticket with everything about it.
type AdminDetail struct {
	AdminTicket
	Locale      string        `json:"locale"`
	Diagnostics Diagnostics   `json:"diagnostics"`
	DuplicateOf string        `json:"duplicateOf,omitempty"`
	Reporter    AdminReporter `json:"reporter"`
	Thread      []Message     `json:"thread"`
	Errors      []LinkedError `json:"errors"`
}

// AdminReporter is what the dashboard may know about the person behind a ticket.
type AdminReporter struct {
	Blocked bool  `json:"blocked"`
	Tickets int   `json:"tickets"`
	Since   int64 `json:"sinceMs"`
}

// LinkedError is a recent error report from the same install (only when the person shared
// their install ID, which needs telemetry on Full).
type LinkedError struct {
	ID         int64  `json:"id"`
	CreatedAt  int64  `json:"createdAtMs"`
	ErrorType  string `json:"errorType"`
	Message    string `json:"errorMessage"`
	AppVersion string `json:"appVersion"`
}

// AdminPatch changes a ticket; nil fields stay as they are.
type AdminPatch struct {
	Status      *string   `json:"status"`
	Priority    *int      `json:"priority"`
	Tags        *[]string `json:"tags"`
	DuplicateOf *string   `json:"duplicateOf"`
	UnreadAdmin *bool     `json:"unreadAdmin"`
}

// Template is a saved reply.
type Template struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	Kind      string `json:"kind"`
	Body      string `json:"body"`
	UpdatedAt int64  `json:"updatedAtMs"`
}

// Priorities: 0 none, 1 low, 2 normal, 3 high, 4 urgent.
const maxPriority = 4

var defaultTemplates = []Template{
	{Name: "Gracias", Kind: MsgThanks, Body: "¡Gracias, {{nombre|crack}}! Tu reporte nos ayuda un montón a mejorar BitChord."},
	{Name: "Necesito más info", Kind: MsgText, Body: "Hola {{nombre}}, gracias por avisar. ¿Podrías contarme los pasos exactos para que pase y, si puedes, mandar una captura? Estás en la versión {{version}} ({{plataforma}})."},
	{Name: "Arreglado", Kind: MsgText, Body: "¡Listo, {{nombre}}! \"{{titulo}}\" quedó arreglado y llega en la próxima versión. Cuando actualices, cuéntame si se resolvió."},
	{Name: "No se puede reproducir", Kind: MsgText, Body: "Hola {{nombre}}, intenté reproducir \"{{titulo}}\" y no lo consigo. ¿Te sigue pasando en la versión {{version}}?"},
	{Name: "Idea anotada", Kind: MsgThanks, Body: "¡Qué buena idea, {{nombre}}! La anoté para una próxima versión. Gracias por tomarte el tiempo."},
}
