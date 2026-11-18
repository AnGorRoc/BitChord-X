package telemetry

import (
	"crypto/subtle"
	_ "embed"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
)

//go:embed dashboard.html
var dashboardHTML []byte

// NewAdminHandler builds the HTTP handler intended for the private LAN/Tailscale
// port (ADMIN_PORT, default 8001). When adminSecret is empty, no password is
// required; when set, HTTP Basic Auth or Bearer token authentication is enforced.
func NewAdminHandler(store *Store, adminSecret string) http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"ok":              true,
			"databaseEnabled": store.DatabaseEnabled(),
		})
	})

	mux.HandleFunc("GET /{$}", serveDashboard)
	mux.HandleFunc("GET /admin", serveDashboard)
	mux.HandleFunc("GET /admin/{$}", serveDashboard)

	mux.HandleFunc("GET /api/admin/overview", func(w http.ResponseWriter, r *http.Request) {
		rangeStr := r.URL.Query().Get("range")
		data, err := store.Overview(r.Context(), rangeStr)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, data)
	})

	mux.HandleFunc("GET /api/admin/errors", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		limit, _ := strconv.Atoi(q.Get("limit"))
		offset, _ := strconv.Atoi(q.Get("offset"))
		filter := ErrorFilter{
			Version:        strings.TrimSpace(q.Get("version")),
			Platform:       strings.TrimSpace(q.Get("platform")),
			ErrorType:      strings.TrimSpace(q.Get("errorType")),
			UnresolvedOnly: q.Get("unresolved") == "true" || q.Get("unresolved") == "1",
			Search:         strings.TrimSpace(q.Get("search")),
			Limit:          limit,
			Offset:         offset,
		}
		res, err := store.ListErrors(r.Context(), filter)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, res)
	})

	mux.HandleFunc("POST /api/admin/errors/{id}/resolve", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
		if err != nil || id <= 0 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid id"})
			return
		}
		var body struct {
			Resolved bool `json:"resolved"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			body.Resolved = true
		}
		if err := store.SetErrorResolved(r.Context(), id, body.Resolved); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "id": id, "resolved": body.Resolved})
	})

	mux.HandleFunc("DELETE /api/admin/errors/{id}", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
		if err != nil || id <= 0 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid id"})
			return
		}
		if err := store.DeleteError(r.Context(), id); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true, "id": id})
	})

	mux.HandleFunc("GET /api/admin/export.csv", func(w http.ResponseWriter, r *http.Request) {
		data, err := store.ExportErrorsCSV(r.Context())
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "text/csv; charset=utf-8")
		w.Header().Set("Content-Disposition", `attachment; filename="bitchord-errors.csv"`)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(data)
	})

	return authMiddleware(adminSecret, mux)
}

func serveDashboard(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(dashboardHTML)
}

func authMiddleware(secret string, next http.Handler) http.Handler {
	if secret == "" {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/healthz" {
			next.ServeHTTP(w, r)
			return
		}
		if auth := r.Header.Get("Authorization"); strings.HasPrefix(auth, "Bearer ") {
			token := strings.TrimPrefix(auth, "Bearer ")
			if subtle.ConstantTimeCompare([]byte(token), []byte(secret)) == 1 {
				next.ServeHTTP(w, r)
				return
			}
		}
		if _, pass, ok := r.BasicAuth(); ok {
			if subtle.ConstantTimeCompare([]byte(pass), []byte(secret)) == 1 {
				next.ServeHTTP(w, r)
				return
			}
		}
		w.Header().Set("WWW-Authenticate", `Basic realm="BitChord Telemetry"`)
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
	})
}

func writeJSON(w http.ResponseWriter, status int, payload interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
