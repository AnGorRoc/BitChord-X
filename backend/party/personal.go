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
	others := len(p.Members)
	if known {
		others--
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
		m.Silent = m.MemberId != member.MemberId
	}
	p.Touch()
}
