package hub

import (
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// outboxFrames is how many frames may wait for one slow connection. A connection that falls this
// far behind is cut instead of being waited for: it reconnects and is brought up to date by the
// welcome, and nobody else's music waits on its bad network.
const outboxFrames = 32

// writeTimeout bounds one write to a peer that stopped reading.
const writeTimeout = 5 * time.Second

// ErrSlowConsumer is returned when a connection's outbox is full and it is being cut.
var ErrSlowConsumer = errors.New("connection too slow")

// SafeConn wraps *websocket.Conn. Frames are queued and written, in the order they were queued, by
// one goroutine per connection, so queuing never waits on the network: a caller holding a party's
// lock can hand a frame to every member and carry on. That order is the order of the party's own
// changes, which is what keeps one member from seeing a newer state before an older one.
type SafeConn struct {
	conn *websocket.Conn

	mu     sync.Mutex // guards closed and sending on out
	closed bool
	out    chan []byte
}

func NewSafeConn(conn *websocket.Conn) *SafeConn {
	s := &SafeConn{conn: conn, out: make(chan []byte, outboxFrames)}
	go s.writeLoop()
	return s
}

// writeLoop is the only writer of the socket. After Close it still delivers what was queued (a
// "bye" sent just before the close must arrive), then closes the connection.
func (s *SafeConn) writeLoop() {
	failed := false
	for frame := range s.out {
		if failed {
			continue // drain, so nothing is left blocked on the channel
		}
		_ = s.conn.SetWriteDeadline(time.Now().Add(writeTimeout))
		if err := s.conn.WriteMessage(websocket.TextMessage, frame); err != nil {
			failed = true
			_ = s.conn.Close() // ends the read loop, which cleans up
		}
	}
	_ = s.conn.Close()
}

// WriteBytes queues an already-encoded text frame.
func (s *SafeConn) WriteBytes(frame []byte) error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return errors.New("connection closed")
	}
	select {
	case s.out <- frame:
		s.mu.Unlock()
		return nil
	default:
	}
	// Full: this peer is not keeping up. Stop queueing for it and let it reconnect.
	s.closed = true
	close(s.out)
	s.mu.Unlock()
	return ErrSlowConsumer
}

func (s *SafeConn) WriteJSON(v interface{}) error {
	frame, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return s.WriteBytes(frame)
}

// Close stops accepting frames, delivers those already queued, and closes the connection.
func (s *SafeConn) Close() error {
	s.mu.Lock()
	if !s.closed {
		s.closed = true
		close(s.out)
	}
	s.mu.Unlock()
	return nil
}

// Hub coordinates active WebSocket connections across rooms.
type Hub struct {
	mu      sync.RWMutex
	sockets map[string]map[string]*SafeConn // code -> memberId -> SafeConn
}

func NewHub() *Hub {
	return &Hub{
		sockets: make(map[string]map[string]*SafeConn),
	}
}

func (h *Hub) Attach(code, memberId string, conn *websocket.Conn) *SafeConn {
	sc := NewSafeConn(conn)
	h.mu.Lock()
	room, ok := h.sockets[code]
	if !ok {
		room = make(map[string]*SafeConn)
		h.sockets[code] = room
	}
	prev := room[memberId]
	room[memberId] = sc
	h.mu.Unlock()

	// Close previous connection outside lock so it doesn't stall other parties
	if prev != nil && prev != sc {
		_ = prev.Close()
	}
	return sc
}

func (h *Hub) Detach(code, memberId string, sc *SafeConn) {
	h.mu.Lock()
	defer h.mu.Unlock()

	room, ok := h.sockets[code]
	if !ok {
		return
	}
	if room[memberId] == sc {
		delete(room, memberId)
	}
	if len(room) == 0 {
		delete(h.sockets, code)
	}
}

func (h *Hub) DropParty(code string) {
	h.mu.Lock()
	room := h.sockets[code]
	delete(h.sockets, code)
	h.mu.Unlock()

	if room != nil {
		for _, sc := range room {
			_ = sc.Close()
		}
	}
}

func (h *Hub) MembersOnline(code string) []string {
	h.mu.RLock()
	defer h.mu.RUnlock()

	room := h.sockets[code]
	if room == nil {
		return nil
	}
	members := make([]string, 0, len(room))
	for mid := range room {
		members = append(members, mid)
	}
	return members
}

func (h *Hub) ActiveCodes() []string {
	h.mu.RLock()
	defer h.mu.RUnlock()

	codes := make([]string, 0, len(h.sockets))
	for c := range h.sockets {
		codes = append(codes, c)
	}
	return codes
}

func (h *Hub) Send(code, memberId string, payload interface{}) {
	h.mu.RLock()
	room := h.sockets[code]
	var target *SafeConn
	if room != nil {
		target = room[memberId]
	}
	h.mu.RUnlock()

	if target != nil {
		_ = target.WriteJSON(payload)
	}
}

func (h *Hub) CloseMember(code, memberId string) {
	h.mu.RLock()
	room := h.sockets[code]
	var target *SafeConn
	if room != nil { target = room[memberId] }
	h.mu.RUnlock()
	if target != nil { _ = target.Close() }
}

// Broadcast queues payload for every member of the room but skipMemberId. It encodes once and
// never waits on the network, so it is safe to call while holding a party's lock.
func (h *Hub) Broadcast(code string, payload interface{}, skipMemberId string) {
	frame, err := json.Marshal(payload)
	if err != nil {
		return
	}

	h.mu.RLock()
	room := h.sockets[code]
	if len(room) == 0 {
		h.mu.RUnlock()
		return
	}
	targets := make([]*SafeConn, 0, len(room))
	for mid, sc := range room {
		if mid != skipMemberId {
			targets = append(targets, sc)
		}
	}
	h.mu.RUnlock()

	for _, sc := range targets {
		_ = sc.WriteBytes(frame)
	}
}

// Count is how many sockets are open across every room, for a ceiling on what one process holds.
func (h *Hub) Count() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	n := 0
	for _, room := range h.sockets {
		n += len(room)
	}
	return n
}
