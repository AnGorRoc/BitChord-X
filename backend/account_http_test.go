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

	"github.com/KabirSinghBhatia/BitChord/backend/account"
)

var (
	testProofAna  = strings.Repeat("a", 64)
	testProofBeto = strings.Repeat("b", 64)
)

func init() {
	// Seconds instead of twelve, and room for every device these tests connect from one
	// address.
	account.LinkGrace = 1500 * time.Millisecond
	accountConnectLimiter = newIPRateLimiter(time.Minute, 100000, 100)
}

func newAccountServer() *httptest.Server {
	// Every test gets accounts of its own: links now outlive a dropped socket for a moment, so what
	// one test leaves behind would otherwise still be linked in the next.
	accounts = account.NewStore()
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

// The heartbeat closes rooms that belong to no party. Account rooms are not parties.
func TestTheHeartbeatDoesNotDropAccountSockets(t *testing.T) {
	ts := newAccountServer()
	defer ts.Close()
	phone := openDevice(t, ts, testProofAna, "phone", "Pixel", "phone")
	defer phone.ws.Close()
	pc := openDevice(t, ts, testProofAna, "pc", "IOP", "computer")
	defer pc.ws.Close()

	dropOrphanRooms()

	// Both sockets must still work: a command goes through and arrives.
	phone.send(map[string]interface{}{"type": "command", "to": "pc", "action": "playQueue",
		"payload": map[string]interface{}{"tracks": []interface{}{map[string]interface{}{"videoId": "v"}}}})
	if pc.next("command")["action"] != "playQueue" {
		t.Fatalf("the sockets must survive the orphan sweep")
	}
}

func TestASnapshotAnswerLinksNobody(t *testing.T) {
	ts := newAccountServer()
	defer ts.Close()
	phone := openDevice(t, ts, testProofAna, "phone", "Pixel", "phone")
	defer phone.ws.Close()
	pc := openDevice(t, ts, testProofAna, "pc", "IOP", "computer")
	defer pc.ws.Close()

	// The phone picks the PC (control only) and asks what it is playing.
	phone.send(map[string]interface{}{"type": "attach", "to": "pc"})
	phone.devicesWhere(func(l []map[string]interface{}) bool { return find(l, "pc")["controlledBy"] == "phone" })
	phone.send(map[string]interface{}{"type": "command", "to": "pc", "action": "snapshot"})
	if pc.next("command")["action"] != "snapshot" {
		t.Fatalf("the PC receives the request")
	}
	// The PC answers with its queue. It is the target, so it must stay the target.
	pc.send(map[string]interface{}{
		"type": "command", "to": "phone", "action": "playQueue",
		"payload": map[string]interface{}{"link": false, "mirror": true, "tracks": []interface{}{map[string]interface{}{"videoId": "v9"}}},
	})
	got := phone.next("command")
	if got["from"] != "pc" || got["action"] != "playQueue" {
		t.Fatalf("the phone receives the snapshot: %v", got)
	}
	list := phone.devicesWhere(func(l []map[string]interface{}) bool { return len(l) == 2 })
	if find(list, "pc")["controlledBy"] != "phone" || find(list, "pc")["controlling"] != nil {
		t.Fatalf("answering a snapshot must not reverse the link: %v", list)
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
	// Gone, but not yet given up on: the link waits out the grace for a socket that comes back.
	list := pc.devicesWhere(func(l []map[string]interface{}) bool {
		return find(l, "phone")["connected"] == false
	})
	if find(list, "pc")["controlledBy"] != "phone" {
		t.Fatalf("a dropped socket must not end the link at once: %v", list)
	}
	// Still gone when the grace ends: the PC is free again.
	list = pc.devicesWhere(func(l []map[string]interface{}) bool {
		c := find(l, "pc")["controlledBy"]
		return c == nil || c == ""
	})
	if find(list, "phone")["connected"] != false {
		t.Fatalf("the remote is still away: %v", list)
	}
}

func TestARemoteThatComesBackInTimeKeepsItsTarget(t *testing.T) {
	ts := newAccountServer()
	defer ts.Close()
	phone := openDevice(t, ts, testProofAna, "phone", "Pixel", "phone")
	pc := openDevice(t, ts, testProofAna, "pc", "IOP", "computer")
	defer pc.ws.Close()

	phone.send(map[string]interface{}{"type": "command", "to": "pc", "action": "playQueue",
		"payload": map[string]interface{}{"tracks": []interface{}{map[string]interface{}{"videoId": "v"}}}})
	pc.devicesWhere(func(l []map[string]interface{}) bool { return find(l, "pc")["controlledBy"] == "phone" })

	_ = phone.ws.Close()
	pc.devicesWhere(func(l []map[string]interface{}) bool { return find(l, "phone")["connected"] == false })
	back := openDevice(t, ts, testProofAna, "phone", "Pixel", "phone")
	defer back.ws.Close()

	list := back.devicesWhere(func(l []map[string]interface{}) bool { return find(l, "phone")["connected"] == true })
	if find(list, "phone")["controlling"] != "pc" || find(list, "pc")["controlledBy"] != "phone" {
		t.Fatalf("back inside the grace, it is still the remote of the PC: %v", list)
	}
	// And the grace running out afterwards finds it connected, so it frees nothing.
	time.Sleep(account.LinkGrace + 200*time.Millisecond)
	// Nothing is broadcast when a grace ends without effect, so ask for a fresh list.
	back.send(map[string]interface{}{"type": "rename", "name": "Pixel"})
	list = back.devicesWhere(func(l []map[string]interface{}) bool {
		return len(l) == 2 && find(l, "pc")["connected"] == true && find(l, "phone")["connected"] == true
	})
	if find(list, "pc")["controlledBy"] != "phone" {
		t.Fatalf("the grace ending must not free a device that came back: %v", list)
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

func TestARefusalNamesItsReason(t *testing.T) {
	ts := newAccountServer()
	defer ts.Close()
	phone := openDevice(t, ts, testProofAna, "phone", "Pixel", "phone")
	defer phone.ws.Close()
	pc := openDevice(t, ts, testProofAna, "pc", "IOP", "computer")
	defer pc.ws.Close()

	// The English message is for logs; the reason is what a client puts in the person's language.
	phone.send(map[string]interface{}{"type": "command", "to": "pc", "action": "pause"})
	if got := phone.next("error"); got["reason"] != "not_linked" {
		t.Fatalf("an unlinked command is refused as not_linked: %v", got)
	}
	phone.send(map[string]interface{}{"type": "attach", "to": "ghost"})
	if got := phone.next("error"); got["reason"] != "not_available" {
		t.Fatalf("an unknown device is not_available: %v", got)
	}
}
