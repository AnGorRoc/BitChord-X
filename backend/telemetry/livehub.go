package telemetry

import (
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type wsMessage struct {
	Type string      `json:"type"`
	TS   time.Time   `json:"ts"`
	Data interface{} `json:"data,omitempty"`
}

// LiveHub broadcasts real-time telemetry updates over WebSocket to connected
// admin dashboard clients on the private admin port.
type LiveHub struct {
	mu      sync.RWMutex
	clients map[*websocket.Conn]chan []byte
}

func NewLiveHub() *LiveHub {
	return &LiveHub{
		clients: make(map[*websocket.Conn]chan []byte),
	}
}

func (h *LiveHub) ClientCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients)
}

func (h *LiveHub) Notify(msgType string, data interface{}) {
	h.mu.RLock()
	hasClients := len(h.clients) > 0
	h.mu.RUnlock()
	if !hasClients {
		return
	}

	raw, err := json.Marshal(wsMessage{
		Type: msgType,
		TS:   time.Now().UTC(),
		Data: data,
	})
	if err != nil {
		return
	}

	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, ch := range h.clients {
		select {
		case ch <- raw:
		default:
			// Drop frame for slow clients so broadcasts never block.
		}
	}
}

var adminUpgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 4096,
	CheckOrigin: func(r *http.Request) bool {
		// Served exclusively on the private LAN/Tailscale admin port.
		return true
	},
}

func (h *LiveHub) ServeWS(w http.ResponseWriter, r *http.Request, initial LiveStats) {
	conn, err := adminUpgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	sendCh := make(chan []byte, 32)

	h.mu.Lock()
	h.clients[conn] = sendCh
	h.mu.Unlock()

	defer func() {
		h.mu.Lock()
		delete(h.clients, conn)
		h.mu.Unlock()
		close(sendCh)
		_ = conn.Close()
	}()

	// Push initial live snapshot immediately on connect.
	if hello, err := json.Marshal(wsMessage{
		Type: "live",
		TS:   time.Now().UTC(),
		Data: initial,
	}); err == nil {
		select {
		case sendCh <- hello:
		default:
		}
	}

	// Writer goroutine
	done := make(chan struct{})
	go func() {
		defer close(done)
		pingTicker := time.NewTicker(25 * time.Second)
		defer pingTicker.Stop()
		for {
			select {
			case msg, ok := <-sendCh:
				if !ok {
					return
				}
				_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
				if err := conn.WriteMessage(websocket.TextMessage, msg); err != nil {
					return
				}
			case <-pingTicker.C:
				_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
				if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
					return
				}
			}
		}
	}()

	// Reader loop (keeps connection alive and detects client close)
	_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	})
	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			break
		}
	}
	<-done
}
