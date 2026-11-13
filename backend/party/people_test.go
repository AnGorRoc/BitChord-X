package party

import (
	"fmt"
	"strings"
	"testing"
)

var (
	proofAna  = strings.Repeat("a", 64)
	proofBeto = strings.Repeat("b", 64)
)

func joinAs(t *testing.T, p *Party, user, device, name, proof, deviceName string) *Member {
	t.Helper()
	m, err := p.JoinAs(user, device, name, nil, proof, deviceName)
	if err != nil {
		t.Fatalf("join %s: %v", device, err)
	}
	return m
}

func TestDevicesOfOneAccountAreOnePerson(t *testing.T) {
	p := NewPartyWithMaxMembers("PEOPLE", 2)
	phone := joinAs(t, p, "ana", "phone", "Ana", proofAna, "Pixel")
	pc := joinAs(t, p, "ana", "pc", "Someone else", proofAna, "IOP")

	if phone.PersonId == "" || phone.PersonId != pc.PersonId {
		t.Fatalf("one account must be one person: %q vs %q", phone.PersonId, pc.PersonId)
	}
	if pc.DisplayName != "Ana" {
		t.Fatalf("the second device takes the person's name, got %q", pc.DisplayName)
	}
	if !phone.IsHost || !pc.IsHost {
		t.Fatalf("every device of the host is the host")
	}
	if p.PersonCount() != 1 || len(p.Members) != 2 {
		t.Fatalf("one person on two devices: people=%d devices=%d", p.PersonCount(), len(p.Members))
	}
	if strings.Contains(phone.PersonId, proofAna) {
		t.Fatalf("the proof must never be derivable from what members see")
	}
}

func TestSecondDeviceTakesNoSeat(t *testing.T) {
	p := NewPartyWithMaxMembers("SEATS", 2)
	joinAs(t, p, "ana", "phone", "Ana", proofAna, "")
	joinAs(t, p, "ana", "pc", "Ana", proofAna, "")
	// Two seats, one used by Ana's two devices: Beto still fits...
	joinAs(t, p, "beto", "b1", "Beto", proofBeto, "")
	// ...and a third person does not.
	if _, err := p.JoinAs("cy", "c1", "Cy", nil, strings.Repeat("c", 64), ""); err == nil {
		t.Fatalf("a third person must find the party full")
	}
}

func TestAClaimedUserIdDoesNotInheritTheHost(t *testing.T) {
	p := NewPartyWithMaxMembers("FORGE", 5)
	host := joinAs(t, p, "ana", "phone", "Ana", proofAna, "")
	// Somebody who read Ana's userId from the members frame but lacks her proof.
	imposter := joinAs(t, p, "ana", "evil", "Ana", strings.Repeat("e", 64), "")
	if imposter.PersonId == host.PersonId || imposter.IsHost {
		t.Fatalf("a user id alone must never join a person or inherit the host")
	}
	// ...nor without any proof at all.
	bare := joinAs(t, p, "ana", "bare", "Ana", "", "")
	if bare.PersonId == host.PersonId || bare.IsHost {
		t.Fatalf("no proof means a person of one's own")
	}
}

func TestClientsWithoutProofBehaveAsBefore(t *testing.T) {
	p := NewPartyWithMaxMembers("OLD", 3)
	a, _ := p.Join("u1", "d1", "A", nil)
	b, _ := p.Join("u1", "d2", "A", nil)
	if a.PersonId == b.PersonId || p.PersonCount() != 2 {
		t.Fatalf("original clients: each device is a person")
	}
}

func TestADevicePerPersonCap(t *testing.T) {
	p := NewPartyWithMaxMembers("CAP", 5)
	for i := 0; i < MaxDevicesPerPerson; i++ {
		joinAs(t, p, "ana", fmt.Sprintf("d%d", i), "Ana", proofAna, "")
	}
	if _, err := p.JoinAs("ana", "one-too-many", "Ana", nil, proofAna, ""); err == nil {
		t.Fatalf("a person must not bring unlimited devices")
	}
}

func TestRejoiningADeviceKeepsItsSeatAndPerson(t *testing.T) {
	p := NewPartyWithMaxMembers("REJOIN", 2)
	first := joinAs(t, p, "ana", "phone", "Ana", proofAna, "Pixel")
	again := joinAs(t, p, "ana", "phone", "Ana B", proofAna, "Pixel 2")
	if first.MemberId != again.MemberId || len(p.Members) != 1 {
		t.Fatalf("a rejoining device must not duplicate")
	}
	if again.DeviceName != "Pixel 2" || again.DisplayName != "Ana B" {
		t.Fatalf("rejoin refreshes the device's details")
	}
}

func TestRenamingAPersonRenamesAllTheirDevices(t *testing.T) {
	p := NewPartyWithMaxMembers("NAME", 3)
	joinAs(t, p, "ana", "phone", "Ana", proofAna, "")
	pc := joinAs(t, p, "ana", "pc", "Ana", proofAna, "")
	joinAs(t, p, "ana", "phone", "Ana Maria", proofAna, "")
	if pc.DisplayName != "Ana Maria" {
		t.Fatalf("every device of a person shows the same name, got %q", pc.DisplayName)
	}
}

func TestRemovingPersonRemovesAllDevicesAndPromotesNext(t *testing.T) {
	p := NewPartyWithMaxMembers("LEAVE", 5)
	phone := joinAs(t, p, "ana", "phone", "Ana", proofAna, "")
	joinAs(t, p, "ana", "pc", "Ana", proofAna, "")
	b1 := joinAs(t, p, "beto", "b1", "Beto", proofBeto, "")
	b2 := joinAs(t, p, "beto", "b2", "Beto", proofBeto, "")
	b1.JoinedAtMs, b2.JoinedAtMs = 10, 20

	removed := p.RemovePerson(phone.MemberId)
	if len(removed) != 2 || len(p.Members) != 2 {
		t.Fatalf("removing Ana removes both her devices: removed=%d left=%d", len(removed), len(p.Members))
	}
	if !b1.IsHost || !b2.IsHost {
		t.Fatalf("the host role passes to the next person, on all their devices")
	}
}

func TestSettingPartySizeCountsPeople(t *testing.T) {
	p := NewPartyWithMaxMembers("SIZE", 5)
	host := joinAs(t, p, "ana", "phone", "Ana", proofAna, "")
	joinAs(t, p, "ana", "pc", "Ana", proofAna, "")
	joinAs(t, p, "ana", "tablet", "Ana", proofAna, "")
	if err := p.SetMaxMembers(host, 2); err != nil {
		t.Fatalf("three devices of one person fit a party of two people: %v", err)
	}
}

func TestWireCarriesPersonAndNotTheProof(t *testing.T) {
	p := NewPartyWithMaxMembers("WIRE", 3)
	m := joinAs(t, p, "ana", "phone", "Ana", proofAna, "Pixel")
	wire := m.ToWire()
	if wire["personId"] != m.PersonId || wire["deviceName"] != "Pixel" {
		t.Fatalf("wire: %v", wire)
	}
	for k, v := range wire {
		if s, ok := v.(string); ok && strings.Contains(s, proofAna) {
			t.Fatalf("the proof leaked through %q", k)
		}
	}
}

func TestTracksRememberWhoAddedThem(t *testing.T) {
	p := NewPartyWithMaxMembers("ADDED", 3)
	ana := joinAs(t, p, "ana", "phone", "Ana", proofAna, "")
	beto := joinAs(t, p, "beto", "b1", "Beto", proofBeto, "")

	// A client may claim anything; the server ignores it.
	forged := TrackFromWire(map[string]interface{}{"videoId": "x", "addedBy": "somebody", "addedByName": "Forged"})
	p.StampAdded([]*Track{forged}, ana)
	if forged.AddedBy != ana.PersonId || forged.AddedByName != "Ana" {
		t.Fatalf("forged credit survived: %+v", forged)
	}

	first := &Track{VideoId: "a"}
	p.StampAdded([]*Track{first}, ana)
	p.Playback.AddUpcoming(&ana.MemberId, []*Track{first}, false)

	// Beto sends the whole queue back reordered with one song of his own: Ana keeps hers.
	resend := []*Track{{VideoId: "b"}, {VideoId: "a"}}
	p.StampQueue(resend, beto)
	if resend[0].AddedBy != beto.PersonId || resend[1].AddedBy != ana.PersonId {
		t.Fatalf("credit must stay with whoever queued it: %+v %+v", resend[0], resend[1])
	}
}

func TestAutoplayTracksCarryNoPerson(t *testing.T) {
	p := NewPartyWithMaxMembers("AUTO", 3)
	ana := joinAs(t, p, "ana", "phone", "Ana", proofAna, "")
	suggested := &Track{VideoId: "s", FromAutoplay: true}
	p.StampAdded([]*Track{suggested}, ana)
	if suggested.AddedBy != "" {
		t.Fatalf("AutoPlay suggestions are nobody's")
	}
}

func TestAddedByStaysAfterThePersonLeaves(t *testing.T) {
	p := NewPartyWithMaxMembers("GONE", 3)
	joinAs(t, p, "ana", "phone", "Ana", proofAna, "")
	beto := joinAs(t, p, "beto", "b1", "Beto", proofBeto, "")
	tr := &Track{VideoId: "a"}
	p.StampAdded([]*Track{tr}, beto)
	p.Playback.AddUpcoming(&beto.MemberId, []*Track{tr}, false)
	p.RemovePerson(beto.MemberId)
	if p.Playback.Queue[0].AddedByName != "Beto" {
		t.Fatalf("the name must outlive the person")
	}
}

func TestRemovedPersonCannotReturnOnAnotherDevice(t *testing.T) {
	p := NewPartyWithMaxMembers("BANS", 5)
	joinAs(t, p, "host", "h1", "Host", proofAna, "")
	beto := joinAs(t, p, "beto", "b-phone", "Beto", proofBeto, "Pixel")

	p.Ban(p.RemovePerson(beto.MemberId))

	// The same person on a device the party never saw is still the same person.
	if _, err := p.JoinAs("other-user", "b-pc", "Beto", nil, proofBeto, "IOP"); err == nil {
		t.Fatalf("a second device of a removed person must be refused")
	} else if pe, ok := err.(*PartyError); !ok || pe.Code != "removed" || pe.Status != 403 {
		t.Fatalf("expected a 403 removed error, got %v", err)
	}
	// So is the device that was removed, and the account it signed in with, with no proof at all.
	if _, err := p.JoinAs("x", "b-phone", "Beto", nil, "", ""); err == nil {
		t.Fatalf("the removed device itself must be refused")
	}
	if _, err := p.JoinAs("beto", "new-device", "Beto", nil, "", ""); err == nil {
		t.Fatalf("the removed user id must be refused")
	}
	// Somebody else is not caught by it.
	if _, err := p.JoinAs("ceci", "c1", "Ceci", nil, strings.Repeat("c", 64), ""); err != nil {
		t.Fatalf("an unrelated person must still be able to join: %v", err)
	}
}

func TestNobodyIsBannedUntilRemoved(t *testing.T) {
	p := NewPartyWithMaxMembers("NOBAN", 5)
	if p.IsBanned("beto", "b1", proofBeto) {
		t.Fatalf("a party that removed nobody bans nobody")
	}
}
