package hub

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// pair opens a real socket and returns the server's side wrapped, and the client's side.
func pair(t *testing.T) (*SafeConn, *websocket.Conn) {
	t.Helper()
	up := websocket.Upgrader{}
	got := make(chan *websocket.Conn, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := up.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		got <- c
	}))
	t.Cleanup(srv.Close)
	client, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	return NewSafeConn(<-got), client
}

func TestFramesArriveInTheOrderTheyWereQueued(t *testing.T) {
	sc, client := pair(t)
	for i := 0; i < 20; i++ {
		if err := sc.WriteJSON(map[string]int{"n": i}); err != nil {
			t.Fatal(err)
		}
	}
	for i := 0; i < 20; i++ {
		var m map[string]int
		_ = client.SetReadDeadline(time.Now().Add(2 * time.Second))
		if err := client.ReadJSON(&m); err != nil || m["n"] != i {
			t.Fatalf("frame %d: got %v, %v", i, m, err)
		}
	}
}

func TestCloseDeliversWhatWasQueuedFirst(t *testing.T) {
	// A kick sends "bye" and closes straight away: the bye must still arrive.
	sc, client := pair(t)
	_ = sc.WriteJSON(map[string]string{"type": "bye"})
	_ = sc.Close()
	var m map[string]string
	_ = client.SetReadDeadline(time.Now().Add(2 * time.Second))
	if err := client.ReadJSON(&m); err != nil || m["type"] != "bye" {
		t.Fatalf("the queued frame must survive the close: %v, %v", m, err)
	}
	if err := sc.WriteJSON(1); err == nil {
		t.Fatalf("a closed connection takes no more frames")
	}
}

func TestASlowPeerIsCutInsteadOfWaitedFor(t *testing.T) {
	sc, _ := pair(t) // the client never reads
	big := strings.Repeat("x", 256*1024)
	start := time.Now()
	var cut bool
	for i := 0; i < 500 && !cut; i++ {
		if err := sc.WriteJSON(big); err != nil {
			cut = true
		}
	}
	if !cut {
		t.Fatalf("a peer that reads nothing must eventually be cut")
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("queueing must never wait on the network, took %v", time.Since(start))
	}
}
