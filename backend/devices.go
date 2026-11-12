package main

import (
	"net/http"
	"regexp"

	"github.com/KabirSinghBhatia/BitChord/backend/config"
	"github.com/KabirSinghBhatia/BitChord/backend/party"
	"github.com/KabirSinghBhatia/BitChord/backend/protocol"
)

// groupIDPattern is what a client sends: a lower-case SHA-256 in hex. Anything
// else is refused so a group id can never be a short or guessable string.
var groupIDPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// personalRoomMaxMembers is generous on purpose: one person's own devices are
// not a crowd, and a refusal here would strand a device outside its own room.
const personalRoomMaxMembers = 10

type devicesJoinRequest struct {
	protocol.JoinRequest
	GroupId string `json:"groupId"`
}

// handleDevicesJoin puts a device in its owner's personal room, creating the
// room when it is the first. The answer has the shape of a party join, so a
// client opens its socket and runs the rest exactly as it does for any party.
//
// The group id is the whole credential. It is never echoed back, and the room it
// opens is unreachable by code: see handleJoinParty and handlePreviewParty.
func handleDevicesJoin(w http.ResponseWriter, r *http.Request) {
	var req devicesJoinRequest
	if !decodeJSONBody(w, r, &req) {
		return
	}
	if err := req.JoinRequest.Validate(); err != nil {
		jsonError(w, http.StatusUnprocessableEntity, "validation_error", err.Error())
		return
	}
	if !groupIDPattern.MatchString(req.GroupId) {
		jsonError(w, http.StatusUnprocessableEntity, "validation_error", "groupId must be a lower-case SHA-256 in hex.")
		return
	}

	p, created, err := store.PersonalRoom(req.GroupId, config.MaxParties, personalRoomMaxMembers)
	if err != nil {
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusInternalServerError, "server_error", "Failed to open the room.")
		return
	}
	// Only the creation is rate limited: reconnecting devices rejoin an existing
	// room all day and must not be counted as new parties.
	if created && !createLimiter.Allow(clientIP(r)) {
		store.Drop(p.Code)
		jsonError(w, http.StatusTooManyRequests, "create_rate_limited", "Too many rooms at once. Please try again shortly.")
		return
	}

	p.Lock()
	m, err := p.JoinPersonal(req.UserId, req.DeviceId, req.DisplayName, req.AvatarUrl)
	if err != nil {
		p.Unlock()
		if pe, ok := err.(*party.PartyError); ok {
			jsonError(w, pe.Status, pe.Code, pe.Message)
			return
		}
		jsonError(w, http.StatusInternalServerError, "server_error", err.Error())
		return
	}
	partyWire := p.ToWire()
	youWire := m.ToWire()
	token := m.Token
	code := p.Code
	membersF := membersFrame(p)
	p.Unlock()

	hubInst.Broadcast(code, membersF, "")
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"code":  code,
		"token": token,
		"you":   youWire,
		"party": partyWire,
	})
}
