package telemetry

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCSRFMiddleware(t *testing.T) {
	ok := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) })
	h := csrfMiddleware(ok)
	cases := []struct {
		name, method string
		headers      map[string]string
		want         int
	}{
		{"read from anywhere", "GET", map[string]string{"Sec-Fetch-Site": "cross-site"}, 204},
		{"script without browser headers", "POST", nil, 204},
		{"the dashboard itself", "POST", map[string]string{"Sec-Fetch-Site": "same-origin", "Origin": "http://admin.test"}, 204},
		{"typed in the address bar", "POST", map[string]string{"Sec-Fetch-Site": "none"}, 204},
		{"another site's form", "POST", map[string]string{"Sec-Fetch-Site": "cross-site", "Origin": "https://evil.example"}, 403},
		{"same site, other host", "DELETE", map[string]string{"Sec-Fetch-Site": "same-site"}, 403},
		{"old browser, foreign origin", "PATCH", map[string]string{"Origin": "https://evil.example"}, 403},
		{"opaque origin", "POST", map[string]string{"Origin": "null"}, 403},
	}
	for _, c := range cases {
		req := httptest.NewRequest(c.method, "http://admin.test/api/admin/database/reset", nil)
		for k, v := range c.headers {
			req.Header.Set(k, v)
		}
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != c.want {
			t.Errorf("%s: got %d want %d", c.name, rec.Code, c.want)
		}
	}
}
