package party

import "sync"

// Personal rooms are how one person's own devices act as one: every device that
// presents the same group id lands in the same room, with one of them playing
// and the rest following and controlling it.
//
// A group id is a hash of a secret the devices share through the person's own
// account (see the client's "My devices"), so it is a bearer credential and is
// never broadcast, logged or returned. The room itself is an ordinary Party, so
// sockets, controls, queue and sync are exactly what a shared party uses; what
// differs is only how it is found (by group id, never by code) and that audio is
// exclusive: whoever last asked to play here is the only member that plays.

// personal tracks which parties are personal rooms. Kept beside the store
// rather than inside it so the store's own bookkeeping is untouched.
var personal = struct {
	mu      sync.RWMutex
	byGroup map[string]string // group id -> party code
	codes   map[string]bool   // party code -> true
}{
	byGroup: map[string]string{},
	codes:   map[string]bool{},
}

// IsPersonal reports whether a code belongs to a personal room. Such a room
// refuses to be found by code, so a guessed or leaked code opens nothing.
func IsPersonal(code string) bool {
	personal.mu.RLock()
	defer personal.mu.RUnlock()
	return personal.codes[code]
}

// PersonalRoom returns the live room for a group, creating it if there is none.
// created reports whether this call made it, which the caller rate limits.
func (s *PartyStore) PersonalRoom(groupID string, maxParties, maxMembers int) (p *Party, created bool, err error) {
	personal.mu.Lock()
	defer personal.mu.Unlock()
	if code, ok := personal.byGroup[groupID]; ok {
		if existing := s.Find(code); existing != nil {
			return existing, false, nil
		}
		delete(personal.byGroup, groupID)
		delete(personal.codes, code)
	}
	p, err = s.CreateWithLimits(maxParties, maxMembers)
	if err != nil {
		return nil, false, err
	}
	p.Personal = true
	// Music keeps going on its own, as it does on a single device; the player's toggle changes it.
	p.Playback.AutoplayEnabled = true
	personal.byGroup[groupID] = p.Code
	personal.codes[p.Code] = true
	return p, true, nil
}

// JoinPersonal joins a device to a personal room. The first device in plays;
// every later one arrives as a controller, and a device that is rejoining keeps
// whichever role it had. The caller holds the party lock.
func (p *Party) JoinPersonal(userID, deviceID, displayName string, avatarURL *string) (*Member, error) {
	known := false
	for _, m := range p.Members {
		if m.DeviceId == deviceID {
			known = true
			break
		}
	}
	// Only a device that follows the room can already be playing it.
	others := 0
	for _, other := range p.Members {
		if other.DeviceId != deviceID && !other.Detached && !other.Silent {
			others++
		}
	}
	m, err := p.Join(userID, deviceID, displayName, avatarURL)
	if err != nil {
		return nil, err
	}
	if !known {
		m.Silent = others > 0
	}
	return m, nil
}

// PlayHere makes member the only device in a personal room that plays aloud.
// The caller holds the party lock.
func (p *Party) PlayHere(member *Member) {
	for _, m := range p.Members {
		if m.Detached {
			continue
		}
		m.Silent = m.MemberId != member.MemberId
	}
	member.Detached = false
	p.Touch()
}

// SetIndependent lets a device play its own music instead of following the room
// (enabled), or follow it again. Rejoining makes it a controller unless nothing
// else is playing the room, in which case it takes over so the room is not silent.
// The caller holds the party lock.
func (p *Party) SetIndependent(member *Member, enabled bool) {
	member.Detached = enabled
	if enabled {
		member.Silent = false
	} else {
		othersPlay := false
		for _, m := range p.Members {
			if m.MemberId != member.MemberId && !m.Detached && !m.Silent {
				othersPlay = true
			}
		}
		member.Silent = othersPlay
	}
	p.Touch()
}
