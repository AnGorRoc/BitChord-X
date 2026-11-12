package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

var (
	testProofAna  = strings.Repeat("a", 64)
	testProofBeto = strings.Repeat("b", 64)
)

func newAccountServer() *httptest.Server {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/account/connect", handleAccountConnect)
	mux.HandleFunc("GET /ws/account", handleAccountSocket)
	return httptest.NewServer(corsMiddleware(mux))
}

type deviceSocket struct {
	t        *testing.T
	ws       *websocket.Conn
	deviceID string
}

// openDevice registers a device over REST and opens its socket, returning after the welcome.
func openDevice(t *testing.T, ts *httptest.Server, proof, id, name, kind string) *deviceSocket {
	t.Helper()
	body, _ := json.Marshal(map[string]string{
		"accountProof": proof, "deviceId": id, "deviceName": name, "deviceType": kind,
	})
	res, err := http.Post(ts.URL+"/api/account/connect", "application/json", bytes.NewReader(body))
	if err != nil || res.StatusCode != http.StatusOK {
		t.Fatalf("connect %s: %v %v", id, err, res)
	}
	var out map[string]interface{}
	_ = json.NewDecoder(res.Body).Decode(&out)
	token, _ := out["token"].(string)
	if token == "" || strings.Contains(string(mustJSON(out)), proof) {
		t.Fatalf("a token is returned and the proof never is: %v", out)
	}

	u, _ := url.Parse(ts.URL)
	u.Scheme = "ws"
	u.Path = "/ws/account"
	h := http.Header{}
	h.Set("Authorization", "Bearer "+token)
	ws, _, err := websocket.DefaultDialer.Dial(u.String(), h)
	if err != nil {
		t.Fatalf("socket %s: %v", id, err)
	}
	d := &deviceSocket{t: t, ws: ws, deviceID: id}
	if f := d.next("welcome"); f["deviceId"] != id {
		t.Fatalf("welcome: %v", f)
	}
	return d
}

func mustJSON(v interface{}) []byte { b, _ := json.Marshal(v); return b }

func (d *deviceSocket) send(frame map[string]interface{}) {
	d.t.Helper()
	if err := d.ws.WriteJSON(frame); err != nil {
		d.t.Fatalf("send: %v", err)
	}
}

// next reads frames until one of the wanted type shows up.
func (d *deviceSocket) next(kind string) map[string]interface{} {
	d.t.Helper()
	_ = d.ws.SetReadDeadline(time.Now().Add(3 * time.Second))
	for {
		var f map[string]interface{}
		if err := d.ws.ReadJSON(&f); err != nil {
			d.t.Fatalf("%s waiting for %q: %v", d.deviceID, kind, err)
		}
		if f["type"] == kind {
			return f
		}
	}
}

// devicesWhere waits for a devices frame satisfying ok.
func (d *deviceSocket) devicesWhere(ok func(list []map[string]interface{}) bool) []map[string]interface{} {
	d.t.Helper()
	for i := 0; i < 20; i++ {
		f := d.next("devices")
		var list []map[string]interface{}
		for _, raw := range f["devices"].([]interface{}) {
			list = append(list, raw.(map[string]interface{}))
		}
		if ok(list) {
			return list
		}
	}
	d.t.Fatalf("%s never saw the list it expected", d.deviceID)
	return nil
}

func find(list []map[string]interface{}, id string) map[string]interface{} {
	for _, d := range list {
		if d["deviceId"] == id {
			return d
		}
	}
	return nil
}

func TestAccountDevicesSeeEachOtherLive(t *testing.T) {
	ts := newAccountServer()
	defer ts.Close()

	phone := openDevice(t, ts, testProofAna, "phone", "Pixel", "phone")
	defer phone.ws.Close()
	pc := openDevice(t, ts, testProofAna, "pc", "IOP", "computer")
	defer pc.ws.Close()

	list := phone.devicesWhere(func(l []map[string]interface{}) bool {
		return len(l) == 2 && find(l, "pc")["connected"] == true
	})
	if find(list, "pc")["name"] != "IOP" || find(list, "pc")["type"] != "computer" {
		t.Fatalf("the phone sees the PC by its name: %v", list)
	}

	// What the PC plays reaches the phone.
	pc.send(map[string]interface{}{"type": "nowPlaying", "nowPlaying": map[string]interface{}{
		"videoId": "v1", "title": "TUSSI", "artist": "LAJA", "isPlaying": true, "positionMs": 12000.0, "durationMs": 180000.0,
	}})
	phone.devicesWhere(func(l []map[string]interface{}) bool {
		np, _ := find(l, "pc")["nowPlaying"].(map[string]interface{})
		return np != nil && np["title"] == "TUSSI" && np["isPlaying"] == true
	})
}

func TestAnotherAccountNeverSeesYourDevices(t *testing.T) {
	ts := newAccountServer()
	defer ts.Close()
	ana := openDevice(t, ts, testProofAna, "phone", "Pixel", "phone")
	defer ana.ws.Close()
	beto := openDevice(t, ts, testProofBeto, "b-phone", "Beto", "phone")
	defer beto.ws.Close()

	list := beto.devicesWhere(func(l []map[string]interface{}) bool { return len(l) >= 1 })
	if len(list) != 1 || list[0]["deviceId"] != "b-phone" {
		t.Fatalf("Beto must see only his own: %v", list)
	}
}

func TestPickingADeviceHandsTheSoundOverAndLinksThem(t *testing.T) {
	ts := newAccountServer()
	defer ts.Close()
	phone := openDevice(t, ts, testProofAna, "phone", "Pixel", "phone")
	defer phone.ws.Close()
	pc := openDevice(t, ts, testProofAna, "pc", "IOP", "computer")
	defer pc.ws.Close()

	phone.send(map[string]interface{}{
		"type": "command", "to": "pc", "action": "playQueue", "id": "1",
		"payload": map[string]interface{}{
			"index": 0.0, "positionMs": 5000.0,
			"tracks": []interface{}{map[string]interface{}{"videoId": "v1"}, map[string]interface{}{"videoId": "v2"}},
		},
	})
	got := pc.next("command")
	payload, _ := got["payload"].(map[string]interface{})
	if got["from"] != "phone" || got["action"] != "playQueue" || len(payload["tracks"].([]interface{})) != 2 {
		t.Fatalf("the PC receives the hand-over: %v", got)
	}
	// Both devices now show who controls whom.
	phone.devicesWhere(func(l []map[string]interface{}) bool {
		return find(l, "pc")["controlledBy"] == "phone" && find(l, "phone")["controlling"] == "pc"
	})

	// The phone, now a remote, can pause it.
	phone.send(map[string]interface{}{"type": "command", "to": "pc", "action": "pause"})
	if pc.next("command")["action"] != "pause" {
		t.Fatalf("a remote reaches its target")
	}
}

func TestACommandWithoutALinkIsRefused(t *testing.T) {
	ts := newAccountServer()
	defer ts.Close()
	phone := openDevice(t, ts, testProofAna, "phone", "Pixel", "phone")
	defer phone.ws.Close()
	pc := openDevice(t, ts, testProofAna, "pc", "IOP", "computer")
	defer pc.ws.Close()

	phone.send(map[string]interface{}{"type": "command", "to": "pc", "action": "pause"})
	if phone.next("error")["error"] != "not_allowed" {
		t.Fatalf("not linked, not allowed")
	}
}

func TestACommandCannotCrossAccounts(t *testing.T) {
	ts := newAccountServer()
	defer ts.Close()
	ana := openDevice(t, ts, testProofAna, "phone", "Pixel", "phone")
	defer ana.ws.Close()
	beto := openDevice(t, ts, testProofBeto, "b-phone", "Beto", "phone")
	defer beto.ws.Close()

	// Ana names Beto's device id: it simply does not exist in her account.
	ana.send(map[string]interface{}{"type": "command", "to": "b-phone", "action": "playQueue",
		"payload": map[string]interface{}{"tracks": []interface{}{}}})
	if ana.next("error")["error"] != "not_allowed" {
		t.Fatalf("another account's device is out of reach")
	}
}

func TestSocketNeedsAValidToken(t *testing.T) {
	ts := newAccountServer()
	defer ts.Close()
	u, _ := url.Parse(ts.URL)
	u.Scheme = "ws"
	u.Path = "/ws/account"
	h := http.Header{}
	h.Set("Authorization", "Bearer nonsense.token")
	if _, res, err := websocket.DefaultDialer.Dial(u.String(), h); err == nil || res == nil || res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("a bad token must be refused: %v %v", err, res)
	}
}

func TestTheRemoteLeavingFreesTheTarget(t *testing.T) {
	ts := newAccountServer()
	defer ts.Close()
	phone := openDevice(t, ts, testProofAna, "phone", "Pixel", "phone")
	pc := openDevice(t, ts, testProofAna, "pc", "IOP", "computer")
	defer pc.ws.Close()

	phone.send(map[string]interface{}{"type": "command", "to": "pc", "action": "playQueue",
		"payload": map[string]interface{}{"tracks": []interface{}{map[string]interface{}{"videoId": "v"}}}})
	pc.devicesWhere(func(l []map[string]interface{}) bool { return find(l, "pc")["controlledBy"] == "phone" })

	_ = phone.ws.Close()
	list := pc.devicesWhere(func(l []map[string]interface{}) bool {
		return find(l, "phone")["connected"] == false
	})
	if find(list, "pc")["controlledBy"] != nil && find(list, "pc")["controlledBy"] != "" {
		t.Fatalf("the PC is free again: %v", list)
	}
}

func TestRenamingADeviceReachesTheOthers(t *testing.T) {
	ts := newAccountServer()
	defer ts.Close()
	phone := openDevice(t, ts, testProofAna, "phone", "Pixel", "phone")
	defer phone.ws.Close()
	pc := openDevice(t, ts, testProofAna, "pc", "IOP", "computer")
	defer pc.ws.Close()

	pc.send(map[string]interface{}{"type": "rename", "name": "Escritorio"})
	phone.devicesWhere(func(l []map[string]interface{}) bool { return find(l, "pc")["name"] == "Escritorio" })
	pc.send(map[string]interface{}{"type": "rename", "name": ""})
	if pc.next("error")["error"] != "bad_request" {
		t.Fatalf("an empty name is refused")
	}
}

func TestConnectRejectsABadProof(t *testing.T) {
	ts := newAccountServer()
	defer ts.Close()
	body, _ := json.Marshal(map[string]string{"accountProof": "short", "deviceId": "d"})
	res, _ := http.Post(ts.URL+"/api/account/connect", "application/json", bytes.NewReader(body))
	if res.StatusCode != http.StatusUnprocessableEntity {
		t.Fatalf("status %d", res.StatusCode)
	}
}
