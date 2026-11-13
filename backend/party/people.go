package party

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"sort"

	"github.com/KabirSinghBhatia/BitChord/backend/clock"
	"github.com/KabirSinghBhatia/BitChord/backend/config"
)

// People, not devices.
//
// A party counts people. Every device that proves it belongs to the same account is one
// person: one name, one picture, one seat, one role. The proof is a hash of a secret the
// devices share through the account itself (see the client's DeviceGroup); it is never
// sent to other members. What they see instead is PersonId, a one-way hash of it, which is
// only good for grouping.
//
// The proof has to be a secret and not an id. A user id is broadcast to every member, so
// anyone could claim the host's and inherit the host's role.

// MaxDevicesPerPerson bounds how many devices one person can bring into a party.
const MaxDevicesPerPerson = 5

// personIDFor gives the id members see for a person, different in every party: the same person
// is not recognisable from one party to the next by it. Without a proof a device is its own
// person, which is what every client that predates this behaves like.
func personIDFor(code, proof, memberID string) string {
	if proof == "" {
		return memberID
	}
	sum := sha256.Sum256([]byte("bitchord-person-v1:" + code + ":" + proof))
	return hex.EncodeToString(sum[:])[:16]
}

// siblingOf finds a member already holding the same proof, if any.
func (p *Party) siblingOf(proof string) *Member {
	if proof == "" {
		return nil
	}
	for _, m := range p.Members {
		if m.AccountKey != "" && subtle.ConstantTimeCompare([]byte(m.AccountKey), []byte(proof)) == 1 {
			return m
		}
	}
	return nil
}

// DevicesOf lists the members that are the same person, oldest first.
func (p *Party) DevicesOf(personID string) []*Member {
	var out []*Member
	for _, m := range p.Members {
		if m.PersonId == personID {
			out = append(out, m)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].JoinedAtMs < out[j].JoinedAtMs })
	return out
}

// PersonCount is how many people are in the party. It is what the size limit counts.
func (p *Party) PersonCount() int {
	seen := make(map[string]struct{}, len(p.Members))
	for _, m := range p.Members {
		seen[m.PersonId] = struct{}{}
	}
	return len(seen)
}

// syncPerson makes every device of m's person show the same name and picture.
func (p *Party) syncPerson(m *Member) {
	for _, other := range p.Members {
		if other.PersonId == m.PersonId {
			other.UserId = m.UserId
			other.DisplayName = m.DisplayName
			other.AvatarUrl = m.AvatarUrl
		}
	}
}

// Join adds a device the way every client did before people existed: as a person of its own.
func (p *Party) Join(userId, deviceId, displayName string, avatarUrl *string) (*Member, error) {
	return p.JoinAs(userId, deviceId, displayName, avatarUrl, "", "")
}

// JoinAs adds a device. With an account proof that another member already holds, it joins that
// person: same name, same role, and no extra seat. The caller holds the party lock.
func (p *Party) JoinAs(
	userId, deviceId, displayName string,
	avatarUrl *string,
	accountProof, deviceName string,
) (*Member, error) {
	now := clock.NowMs()

	if p.IsBanned(userId, deviceId, accountProof) {
		return nil, NewPartyError(403, "removed", "The host removed you from this party.")
	}

	// A device coming back keeps its place.
	for _, m := range p.Members {
		if m.DeviceId != deviceId {
			continue
		}
		if accountProof != "" && m.AccountKey != accountProof {
			// The device is now signed in as somebody else (or has only now got a proof): it is
			// not the same person coming back. It leaves as who it was and joins afresh below, so
			// the seat limit, the removed list and the host role apply to who it is now. Renaming
			// it in place used to skip all three and left a person half-host, half-guest.
			p.Remove(m.MemberId)
			break
		}
		m.DisplayName = displayName
		m.AvatarUrl = avatarUrl
		m.UserId = userId
		m.DeviceName = deviceName
		m.LastSeenMs = now
		m.Token = randomToken(24)
		p.syncPerson(m)
		p.Touch()
		return m, nil
	}

	sibling := p.siblingOf(accountProof)
	switch {
	case sibling != nil:
		if len(p.DevicesOf(sibling.PersonId)) >= MaxDevicesPerPerson {
			return nil, NewPartyError(409, "too_many_devices",
				fmt.Sprintf("That account already has %d devices in this party.", MaxDevicesPerPerson))
		}
	case p.PersonCount() >= p.MaxMembers:
		return nil, NewPartyError(409, "party_full", fmt.Sprintf("This party is full (%d people).", p.MaxMembers))
	}

	m := &Member{
		MemberId:          randomHex(8),
		UserId:            userId,
		DeviceId:          deviceId,
		DeviceName:        deviceName,
		DisplayName:       displayName,
		AvatarUrl:         avatarUrl,
		Token:             randomToken(24),
		AccountKey:        accountProof,
		IsHost:            len(p.Members) == 0,
		Connected:         false,
		JoinedAtMs:        now,
		LastSeenMs:        now,
		ControlBudget:     config.ControlRatePerSecond,
		ControlBudgetAtMs: now,
		FrameBudget:       config.FrameRatePerSecond,
		FrameBudgetAtMs:   now,
	}
	m.PersonId = personIDFor(p.Code, accountProof, m.MemberId)
	if sibling != nil {
		m.PersonId = sibling.PersonId
		m.IsHost = sibling.IsHost
		m.UserId = sibling.UserId
		m.DisplayName = sibling.DisplayName
		m.AvatarUrl = sibling.AvatarUrl
	}
	p.Members[m.MemberId] = m
	p.Touch()
	return m, nil
}

// RemovePerson removes a member and every other device of the same person, returning them.
func (p *Party) RemovePerson(memberID string) []*Member {
	target, ok := p.Members[memberID]
	if !ok {
		return nil
	}
	var removed []*Member
	for _, m := range p.DevicesOf(target.PersonId) {
		if p.Remove(m.MemberId) != nil {
			removed = append(removed, m)
		}
	}
	return removed
}

// promoteHost hands the host role to a person when none is left, picking whoever joined
// first rather than whoever a map happens to list first.
func (p *Party) promoteHost() {
	var earliest *Member
	for _, m := range p.Members {
		if m.IsHost {
			return
		}
		if earliest == nil || m.JoinedAtMs < earliest.JoinedAtMs {
			earliest = m
		}
	}
	if earliest == nil {
		return
	}
	for _, m := range p.DevicesOf(earliest.PersonId) {
		m.IsHost = true
	}
}

// stamp records who asked for a track. AutoPlay's own suggestions carry no person.
func stamp(t *Track, m *Member) {
	if t == nil || t.FromAutoplay {
		return
	}
	t.AddedBy = m.PersonId
	t.AddedByName = m.DisplayName
	t.AddedByAvatar = m.AvatarUrl
}

// StampAdded marks tracks a member is adding now. What a client claims about who added a
// track is never read, so it cannot be forged.
func (p *Party) StampAdded(tracks []*Track, m *Member) {
	for _, t := range tracks {
		stamp(t, m)
	}
}

// StampQueue marks the tracks of a queue a member has sent whole. A track that was already
// in the queue keeps whoever added it; only the ones that are new are credited to the sender.
func (p *Party) StampQueue(tracks []*Track, m *Member) {
	pool := make(map[string][]*Track, len(p.Playback.Queue))
	for _, old := range p.Playback.Queue {
		pool[old.VideoId] = append(pool[old.VideoId], old)
	}
	for _, t := range tracks {
		if t.FromAutoplay {
			continue
		}
		if list := pool[t.VideoId]; len(list) > 0 {
			old := list[0]
			pool[t.VideoId] = list[1:]
			t.AddedBy, t.AddedByName, t.AddedByAvatar = old.AddedBy, old.AddedByName, old.AddedByAvatar
			continue
		}
		stamp(t, m)
	}
}

// Removed people stay removed.
//
// A kick used to be remembered only by the device that was kicked, which is no wall at all for a
// person with a second device: it joins as the same person, with the host's door open. The party
// remembers who it removed for as long as it lives. A person is known by the proof their devices
// share (the strong key); the user id and device id catch a client that sends no proof. Nothing
// outlives the party, so there is no list to keep or to leak.

// Ban records every device in removed so none of them, and no other device of the same person,
// can come back. The caller holds the party lock.
func (p *Party) Ban(removed []*Member) {
	if p.banned == nil {
		p.banned = make(map[string]struct{})
	}
	for _, m := range removed {
		if m.AccountKey != "" {
			p.banned["p:"+m.PersonId] = struct{}{}
		}
		p.banned["u:"+m.UserId] = struct{}{}
		p.banned["d:"+m.DeviceId] = struct{}{}
	}
}

// IsBanned reports whether a device asking to join was removed from this party. The caller holds
// the party lock.
func (p *Party) IsBanned(userID, deviceID, accountProof string) bool {
	if len(p.banned) == 0 {
		return false
	}
	if accountProof != "" {
		if _, ok := p.banned["p:"+personIDFor(p.Code, accountProof, "")]; ok {
			return true
		}
	}
	if _, ok := p.banned["u:"+userID]; ok {
		return true
	}
	_, ok := p.banned["d:"+deviceID]
	return ok
}
