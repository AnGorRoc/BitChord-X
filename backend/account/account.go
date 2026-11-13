// Package account is how one person's devices see each other and hand the sound around.
//
// It is deliberately not a party. A party is one shared playback; here every device keeps
// playing its own music, and the only things that travel are (a) what each device is playing,
// published by that device, and (b) commands addressed to one device. That is the Spotify
// Connect model: a device is free until somebody picks it.
//
// A person's devices find each other through a proof that only they can produce (a hash of a
// secret kept in their own account, see the client's DeviceGroup). The proof is never sent to
// other accounts and never returned by this package; accounts are told apart by an id derived
// from it.
package account

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"regexp"
	"sort"
	"strings"
	"sync"
)

const (
	// MaxDevices bounds one account's devices, connected or remembered.
	MaxDevices = 12
	// ForgetAfterMs is how long a device that has gone quiet stays in the list as "last seen".
	ForgetAfterMs = 24 * 60 * 60 * 1000
	// MaxPayloadItems bounds a handed-over queue.
	MaxPayloadItems = 500
)

var proofPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// Types a device may call itself; anything else is stored as "other".
var deviceTypes = map[string]bool{"phone": true, "tablet": true, "computer": true, "tv": true, "watch": true, "speaker": true}

// Commands one device may send another.
var Commands = map[string]bool{
	"play": true, "pause": true, "seek": true, "next": true, "previous": true,
	"setVolume": true, "playQueue": true, "enqueue": true, "release": true,
	// snapshot asks the target to send its queue back (as a playQueue that links nobody), which is
	// how a remote that picked a device already playing comes to show what that device plays.
	"snapshot": true,
}

// NowPlaying is what a device says it is playing. The device is the only source of it.
type NowPlaying struct {
	VideoId      string  `json:"videoId"`
	Title        string  `json:"title"`
	Artist       string  `json:"artist"`
	ThumbnailUrl *string `json:"thumbnailUrl,omitempty"`
	DurationMs   int64   `json:"durationMs"`
	IsPlaying    bool    `json:"isPlaying"`
	PositionMs   int64   `json:"positionMs"`
	// AnchorMs is the server time PositionMs was measured at, so a viewer can draw the playhead.
	AnchorMs  int64    `json:"anchorMs"`
	Volume    *float64 `json:"volume,omitempty"`
	UpdatedAt int64    `json:"updatedAtMs"`
}

// Device is one of the person's devices.
type Device struct {
	DeviceID   string      `json:"deviceId"`
	Name       string      `json:"name"`
	Type       string      `json:"type"`
	Connected  bool        `json:"connected"`
	LastSeenMs int64       `json:"lastSeenMs"`
	NowPlaying *NowPlaying `json:"nowPlaying,omitempty"`
	// ControlledBy is the device currently acting as this one's remote, if any.
	ControlledBy string `json:"controlledBy,omitempty"`
	// Controlling is the device this one is acting as the remote of, if any.
	Controlling string `json:"controlling,omitempty"`
	// PartyCode is the party this device is in, if any, so the person's other devices can tell
	// that joining it again means taking this device's sound (Connect), not a second seat.
	PartyCode string `json:"partyCode,omitempty"`

	token string
}

// Account is one person's set of devices.
type Account struct {
	ID      string
	mu      sync.Mutex
	devices map[string]*Device
}

// Store holds every account in memory. Like parties, nothing here is meant to outlive a restart:
// devices reconnect by themselves.
type Store struct {
	mu       sync.Mutex
	accounts map[string]*Account
}

func NewStore() *Store { return &Store{accounts: map[string]*Account{}} }

// AccountID is the public, one-way name of an account.
func AccountID(proof string) string {
	sum := sha256.Sum256([]byte("bitchord-account-v1:" + proof))
	return hex.EncodeToString(sum[:])[:20]
}

func randomToken() string {
	b := make([]byte, 24)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// Connect registers a device (or refreshes a returning one) and returns its token. A token is
// "<accountId>.<secret>" so the socket can find the account from it alone.
func (s *Store) Connect(proof, deviceID, name, kind string, nowMs int64) (*Account, *Device, string, error) {
	if !proofPattern.MatchString(proof) {
		return nil, nil, "", errors.New("accountProof must be a lower-case SHA-256 in hex")
	}
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" || len(deviceID) > 128 {
		return nil, nil, "", errors.New("deviceId must be between 1 and 128 characters")
	}
	name = strings.TrimSpace(name)
	if name == "" {
		name = "Device"
	}
	if len(name) > 60 {
		name = name[:60]
	}
	if !deviceTypes[kind] {
		kind = "other"
	}

	id := AccountID(proof)
	s.mu.Lock()
	acc := s.accounts[id]
	if acc == nil {
		acc = &Account{ID: id, devices: map[string]*Device{}}
		s.accounts[id] = acc
	}
	s.mu.Unlock()

	acc.mu.Lock()
	defer acc.mu.Unlock()
	acc.forgetStale(nowMs)
	d := acc.devices[deviceID]
	if d == nil {
		if len(acc.devices) >= MaxDevices {
			return nil, nil, "", errors.New("too many devices on this account")
		}
		d = &Device{DeviceID: deviceID}
		acc.devices[deviceID] = d
	}
	d.Name, d.Type, d.LastSeenMs = name, kind, nowMs
	d.token = id + "." + randomToken()
	return acc, d, d.token, nil
}

// Authenticate finds the device a token belongs to.
func (s *Store) Authenticate(token string) (*Account, *Device, error) {
	id, _, ok := strings.Cut(token, ".")
	if !ok {
		return nil, nil, errors.New("bad token")
	}
	s.mu.Lock()
	acc := s.accounts[id]
	s.mu.Unlock()
	if acc == nil {
		return nil, nil, errors.New("bad token")
	}
	acc.mu.Lock()
	defer acc.mu.Unlock()
	for _, d := range acc.devices {
		if subtle.ConstantTimeCompare([]byte(d.token), []byte(token)) == 1 {
			return acc, d, nil
		}
	}
	return nil, nil, errors.New("bad token")
}

// Sweep drops accounts with nothing left to show, and returns how many.
func (s *Store) Sweep(nowMs int64) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	dropped := 0
	for id, acc := range s.accounts {
		acc.mu.Lock()
		acc.forgetStale(nowMs)
		empty := len(acc.devices) == 0
		acc.mu.Unlock()
		if empty {
			delete(s.accounts, id)
			dropped++
		}
	}
	return dropped
}

// Len is the number of accounts, for stats.
func (s *Store) Len() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.accounts)
}

// forgetStale removes devices that have been gone long enough. The caller holds acc.mu.
func (a *Account) forgetStale(nowMs int64) {
	for id, d := range a.devices {
		if !d.Connected && nowMs-d.LastSeenMs > ForgetAfterMs {
			a.releaseLinks(d)
			delete(a.devices, id)
		}
	}
}

// releaseLinks unhooks d from whoever controls it and whoever it controls.
func (a *Account) releaseLinks(d *Device) {
	if d.ControlledBy != "" {
		if c := a.devices[d.ControlledBy]; c != nil && c.Controlling == d.DeviceID {
			c.Controlling = ""
		}
		d.ControlledBy = ""
	}
	if d.Controlling != "" {
		if t := a.devices[d.Controlling]; t != nil && t.ControlledBy == d.DeviceID {
			t.ControlledBy = ""
		}
		d.Controlling = ""
	}
}

// Lock and Unlock let the transport hold the account across several calls.
func (a *Account) Lock()   { a.mu.Lock() }
func (a *Account) Unlock() { a.mu.Unlock() }

// SetConnected records a device coming or going. Going also ends any remote link it was in.
// The caller holds the account lock.
func (a *Account) SetConnected(d *Device, connected bool, nowMs int64) {
	d.Connected = connected
	d.LastSeenMs = nowMs
	if !connected {
		a.releaseLinks(d)
	}
}

// Publish stores what a device is playing. The caller holds the account lock.
func (a *Account) Publish(d *Device, np *NowPlaying, nowMs int64) {
	if np == nil || np.VideoId == "" {
		d.NowPlaying = nil
		return
	}
	if np.PositionMs < 0 {
		np.PositionMs = 0
	}
	np.AnchorMs, np.UpdatedAt = nowMs, nowMs
	d.NowPlaying = np
	d.LastSeenMs = nowMs
}

// Rename changes the device's own name. The caller holds the account lock.
func (a *Account) Rename(d *Device, name string) error {
	name = strings.TrimSpace(name)
	if name == "" || len(name) > 60 {
		return errors.New("a device name is 1 to 60 characters")
	}
	d.Name = name
	return nil
}

// Attach makes controller the remote of target. A device is the remote of one device at a time,
// and a device has one remote at a time: a second one takes over, as in Spotify. The caller holds
// the account lock.
func (a *Account) Attach(controller *Device, targetID string) error {
	target := a.devices[targetID]
	if target == nil || !target.Connected {
		return errors.New("that device is not available")
	}
	if target.DeviceID == controller.DeviceID {
		return errors.New("a device cannot control itself")
	}
	// Whoever controlled the target loses it; whatever the controller controlled is let go.
	if target.ControlledBy != "" && target.ControlledBy != controller.DeviceID {
		if old := a.devices[target.ControlledBy]; old != nil {
			old.Controlling = ""
		}
	}
	if controller.Controlling != "" && controller.Controlling != targetID {
		if prev := a.devices[controller.Controlling]; prev != nil {
			prev.ControlledBy = ""
		}
	}
	target.ControlledBy = controller.DeviceID
	controller.Controlling = targetID
	return nil
}

// Detach ends the controller's remote link. The caller holds the account lock.
func (a *Account) Detach(controller *Device) {
	if controller.Controlling == "" {
		return
	}
	if t := a.devices[controller.Controlling]; t != nil && t.ControlledBy == controller.DeviceID {
		t.ControlledBy = ""
	}
	controller.Controlling = ""
}

// Route checks a command and says which device should receive it. Only a device that is the
// remote of the target may send it, except "release", which the target may also send to take its
// sound back. The caller holds the account lock.
func (a *Account) Route(from *Device, to, action string, itemCount int) (*Device, error) {
	if !Commands[action] {
		return nil, errors.New("unknown command")
	}
	if itemCount > MaxPayloadItems {
		return nil, errors.New("too many songs in one hand-over")
	}
	target := a.devices[to]
	if target == nil || !target.Connected {
		return nil, errors.New("that device is not available")
	}
	if target.DeviceID == from.DeviceID {
		return nil, errors.New("a device cannot command itself")
	}
	linked := from.Controlling == target.DeviceID && target.ControlledBy == from.DeviceID
	switch {
	case linked:
	case action == "release" && from.ControlledBy == target.DeviceID && target.Controlling == from.DeviceID:
		// The device being controlled asks its remote to hand the sound back.
	case action == "playQueue" || action == "enqueue":
		// A device may hand its own music to another without being linked first: that is how
		// "play on this other device" starts. The caller links them right after.
	default:
		return nil, errors.New("not controlling that device")
	}
	return target, nil
}

// List is every device of the account in a stable order: connected first, then by name.
// Tokens are never included. The caller holds the account lock.
func (a *Account) List() []Device {
	out := make([]Device, 0, len(a.devices))
	for _, d := range a.devices {
		c := *d
		c.token = ""
		out = append(out, c)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Connected != out[j].Connected {
			return out[i].Connected
		}
		if out[i].Name != out[j].Name {
			return out[i].Name < out[j].Name
		}
		return out[i].DeviceID < out[j].DeviceID
	})
	return out
}

// DeviceIDs lists connected devices, for broadcasting.
func (a *Account) DeviceIDs() []string {
	var ids []string
	for id, d := range a.devices {
		if d.Connected {
			ids = append(ids, id)
		}
	}
	return ids
}

// Get returns a device by id. The caller holds the account lock.
func (a *Account) Get(id string) *Device { return a.devices[id] }
