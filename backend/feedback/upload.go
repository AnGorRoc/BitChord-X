package feedback

import (
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// handleUpload stores one attachment of one of the person's messages:
//
//	PUT /api/feedback/tickets/{tid}/messages/{mid}/attachments/{aid}
//
// with the file as the raw body (no multipart) and its Content-Length. {aid} is a UUID the app
// picks per file, so a retried upload is recognised and answered with what was stored the first
// time, without reading or processing it again.
func (s *Service) handleUpload(w http.ResponseWriter, r *http.Request) {
	if s.limited(w, r, s.opt.UploadLimiter) {
		return
	}
	rep, t, ok := s.ticket(w, r)
	if !ok {
		return
	}
	ctx := r.Context()
	clientID := strings.ToLower(r.PathValue("aid"))
	if !validUUID(clientID) {
		writeErr(w, http.StatusUnprocessableEntity, "invalid_id", "The attachment ID must be a UUID.")
		return
	}
	mid, err := strconv.ParseInt(r.PathValue("mid"), 10, 64)
	if err != nil || mid <= 0 {
		writeErr(w, http.StatusNotFound, "not_found", "No such message.")
		return
	}
	if _, err := s.repo.UserMessage(ctx, t.ID, mid); errors.Is(err, ErrNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "No such message.")
		return
	} else if err != nil {
		s.serverErr(w, "upload message", err)
		return
	}
	if a, err := s.repo.Attachment(ctx, mid, clientID); err == nil {
		writeJSON(w, http.StatusOK, map[string]any{"attachment": a})
		return
	} else if !errors.Is(err, ErrNotFound) {
		s.serverErr(w, "upload lookup", err)
		return
	}

	// Refuse what cannot fit before reading a byte of it.
	if r.ContentLength < 0 {
		writeErr(w, http.StatusLengthRequired, "length_required", "Content-Length is required.")
		return
	}
	if r.ContentLength == 0 || r.ContentLength > s.opt.MaxUploadBytes {
		writeErr(w, http.StatusRequestEntityTooLarge, "file_too_large", "The file must be 1 byte to "+strconv.FormatInt(s.opt.MaxUploadBytes>>20, 10)+" MB.")
		return
	}
	if rep.Blocked {
		// Accepted and dropped unread, like the rest of a blocked sender's reports.
		writeJSON(w, http.StatusCreated, map[string]any{"attachment": Attachment{ClientAttachmentID: clientID, Kind: AttachImage, Mime: "image/jpeg", Bytes: r.ContentLength}})
		return
	}
	if used, err := s.repo.TicketAttachmentBytes(ctx, t.ID); err != nil {
		s.serverErr(w, "upload size", err)
		return
	} else if used >= s.opt.Limits.MaxTicketBytes {
		writeErr(w, http.StatusRequestEntityTooLarge, "ticket_full", "This report cannot hold more attachments.")
		return
	}
	if total, err := s.repo.TotalAttachmentBytes(ctx); err != nil {
		s.serverErr(w, "upload disk", err)
		return
	} else if total+r.ContentLength > s.opt.MaxDiskBytes {
		writeErr(w, http.StatusInsufficientStorage, "storage_full", "The server cannot take attachments right now. The text was saved.")
		return
	}

	select {
	case s.receiving <- struct{}{}:
		defer func() { <-s.receiving }()
	default:
		w.Header().Set("Retry-After", "10")
		writeErr(w, http.StatusServiceUnavailable, "busy", "The server is busy. Try again shortly.")
		return
	}
	// The server's 20 s read timeout is meant for small JSON bodies; a screenshot over a weak
	// mobile connection takes longer.
	_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(2 * time.Minute))
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, r.ContentLength))
	if err != nil || int64(len(data)) != r.ContentLength {
		writeErr(w, http.StatusBadRequest, "incomplete_body", "The upload did not arrive complete.")
		return
	}

	select {
	case s.processing <- struct{}{}:
	case <-ctx.Done():
		return
	case <-time.After(15 * time.Second):
		w.Header().Set("Retry-After", "10")
		writeErr(w, http.StatusServiceUnavailable, "busy", "The server is busy. Try again shortly.")
		return
	}
	p, err := processUpload(data)
	<-s.processing
	switch {
	case errors.Is(err, errUnsupported):
		writeErr(w, http.StatusUnsupportedMediaType, "unsupported_type", "Only PNG, JPEG, WebP images and plain-text logs are accepted.")
		return
	case errors.Is(err, errTooLarge):
		writeErr(w, http.StatusRequestEntityTooLarge, "file_too_large", "The image or log is too large.")
		return
	case errors.Is(err, errCorrupt):
		writeErr(w, http.StatusUnprocessableEntity, "corrupt_file", "The file could not be read.")
		return
	case err != nil:
		s.serverErr(w, "process upload", err)
		return
	}

	sha := contentSHA(p.data)
	s.files.mu.RLock()
	err = s.files.write(sha, p.ext, p.data, p.thumb)
	var a Attachment
	var created bool
	if err == nil {
		a, created, err = s.repo.AddAttachment(ctx, NewAttachment{
			TicketID: t.ID, MessageID: mid, ClientAttachmentID: clientID,
			Kind: p.kind, Mime: p.mime, SHA256: sha, Bytes: int64(len(p.data)),
			Width: p.width, Height: p.height, HasThumb: p.thumb != nil,
		}, s.opt.Limits)
	}
	s.files.mu.RUnlock()
	if err != nil || !created {
		// The file written above may now belong to nothing (refused, or a retry that lost the race).
		s.dropUnused(ctx, []string{sha})
	}
	switch {
	case errors.Is(err, ErrLimit):
		writeErr(w, http.StatusRequestEntityTooLarge, "ticket_full", "This message or report cannot hold more attachments.")
	case err != nil:
		s.serverErr(w, "store upload", err)
	case !created:
		writeJSON(w, http.StatusOK, map[string]any{"attachment": a})
	default:
		s.opt.OnEvent("feedback", map[string]any{"event": "attachment", "id": t.PublicID})
		writeJSON(w, http.StatusCreated, map[string]any{"attachment": a})
	}
}
