package account

import (
	"strings"
	"testing"
)

var (
	proofAna  = strings.Repeat("a", 64)
	proofBeto = strings.Repeat("b", 64)
)

const now = int64(1_000_000)

func connect(t *testing.T, s *Store, proof, id, name, kind string) (*Account, *Device, string) {
	t.Helper()
	acc, d, token, err := s.Connect(proof, id, name, kind, now)
	if err != nil {
		t.Fatalf("connect %s: %v", id, err)
	}
	acc.Lock()
	acc.SetConnected(d, true, now)
	acc.Unlock()
	return acc, d, token
}

func TestDevicesOfTheSameAccountSeeEachOther(t *testing.T) {
	s := NewStore()
	acc, _, _ := connect(t, s, proofAna, "phone", "Pixel", "phone")
	acc2, _, _ := connect(t, s, proofAna, "pc", "IOP", "computer")
	if acc != acc2 {
		t.Fatalf("one proof, one account")
	}
	acc.Lock()
	defer acc.Unlock()
	list := acc.List()
	if len(list) != 2 || list[0].Name != "IOP" || list[1].Name != "Pixel" {
		t.Fatalf("both devices, sorted by name: %+v", list)
	}
}

func TestAnotherAccountSeesNothingOfIt(t *testing.T) {
	s := NewStore()
	connect(t, s, proofAna, "phone", "Pixel", "phone")
	other, _, _ := connect(t, s, proofBeto, "b-phone", "Beto", "phone")
	other.Lock()
	defer other.Unlock()
	if len(other.List()) != 1 {
		t.Fatalf("accounts must be separate")
	}
}

func TestProofMustBeAHash(t *testing.T) {
	s := NewStore()
	for _, bad := range []string{"", "short", strings.Repeat("A", 64), strings.Repeat("g", 64)} {
		if _, _, _, err := s.Connect(bad, "d", "n", "phone", now); err == nil {
			t.Fatalf("%q must be refused", bad)
		}
	}
}

func TestAccountIdRevealsNothingOfTheProof(t *testing.T) {
	id := AccountID(proofAna)
	if strings.Contains(id, proofAna[:8]) || len(id) != 20 {
		t.Fatalf("account id %q", id)
	}
}

func TestTokensAuthenticateOnlyTheirOwnDevice(t *testing.T) {
	s := NewStore()
	_, d, token := connect(t, s, proofAna, "phone", "Pixel", "phone")
	_, got, err := s.Authenticate(token)
	if err != nil || got != d {
		t.Fatalf("a token finds its device: %v", err)
	}
	for _, bad := range []string{"", "nodot", "unknown.abc", strings.Split(token, ".")[0] + ".wrong"} {
		if _, _, err := s.Authenticate(bad); err == nil {
			t.Fatalf("%q must not authenticate", bad)
		}
	}
}

func TestReconnectingRefreshesTheTokenAndKeepsTheDevice(t *testing.T) {
	s := NewStore()
	_, d1, tok1 := connect(t, s, proofAna, "phone", "Pixel", "phone")
	_, d2, tok2 := connect(t, s, proofAna, "phone", "Pixel 2", "phone")
	if d1 != d2 || tok1 == tok2 || d2.Name != "Pixel 2" {
		t.Fatalf("same device, new token, new name")
	}
	if _, _, err := s.Authenticate(tok1); err == nil {
		t.Fatalf("the old token must stop working")
	}
}

func TestDeviceCap(t *testing.T) {
	s := NewStore()
	for i := 0; i < MaxDevices; i++ {
		connect(t, s, proofAna, strings.Repeat("d", i+1), "n", "phone")
	}
	if _, _, _, err := s.Connect(proofAna, "one-more", "n", "phone", now); err == nil {
		t.Fatalf("the device cap must hold")
	}
}

func TestTypeFallsBackToOther(t *testing.T) {
	s := NewStore()
	_, d, _ := connect(t, s, proofAna, "x", "X", "toaster")
	if d.Type != "other" {
		t.Fatalf("got %q", d.Type)
	}
}

func TestPublishingNowPlaying(t *testing.T) {
	s := NewStore()
	acc, d, _ := connect(t, s, proofAna, "phone", "Pixel", "phone")
	acc.Lock()
	defer acc.Unlock()
	acc.Publish(d, &NowPlaying{VideoId: "v", Title: "T", IsPlaying: true, PositionMs: -5}, now+10)
	if d.NowPlaying == nil || d.NowPlaying.PositionMs != 0 || d.NowPlaying.AnchorMs != now+10 {
		t.Fatalf("%+v", d.NowPlaying)
	}
	acc.Publish(d, &NowPlaying{}, now+20)
	if d.NowPlaying != nil {
		t.Fatalf("an empty report clears it")
	}
}

func TestAttachMakesARemoteAndASecondTakesOver(t *testing.T) {
	s := NewStore()
	acc, phone, _ := connect(t, s, proofAna, "phone", "Pixel", "phone")
	_, tablet, _ := connect(t, s, proofAna, "tablet", "Tab", "tablet")
	_, pc, _ := connect(t, s, proofAna, "pc", "IOP", "computer")
	acc.Lock()
	defer acc.Unlock()

	if err := acc.Attach(phone, "pc"); err != nil {
		t.Fatal(err)
	}
	if pc.ControlledBy != "phone" || phone.Controlling != "pc" {
		t.Fatalf("linked")
	}
	// The tablet picks the PC too: the phone is let go.
	if err := acc.Attach(tablet, "pc"); err != nil {
		t.Fatal(err)
	}
	if pc.ControlledBy != "tablet" || phone.Controlling != "" {
		t.Fatalf("a second remote takes over: %q %q", pc.ControlledBy, phone.Controlling)
	}
	// A remote that moves to another device lets the first one go.
	if err := acc.Attach(tablet, "phone"); err != nil {
		t.Fatal(err)
	}
	if pc.ControlledBy != "" || phone.ControlledBy != "tablet" {
		t.Fatalf("moving the remote frees the old target")
	}
}

func TestAttachRefusals(t *testing.T) {
	s := NewStore()
	acc, phone, _ := connect(t, s, proofAna, "phone", "Pixel", "phone")
	_, pc, _ := connect(t, s, proofAna, "pc", "IOP", "computer")
	acc.Lock()
	defer acc.Unlock()
	if acc.Attach(phone, "phone") == nil {
		t.Fatalf("not itself")
	}
	if acc.Attach(phone, "ghost") == nil {
		t.Fatalf("not an unknown device")
	}
	acc.SetConnected(pc, false, now)
	if acc.Attach(phone, "pc") == nil {
		t.Fatalf("not a device that is away")
	}
}

func TestDisconnectingEndsTheLinks(t *testing.T) {
	s := NewStore()
	acc, phone, _ := connect(t, s, proofAna, "phone", "Pixel", "phone")
	_, pc, _ := connect(t, s, proofAna, "pc", "IOP", "computer")
	acc.Lock()
	defer acc.Unlock()
	_ = acc.Attach(phone, "pc")
	acc.SetConnected(phone, false, now+5)
	if pc.ControlledBy != "" || phone.Controlling != "" {
		t.Fatalf("a remote that leaves frees its target")
	}
	// And the other way round.
	acc.SetConnected(phone, true, now+6)
	_ = acc.Attach(phone, "pc")
	acc.SetConnected(pc, false, now+7)
	if phone.Controlling != "" {
		t.Fatalf("a target that leaves frees its remote")
	}
}

func TestRoutingRules(t *testing.T) {
	s := NewStore()
	acc, phone, _ := connect(t, s, proofAna, "phone", "Pixel", "phone")
	_, pc, _ := connect(t, s, proofAna, "pc", "IOP", "computer")
	acc.Lock()
	defer acc.Unlock()

	if _, err := acc.Route(phone, "pc", "pause", 0); err == nil {
		t.Fatalf("not allowed before linking")
	}
	if _, err := acc.Route(phone, "pc", "playQueue", 3); err != nil {
		t.Fatalf("handing music over starts the link, so it needs none: %v", err)
	}
	_ = acc.Attach(phone, "pc")
	if got, err := acc.Route(phone, "pc", "pause", 0); err != nil || got != pc {
		t.Fatalf("a remote may command its target: %v", err)
	}
	if _, err := acc.Route(phone, "pc", "explode", 0); err == nil {
		t.Fatalf("unknown commands are refused")
	}
	if _, err := acc.Route(phone, "pc", "playQueue", MaxPayloadItems+1); err == nil {
		t.Fatalf("a hand-over has a size limit")
	}
	if _, err := acc.Route(phone, "phone", "pause", 0); err == nil {
		t.Fatalf("not itself")
	}
	// The controlled device may ask its remote to hand the sound back.
	if _, err := acc.Route(pc, "phone", "release", 0); err != nil {
		t.Fatalf("release from the target: %v", err)
	}
	if _, err := acc.Route(pc, "phone", "pause", 0); err == nil {
		t.Fatalf("the target may not otherwise command its remote")
	}
}

func TestListNeverCarriesTokens(t *testing.T) {
	s := NewStore()
	acc, _, token := connect(t, s, proofAna, "phone", "Pixel", "phone")
	acc.Lock()
	defer acc.Unlock()
	for _, d := range acc.List() {
		if d.token != "" || strings.Contains(d.Name+d.DeviceID, token) {
			t.Fatalf("a token leaked")
		}
	}
}

func TestForgettingAndSweeping(t *testing.T) {
	s := NewStore()
	acc, d, _ := connect(t, s, proofAna, "phone", "Pixel", "phone")
	acc.Lock()
	acc.SetConnected(d, false, now)
	acc.Unlock()
	if s.Sweep(now+ForgetAfterMs-1) != 0 || s.Len() != 1 {
		t.Fatalf("a device that left recently is still listed as last seen")
	}
	if s.Sweep(now+ForgetAfterMs+1) != 1 || s.Len() != 0 {
		t.Fatalf("an account with nothing left goes away")
	}
}

func TestConnectedDevicesAreNeverForgotten(t *testing.T) {
	s := NewStore()
	connect(t, s, proofAna, "phone", "Pixel", "phone")
	if s.Sweep(now+10*ForgetAfterMs) != 0 {
		t.Fatalf("a connected device stays")
	}
}

func TestNewAccountsAreRefusedAtTheLimitButKnownOnesAreNot(t *testing.T) {
	s := NewStore()
	s.MaxAccounts = 1
	connect(t, s, proofAna, "phone", "Pixel", "phone")

	if _, _, _, err := s.Connect(proofBeto, "b1", "Beto", "phone", now); err != ErrServerBusy {
		t.Fatalf("a second account must be refused at the limit, got %v", err)
	}
	// A device of an account already held is never turned away by it.
	if _, _, _, err := s.Connect(proofAna, "pc", "IOP", "computer", now); err != nil {
		t.Fatalf("a known account must still get new devices in: %v", err)
	}
}

func TestALinkSurvivesABlipButNotAnAbsence(t *testing.T) {
	s := NewStore()
	acc, phone, _ := connect(t, s, proofAna, "phone", "Pixel", "phone")
	_, pc, _ := connect(t, s, proofAna, "pc", "IOP", "computer")
	acc.Lock()
	defer acc.Unlock()
	_ = acc.Attach(phone, "pc")

	// The remote's socket drops and comes back inside the grace: nothing was lost.
	blip := acc.MarkAway(phone, now+1)
	if phone.Controlling != "pc" || pc.ControlledBy != "phone" {
		t.Fatalf("a dropped socket must not end the link at once")
	}
	acc.SetConnected(phone, true, now+2)
	if acc.ReleaseIfAway(phone, blip) || phone.Controlling != "pc" {
		t.Fatalf("a device that came back keeps its link")
	}

	// The target stays away past the grace: now it is over, for both sides.
	stale := acc.MarkAway(pc, now+3)
	acc.SetConnected(pc, true, now+3)
	acc.MarkAway(pc, now+4) // a newer drop: the older ticket is no longer the one that counts
	if acc.ReleaseIfAway(pc, stale) {
		t.Fatalf("an older drop's timer must not end a newer blip's grace")
	}
	absence := acc.MarkAway(pc, now+5)
	if !acc.ReleaseIfAway(pc, absence) {
		t.Fatalf("a device still away when the grace ends releases its links")
	}
	if phone.Controlling != "" || pc.ControlledBy != "" {
		t.Fatalf("both ends are freed: %q %q", phone.Controlling, pc.ControlledBy)
	}
}

func TestARemoteIsNotASpeaker(t *testing.T) {
	s := NewStore()
	acc, phone, _ := connect(t, s, proofAna, "phone", "Pixel", "phone")
	_, pc, _ := connect(t, s, proofAna, "pc", "IOP", "computer")
	_, tablet, _ := connect(t, s, proofAna, "tab", "Tab", "tablet")
	acc.Lock()
	defer acc.Unlock()
	if err := acc.Attach(phone, "pc"); err != nil {
		t.Fatal(err)
	}
	// The phone now plays on the PC. Nobody can pick the phone as a speaker or hand it music, or
	// it would pass them on to the PC instead of playing them.
	if acc.Attach(tablet, "phone") == nil {
		t.Fatalf("a device that is somebody's remote cannot be picked")
	}
	if _, err := acc.Route(tablet, "phone", "playQueue", 1); err == nil {
		t.Fatalf("music cannot be handed to a remote")
	}
	// What its own target says back to it is how a snapshot or a pull arrives, and must pass.
	if _, err := acc.Route(pc, "phone", "playQueue", 1); err != nil {
		t.Fatalf("the controlled device answers its remote: %v", err)
	}
	_ = tablet
}
