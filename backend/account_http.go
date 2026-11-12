package main

import (
	"log"
	"net/http"
	"time"

	"github.com/KabirSinghBhatia/BitChord/backend/account"
	"github.com/KabirSinghBhatia/BitChord/backend/clock"
	"github.com/KabirSinghBhatia/BitChord/backend/config"
	"github.com/KabirSinghBhatia/BitChord/backend/hub"
)

// One person's devices, see package account. Each account is a room in the same hub that
// carries parties, under a prefix no party code can have (codes are letters and digits).
var accounts = account.NewStore()

const accountRoomPrefix = "acct:"

func accountRoom(id string) string { return accountRoomPrefix + id }

type accountConnectRequest struct {
	AccountProof string `json:"accountProof"`
	DeviceId     string `json:"deviceId"`
	DeviceName   string `json:"deviceName"`
	DeviceType   string `json:"deviceType"`
}

// handleAccountConnect registers a device with its owner's other devices and hands back the
// token its socket will use. The proof is the whole credential and is never echoed.
func handleAccountConnect(w http.ResponseWriter, r *http.Request) {
	var req accountConnectRequest
	if !decodeJSONBody(w, r, &req) {
		return
	}
	now := clock.NowMs()
	acc, d, token, err := accounts.Connect(req.AccountProof, req.DeviceId, req.DeviceName, req.DeviceType, now)
	if err != nil {
		jsonError(w, http.StatusUnprocessableEntity, "validation_error", err.Error())
		return
	}
	acc.Lock()
	list := acc.List()
	you := d.DeviceID
	id := acc.ID
	acc.Unlock()

	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"token":     token,
		"accountId": id,
		"deviceId":  you,
		"devices":   list,
	})
}

func devicesFrame(acc *account.Account) map[string]interface{} {
	return map[string]interface{}{
		"type":     "devices",
		"devices":  acc.List(),
		"serverMs": clock.NowMs(),
	}
}

func errorFrame(code, message string) map[string]interface{} {
	return map[string]interface{}{"type": "error", "error": code, "message": message}
}

// accountFrameBudget is a token bucket per socket: a device publishing its position a few
// times a second is normal, a flood is not.
type accountFrameBudget struct {
	tokens float64
	at     time.Time
}

func (b *accountFrameBudget) allow() bool {
	now := time.Now()
	if b.at.IsZero() {
		b.tokens, b.at = 40, now
	}
	b.tokens += now.Sub(b.at).Seconds() * 20
	if b.tokens > 40 {
		b.tokens = 40
	}
	b.at = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

func handleAccountSocket(w http.ResponseWriter, r *http.Request) {
	token := parseSocketToken(r)
	if token == "" {
		http.Error(w, "missing token", http.StatusUnauthorized)
		return
	}
	acc, d, err := accounts.Authenticate(token)
	if err != nil {
		http.Error(w, "bad token", http.StatusUnauthorized)
		return
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("account websocket upgrade failed: %v", err)
		return
	}
	conn.SetReadLimit(config.WebSocketMaxBytes)
	idle := time.Duration(config.ConnectionIdleMs) * time.Millisecond
	_ = conn.SetReadDeadline(time.Now().Add(idle))

	room := accountRoom(acc.ID)
	acc.Lock()
	acc.SetConnected(d, true, clock.NowMs())
	acc.Unlock()
	sc := hubInst.Attach(room, d.DeviceID, conn)

	acc.Lock()
	welcome := map[string]interface{}{
		"type": "welcome", "deviceId": d.DeviceID, "accountId": acc.ID,
		"devices": acc.List(), "serverMs": clock.NowMs(),
	}
	acc.Unlock()
	_ = sc.WriteJSON(welcome)
	broadcastDevices(acc)

	defer func() {
		hubInst.Detach(room, d.DeviceID, sc)
		_ = sc.Close()
		acc.Lock()
		// Only mark it away if no newer socket of the same device replaced this one.
		stillThere := false
		for _, id := range hubInst.MembersOnline(room) {
			if id == d.DeviceID {
				stillThere = true
			}
		}
		if !stillThere {
			acc.SetConnected(d, false, clock.NowMs())
		}
		acc.Unlock()
		broadcastDevices(acc)
	}()

	var budget accountFrameBudget
	for {
		var frame map[string]interface{}
		if err := conn.ReadJSON(&frame); err != nil {
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(idle))
		if !budget.allow() {
			_ = sc.WriteJSON(errorFrame("rate_limited", "Too many messages at once."))
			continue
		}
		handleAccountFrame(acc, d, sc, frame)
	}
}

func broadcastDevices(acc *account.Account) {
	acc.Lock()
	frame := devicesFrame(acc)
	acc.Unlock()
	hubInst.Broadcast(accountRoom(acc.ID), frame, "")
}

func handleAccountFrame(acc *account.Account, d *account.Device, sc *hub.SafeConn, frame map[string]interface{}) {
	kind, _ := frame["type"].(string)
	now := clock.NowMs()

	switch kind {
	case "ping":
		acc.Lock()
		d.LastSeenMs = now
		acc.Unlock()
		_ = sc.WriteJSON(map[string]interface{}{"type": "pong", "clientMs": frame["clientMs"], "serverMs": now})

	case "nowPlaying":
		np := nowPlayingFromFrame(frame)
		acc.Lock()
		acc.Publish(d, np, now)
		acc.Unlock()
		broadcastDevices(acc)

	case "rename":
		name, _ := frame["name"].(string)
		acc.Lock()
		err := acc.Rename(d, name)
		acc.Unlock()
		if err != nil {
			_ = sc.WriteJSON(errorFrame("bad_request", err.Error()))
			return
		}
		broadcastDevices(acc)

	case "attach":
		to, _ := frame["to"].(string)
		acc.Lock()
		err := acc.Attach(d, to)
		acc.Unlock()
		if err != nil {
			_ = sc.WriteJSON(errorFrame("not_available", err.Error()))
			return
		}
		broadcastDevices(acc)

	case "detach":
		acc.Lock()
		acc.Detach(d)
		acc.Unlock()
		broadcastDevices(acc)

	case "command":
		to, _ := frame["to"].(string)
		action, _ := frame["action"].(string)
		payload, _ := frame["payload"].(map[string]interface{})
		items := 0
		if tracks, ok := payload["tracks"].([]interface{}); ok {
			items = len(tracks)
		}
		acc.Lock()
		target, err := acc.Route(d, to, action, items)
		var targetID string
		if err == nil {
			targetID = target.DeviceID
			// Handing music to a device is also how a remote starts: link them in the same step
			// so the list shows who controls whom without a second round trip.
			if action == "playQueue" || action == "enqueue" {
				if d.Controlling != targetID {
					_ = acc.Attach(d, targetID)
				}
			}
			if action == "release" {
				acc.Detach(target)
			}
		}
		acc.Unlock()
		if err != nil {
			_ = sc.WriteJSON(errorFrame("not_allowed", err.Error()))
			return
		}
		hubInst.Send(accountRoom(acc.ID), targetID, map[string]interface{}{
			"type": "command", "from": d.DeviceID, "action": action,
			"payload": payload, "id": frame["id"], "serverMs": now,
		})
		broadcastDevices(acc)
	}
}

func nowPlayingFromFrame(frame map[string]interface{}) *account.NowPlaying {
	m, _ := frame["nowPlaying"].(map[string]interface{})
	if m == nil {
		return nil
	}
	str := func(key string, max int) string {
		s, _ := m[key].(string)
		if len(s) > max {
			s = s[:max]
		}
		return s
	}
	num := func(key string) int64 {
		f, _ := m[key].(float64)
		return int64(f)
	}
	np := &account.NowPlaying{
		VideoId:    str("videoId", 128),
		Title:      str("title", 300),
		Artist:     str("artist", 300),
		DurationMs: num("durationMs"),
		PositionMs: num("positionMs"),
	}
	np.IsPlaying, _ = m["isPlaying"].(bool)
	if t := str("thumbnailUrl", 1000); t != "" {
		np.ThumbnailUrl = &t
	}
	if v, ok := m["volume"].(float64); ok && v >= 0 && v <= 1 {
		np.Volume = &v
	}
	return np
}

// startAccountSweeper forgets devices that have been gone for a day and accounts left empty.
func startAccountSweeper() {
	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		accounts.Sweep(clock.NowMs())
	}
}
