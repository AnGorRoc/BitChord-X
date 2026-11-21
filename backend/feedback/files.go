package feedback

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sync"
)

var shaPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// fileStore keeps attachment files on disk, named by the SHA-256 of their content, so the same
// screenshot sent twice is stored once: dir/ab/abcdef….png and its thumbnail dir/ab/abcdef…_t.jpg.
//
// Writing a file and recording it, and checking a file is unused and removing it, each happen
// under mu (shared and exclusive), so a cleanup can never remove a file between an upload
// writing it and the upload's row being committed.
type fileStore struct {
	dir string
	mu  sync.RWMutex
}

func newFileStore(dir string) (*fileStore, error) {
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return nil, fmt.Errorf("feedback dir: %w", err)
	}
	return &fileStore{dir: dir}, nil
}

func contentSHA(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func (f *fileStore) path(sha, suffix string) (string, error) {
	if !shaPattern.MatchString(sha) {
		return "", errors.New("bad sha")
	}
	return filepath.Join(f.dir, sha[:2], sha+suffix), nil
}

// write stores data (and its thumbnail) unless a file with that content is already there.
// The caller holds f.mu for reading.
func (f *fileStore) write(sha, ext string, data, thumb []byte) error {
	p, err := f.path(sha, "."+ext)
	if err != nil {
		return err
	}
	if err := writeOnce(p, data); err != nil {
		return err
	}
	if thumb != nil {
		tp, _ := f.path(sha, "_t.jpg")
		if err := writeOnce(tp, thumb); err != nil {
			return err
		}
	}
	return nil
}

// writeOnce writes through a temporary file and a rename, so a reader never sees half a file
// and two uploads of the same content cannot interleave.
func writeOnce(p string, data []byte) error {
	if _, err := os.Stat(p); err == nil {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o750); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(p), ".up-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		os.Remove(name)
		return err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(name)
		return err
	}
	if err := os.Rename(name, p); err != nil {
		os.Remove(name)
		return err
	}
	return nil
}

// remove deletes every file stored for sha. The caller holds f.mu for writing and has checked
// that no attachment uses it any more.
func (f *fileStore) remove(sha string) {
	if !shaPattern.MatchString(sha) {
		return
	}
	matches, _ := filepath.Glob(filepath.Join(f.dir, sha[:2], sha+"*"))
	for _, m := range matches {
		_ = os.Remove(m)
	}
}
