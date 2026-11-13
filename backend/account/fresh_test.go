package account

import "testing"

// An app closed and opened again comes back with a socket marked fresh: the links it had belonged to
// a session it no longer has, so they end at once instead of surviving the reopen.
func TestAFreshStartEndsTheLinksAtOnce(t *testing.T) {
	s := NewStore()
	acc, phone, _ := connect(t, s, proofAna, "phone", "Pixel", "phone")
	_, pc, _ := connect(t, s, proofAna, "pc", "IOP", "computer")

	acc.Lock()
	defer acc.Unlock()
	if err := acc.Attach(pc, "phone"); err != nil {
		t.Fatalf("attach: %v", err)
	}
	acc.StartFresh(pc)
	if pc.Controlling != "" || phone.ControlledBy != "" {
		t.Fatalf("a fresh start keeps no link: pc controlling %q, phone controlled by %q", pc.Controlling, phone.ControlledBy)
	}
}

// The same device, controlled by another, starts fresh: the controller is let go too.
func TestAFreshStartAlsoEndsBeingControlled(t *testing.T) {
	s := NewStore()
	acc, phone, _ := connect(t, s, proofAna, "phone", "Pixel", "phone")
	_, pc, _ := connect(t, s, proofAna, "pc", "IOP", "computer")

	acc.Lock()
	defer acc.Unlock()
	if err := acc.Attach(pc, "phone"); err != nil {
		t.Fatalf("attach: %v", err)
	}
	acc.StartFresh(phone)
	if pc.Controlling != "" || phone.ControlledBy != "" {
		t.Fatalf("a fresh target keeps its remote: pc controlling %q, phone controlled by %q", pc.Controlling, phone.ControlledBy)
	}
}
