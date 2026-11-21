package feedback

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"hash/crc32"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"testing"
)

func pngBytes(t *testing.T, w, h int, alpha bool) []byte {
	t.Helper()
	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			a := uint8(255)
			if alpha && x == 0 {
				a = 0
			}
			img.Set(x, y, color.NRGBA{uint8(x), uint8(y), 90, a})
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func jpegWithEXIF(t *testing.T) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 64, 48))
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, nil); err != nil {
		t.Fatal(err)
	}
	raw := buf.Bytes()
	// An APP1 "Exif" segment right after SOI, carrying a marker string to look for afterwards.
	payload := append([]byte("Exif\x00\x00"), []byte("GPS-SECRET-LOCATION")...)
	seg := []byte{0xFF, 0xE1, 0, 0}
	binary.BigEndian.PutUint16(seg[2:], uint16(len(payload)+2))
	out := append([]byte{}, raw[:2]...)
	out = append(out, seg...)
	out = append(out, payload...)
	return append(out, raw[2:]...)
}

func TestImageReencodedAndStripped(t *testing.T) {
	in := jpegWithEXIF(t)
	if !bytes.Contains(in, []byte("GPS-SECRET-LOCATION")) {
		t.Fatal("fixture lacks EXIF")
	}
	p, err := processUpload(in)
	if err != nil {
		t.Fatal(err)
	}
	if p.kind != AttachImage || p.mime != "image/jpeg" || p.width != 64 || p.height != 48 {
		t.Fatalf("%+v", p)
	}
	if bytes.Contains(p.data, []byte("GPS-SECRET-LOCATION")) {
		t.Fatal("EXIF survived")
	}
	if len(p.thumb) == 0 {
		t.Fatal("no thumbnail")
	}
}

func TestScreenshotStaysPNGAndIsScaled(t *testing.T) {
	p, err := processUpload(pngBytes(t, 5000, 100, true))
	if err != nil {
		t.Fatal(err)
	}
	if p.mime != "image/png" || p.width != storeMaxSide || p.height != 81 {
		t.Fatalf("%s %dx%d", p.mime, p.width, p.height)
	}
	cfg, err := jpeg.DecodeConfig(bytes.NewReader(p.thumb))
	if err != nil || cfg.Width != thumbSide {
		t.Fatalf("thumb %+v %v", cfg, err)
	}
}

func TestPolyglotLosesItsTail(t *testing.T) {
	// A valid PNG with an HTML page (or a zip) glued on: browsers or unzip tools could read the
	// tail. Re-encoding keeps the pixels only.
	in := append(pngBytes(t, 4, 4, false), []byte("<html><script>alert(1)</script></html>")...)
	p, err := processUpload(in)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(p.data, []byte("<script>")) {
		t.Fatal("appended payload survived")
	}
}

// pngHeaderOnly is a PNG whose IHDR claims w×h: a few dozen bytes that would decode to
// gigabytes. The CRC is correct, so the header itself parses.
func pngHeaderOnly(w, h uint32) []byte {
	ihdr := make([]byte, 13)
	binary.BigEndian.PutUint32(ihdr[0:], w)
	binary.BigEndian.PutUint32(ihdr[4:], h)
	ihdr[8], ihdr[9] = 8, 2 // 8-bit RGB
	chunk := append([]byte("IHDR"), ihdr...)
	var out bytes.Buffer
	out.WriteString("\x89PNG\r\n\x1a\n")
	_ = binary.Write(&out, binary.BigEndian, uint32(13))
	out.Write(chunk)
	_ = binary.Write(&out, binary.BigEndian, crc32.ChecksumIEEE(chunk))
	return out.Bytes()
}

func TestDecompressionBombRefusedFromHeader(t *testing.T) {
	if _, err := processUpload(pngHeaderOnly(30000, 30000)); !errors.Is(err, errTooLarge) {
		t.Fatalf("got %v", err)
	}
	if _, err := processUpload(pngHeaderOnly(8000, 8000)); !errors.Is(err, errTooLarge) {
		t.Fatalf("64 MP: got %v", err)
	}
	if _, err := processUpload(pngHeaderOnly(100, 100)); !errors.Is(err, errCorrupt) {
		t.Fatalf("truncated small PNG: got %v", err)
	}
}

func TestWebPDecodes(t *testing.T) {
	// 1×1 lossless WebP.
	data, _ := base64.StdEncoding.DecodeString("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==")
	p, err := processUpload(data)
	if err != nil {
		t.Fatal(err)
	}
	if p.width != 1 || p.height != 1 {
		t.Fatalf("%+v", p)
	}
}

func TestUnsupportedTypes(t *testing.T) {
	for name, data := range map[string][]byte{
		"svg":  []byte(`<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>`),
		"html": []byte("<!DOCTYPE html><html></html>"),
		"gif":  []byte("GIF89a\x01\x00\x01\x00\x00\x00\x00;"),
		"exe":  append([]byte("MZ"), make([]byte, 100)...),
		"zip":  []byte("PK\x03\x04rest"),
	} {
		if _, err := processUpload(data); !errors.Is(err, errUnsupported) {
			t.Errorf("%s: got %v", name, err)
		}
	}
}

func TestLogs(t *testing.T) {
	p, err := processUpload([]byte("12:00 player started\n12:01 error: timeout\n"))
	if err != nil || p.kind != AttachLog || p.ext != "txt" {
		t.Fatalf("%+v %v", p, err)
	}
	if _, err := processUpload(append([]byte("text"), 0, 'x')); err == nil {
		t.Fatal("NUL byte accepted")
	}
	if _, err := processUpload(bytes.Repeat([]byte("a"), maxLogBytes+1)); !errors.Is(err, errTooLarge) {
		t.Fatalf("oversize log: %v", err)
	}
}

func TestCleanText(t *testing.T) {
	got := cleanText("  Hola\r\nmundo\x00\x07 ‮evil x\t! ")
	if got != "Hola\nmundo evilx\t!" {
		t.Fatalf("%q", got)
	}
	if got := cleanLine("a\n\n  b\tc"); got != "a b c" {
		t.Fatalf("%q", got)
	}
	if got := short("Pixel 8 Pro"); got != "Pixel 8 Pro" {
		t.Fatalf("%q", got)
	}
	if got := short("<script>"); got != "" {
		t.Fatalf("%q", got)
	}
}
