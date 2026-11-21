package feedback

import (
	"bytes"
	"errors"
	"fmt"
	"image"
	"image/draw"
	"image/jpeg"
	"image/png"
	"net/http"
	"strings"
	"unicode/utf8"

	xdraw "golang.org/x/image/draw"
	_ "golang.org/x/image/webp" // registers the WebP decoder
)

// Bounds on what an upload may be. A screenshot of a 4K phone is about 10 MP.
const (
	maxPixels    = 40_000_000
	maxSide      = 12_000
	storeMaxSide = 4096 // larger images are scaled down before they are stored
	thumbSide    = 360
	jpegQuality  = 88
	thumbQuality = 78
	maxLogBytes  = 512 * 1024
	sniffBytes   = 512
	// What may precede the first character of a log: spaces, line breaks, a byte-order mark.
	logLeadingSpace = " \t\r\n\uFEFF"
)

var (
	errUnsupported = errors.New("unsupported file type")
	errTooLarge    = errors.New("image dimensions too large")
	errCorrupt     = errors.New("file could not be decoded")
)

// processed is an upload ready to be written: the bytes to keep, never the bytes that came in.
type processed struct {
	kind          string
	mime          string
	ext           string
	data          []byte
	thumb         []byte
	width, height int
}

// processUpload turns an upload into what is stored. What kind of file it is comes from its
// first bytes, never from the Content-Type the client claims. An image is decoded and written
// out again: that drops EXIF (GPS, camera serial), anything appended after the image data and
// anything that would make it parse as another format. Its size is checked from the header
// before the pixels are decoded, so a small file that would inflate to gigabytes is refused
// cheaply.
//
// The upload is already in memory (it is at most a few megabytes, and the handler bounds how
// many are processed at once).
func processUpload(data []byte) (processed, error) {
	sniffed := http.DetectContentType(data[:min(len(data), sniffBytes)])
	switch {
	case sniffed == "image/png" || sniffed == "image/jpeg" || sniffed == "image/webp":
		return processImage(data, sniffed)
	case strings.HasPrefix(sniffed, "text/plain"):
		return processLog(data)
	default:
		return processed{}, errUnsupported
	}
}

func processImage(data []byte, sniffed string) (processed, error) {
	cfg, format, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil {
		return processed{}, errCorrupt
	}
	if cfg.Width <= 0 || cfg.Height <= 0 || cfg.Width > maxSide || cfg.Height > maxSide || cfg.Width*cfg.Height > maxPixels {
		return processed{}, errTooLarge
	}
	img, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return processed{}, errCorrupt
	}
	img = fitWithin(img, storeMaxSide, xdraw.CatmullRom)

	out := processed{kind: AttachImage, width: img.Bounds().Dx(), height: img.Bounds().Dy()}
	var buf bytes.Buffer
	// Screenshots come as PNG and stay PNG, since JPEG smears text; a WebP with transparency
	// does too. Photos (JPEG, opaque WebP) are written as JPEG.
	if format == "png" || (sniffed == "image/webp" && !opaque(img)) {
		enc := png.Encoder{CompressionLevel: png.DefaultCompression}
		if err := enc.Encode(&buf, img); err != nil {
			return processed{}, fmt.Errorf("encode png: %w", err)
		}
		out.mime, out.ext = "image/png", "png"
	} else {
		if err := jpeg.Encode(&buf, flatten(img), &jpeg.Options{Quality: jpegQuality}); err != nil {
			return processed{}, fmt.Errorf("encode jpeg: %w", err)
		}
		out.mime, out.ext = "image/jpeg", "jpg"
	}
	out.data = buf.Bytes()

	var tb bytes.Buffer
	thumb := fitWithin(img, thumbSide, xdraw.ApproxBiLinear)
	if err := jpeg.Encode(&tb, flatten(thumb), &jpeg.Options{Quality: thumbQuality}); err == nil {
		out.thumb = tb.Bytes()
	}
	return out, nil
}

// fitWithin scales img down so its longer side is at most side; smaller images are returned as is.
func fitWithin(img image.Image, side int, scaler xdraw.Scaler) image.Image {
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	if w <= side && h <= side {
		return img
	}
	if w >= h {
		h = max(1, h*side/w)
		w = side
	} else {
		w = max(1, w*side/h)
		h = side
	}
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	scaler.Scale(dst, dst.Bounds(), img, b, xdraw.Over, nil)
	return dst
}

func opaque(img image.Image) bool {
	if o, ok := img.(interface{ Opaque() bool }); ok {
		return o.Opaque()
	}
	return false
}

// flatten puts an image with transparency on white, since JPEG has no alpha and would otherwise
// turn transparent pixels black.
func flatten(img image.Image) image.Image {
	if opaque(img) {
		return img
	}
	dst := image.NewRGBA(img.Bounds())
	draw.Draw(dst, dst.Bounds(), image.White, image.Point{}, draw.Src)
	draw.Draw(dst, dst.Bounds(), img, img.Bounds().Min, draw.Over)
	return dst
}

// processLog keeps a text log: valid UTF-8, no NUL bytes, at most maxLogBytes. The client
// already removes paths and URL queries from it (as for error reports); the server only makes
// sure it is text.
func processLog(data []byte) (processed, error) {
	if len(data) > maxLogBytes {
		return processed{}, errTooLarge
	}
	if !utf8.Valid(data) || bytes.IndexByte(data, 0) >= 0 {
		return processed{}, errUnsupported
	}
	// http.DetectContentType calls SVG and other markup without an XML declaration plain text.
	// It is served as text either way, but a log never starts with a tag.
	if trimmed := bytes.TrimLeft(data, logLeadingSpace); len(trimmed) > 0 && trimmed[0] == '<' {
		return processed{}, errUnsupported
	}
	return processed{kind: AttachLog, mime: "text/plain; charset=utf-8", ext: "txt", data: data}, nil
}
