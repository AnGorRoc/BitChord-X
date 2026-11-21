package config

import (
	"log"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
)

func getInt(key string, fallback int) int {
	val := os.Getenv(key)
	if val == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(strings.TrimSpace(val))
	if err != nil {
		return fallback
	}
	return parsed
}

func getCSV(key string, fallback string) []string {
	val := os.Getenv(key)
	if val == "" {
		val = fallback
	}
	if val == "" {
		return nil
	}
	var res []string
	for _, item := range strings.Split(val, ",") {
		trimmed := strings.TrimSpace(item)
		if trimmed != "" {
			res = append(res, trimmed)
		}
	}
	return res
}

func getBool(key string, fallback bool) bool {
	val := os.Getenv(key)
	if val == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(strings.TrimSpace(val))
	if err != nil {
		return fallback
	}
	return parsed
}

func getOrigin(key string) string {
	val := strings.TrimRight(strings.TrimSpace(os.Getenv(key)), "/")
	if val == "" {
		return ""
	}
	u, err := url.Parse(val)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.Path != "" || u.RawQuery != "" || u.Fragment != "" || u.User != nil {
		log.Printf("%s %q is not an http(s) origin; ignoring", key, val)
		return ""
	}
	return val
}

// IsAllowedOrigin deliberately does not support a wildcard. Browser clients
// must be explicitly named; native clients send no Origin header.
func IsAllowedOrigin(origin string) bool {
	for _, allowed := range AllowedOrigins {
		if origin == allowed {
			return true
		}
	}
	return false
}

var (
	MaxMembers           = getInt("JAM_MAX_MEMBERS", 5)
	StateHeartbeatMs     = getInt("JAM_STATE_HEARTBEAT_MS", 5000)
	PlayLeadMs           = getInt("JAM_PLAY_LEAD_MS", 350)
	DisconnectGraceMs    = int64(getInt("JAM_DISCONNECT_GRACE_MS", 45000))
	EmptyPartyTTLMs      = int64(getInt("JAM_EMPTY_PARTY_TTL_MS", 120000))
	PartyMaxAgeMs        = int64(getInt("JAM_PARTY_MAX_AGE_MS", 12*60*60*1000))
	ControlRatePerSecond = float64(getInt("JAM_CONTROL_RATE_PER_SECOND", 25))
	MaxUpcomingQueue     = getInt("JAM_MAX_UPCOMING_QUEUE", 500)
	MaxQueueLength       = getInt("JAM_MAX_QUEUE_LENGTH", 1+MaxUpcomingQueue)
	// Render Free has 0.1 CPU. Fifty rooms (at most 250 sockets) is a safe
	// starting ceiling; raise it only after measuring CPU and memory usage.
	MaxParties           = getInt("JAM_MAX_PARTIES", 50)
	CreateRatePerMinute  = getInt("JAM_CREATE_RATE_PER_MINUTE", 2)
	// Per address. Joining is what a code guesser calls, previewing is what a link opener calls,
	// and connecting an account device is what makes a new account out of thin air.
	JoinRatePerMinute    = getInt("JAM_JOIN_RATE_PER_MINUTE", 30)
	PreviewRatePerMinute = getInt("JAM_PREVIEW_RATE_PER_MINUTE", 60)
	AccountConnectRatePerMinute = getInt("JAM_ACCOUNT_CONNECT_RATE_PER_MINUTE", 30)
	// Accounts live in memory for a day after their last device; this bounds what an address-hopper
	// can make the server hold. Devices of accounts already known are never refused by it.
	MaxAccounts          = getInt("JAM_MAX_ACCOUNTS", 20000)
	// Open sockets across parties and account channels. A person can hold up to five devices in
	// a party and twelve on their account, so the party ceiling alone does not bound this.
	MaxSockets           = getInt("JAM_MAX_SOCKETS", 3000)
	RateLimitMaxEntries  = getInt("JAM_RATE_LIMIT_MAX_ENTRIES", 10000)
	RequestMaxBytes      = int64(getInt("JAM_REQUEST_MAX_BYTES", 16*1024))
	// A whole 500-song queue arrives in one frame, around 200 KB.
	WebSocketMaxBytes    = int64(getInt("JAM_WEBSOCKET_MAX_BYTES", 512*1024))
	ConnectionIdleMs     = int64(getInt("JAM_CONNECTION_IDLE_MS", 15*60*1000))
	FrameRatePerSecond   = float64(getInt("JAM_FRAME_RATE_PER_SECOND", 30))
	AllowedOrigins       = getCSV("JAM_ALLOWED_ORIGINS", "")
	TrustProxy           = getBool("JAM_TRUST_PROXY", false)
	PublicOrigin         = getOrigin("JAM_PUBLIC_ORIGIN")
	// A header a trusted proxy sets to the real client address, read before
	// X-Forwarded-For when JAM_TRUST_PROXY is on. Behind Cloudflare that is
	// CF-Connecting-IP: Cloudflare appends to an X-Forwarded-For the caller may
	// already have written, so the first entry of that one is the caller's word.
	ClientIPHeader       = strings.TrimSpace(os.Getenv("JAM_CLIENT_IP_HEADER"))
	// "package:SHA256" pairs, comma separated, published as
	// /.well-known/assetlinks.json so Android opens this server's invite links
	// in the app directly. Empty publishes nothing.
	AndroidAppLinks      = parseAppLinks(getCSV("JAM_ANDROID_APP_LINKS", ""))
	// Build identifier reported by /api/info, e.g. a commit hash.
	Version              = strings.TrimSpace(os.Getenv("JAM_VERSION"))
	Port                 = getInt("PORT", 8000)
	AdminPort            = getInt("ADMIN_PORT", 8001)
	AdminSecret          = strings.TrimSpace(os.Getenv("ADMIN_SECRET"))
	AdminTailscaleOnly   = getBool("ADMIN_TAILSCALE_ONLY", false)
	DatabaseURL          = strings.TrimSpace(os.Getenv("DATABASE_URL"))
	TelemetryRatePerMin  = getInt("JAM_TELEMETRY_RATE_PER_MINUTE", 20)
	// Feedback (bug reports and ideas sent from the apps). Attachments are files under
	// FeedbackDir, which must be a persistent volume; their total is capped at FeedbackMaxDiskMB.
	FeedbackEnabled          = getBool("FEEDBACK_ENABLED", true)
	FeedbackDir              = strings.TrimSpace(os.Getenv("FEEDBACK_DIR"))
	FeedbackMaxDiskMB        = getInt("FEEDBACK_MAX_DISK_MB", 5000)
	FeedbackMaxUploadMB      = getInt("FEEDBACK_MAX_UPLOAD_MB", 8)
	FeedbackRetentionDays    = getInt("FEEDBACK_RETENTION_DAYS", 180)
	FeedbackTicketsPerHour   = getInt("FEEDBACK_TICKETS_PER_HOUR", 5)
	FeedbackMessagesPerHour  = getInt("FEEDBACK_MESSAGES_PER_HOUR", 30)
	FeedbackUploadsPerHour   = getInt("FEEDBACK_UPLOADS_PER_HOUR", 60)
	FeedbackReadsPerMinute   = getInt("FEEDBACK_READS_PER_MINUTE", 30)
	TrustedProxyNets     = parseCIDRs(getCSV("JAM_TRUSTED_PROXY_CIDRS", defaultTrustedProxyCIDRs))
	tailscaleNets        = parseCIDRs([]string{"127.0.0.0/8", "::1/128", "100.64.0.0/10", "fd7a:115c:a1e0::/48"})
)

// Default trusted proxy CIDRs: loopback, RFC1918/Docker bridges (cloudflared/Caddy),
// and Cloudflare's published IPv4/IPv6 edge ranges.
const defaultTrustedProxyCIDRs = "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7," +
	"173.245.48.0/20,103.21.244.0/22,103.22.200.0/22,103.31.4.0/22," +
	"141.101.64.0/18,108.162.192.0/18,190.93.240.0/20,188.114.96.0/20," +
	"197.234.240.0/22,198.41.128.0/17,162.158.0.0/15,104.16.0.0/13," +
	"104.24.0.0/14,172.64.0.0/13,131.0.72.0/22," +
	"2400:cb00::/32,2606:4700::/32,2803:f800::/32,2405:b500::/32," +
	"2405:8100::/32,2a06:98c0::/29,2c0f:f248::/32"

func parseCIDRs(entries []string) []*net.IPNet {
	var nets []*net.IPNet
	for _, entry := range entries {
		if !strings.Contains(entry, "/") {
			if ip := net.ParseIP(entry); ip != nil {
				bits := 32
				if ip.To4() == nil {
					bits = 128
				}
				nets = append(nets, &net.IPNet{IP: ip, Mask: net.CIDRMask(bits, bits)})
				continue
			}
		}
		_, ipNet, err := net.ParseCIDR(entry)
		if err != nil {
			log.Printf("Invalid CIDR %q; ignoring", entry)
			continue
		}
		nets = append(nets, ipNet)
	}
	return nets
}

func extractRemoteIP(remoteAddr string) net.IP {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		host = remoteAddr
	}
	return net.ParseIP(strings.TrimSpace(host))
}

// IsTrustedProxy reports whether remoteAddr belongs to a configured trusted proxy CIDR.
func IsTrustedProxy(remoteAddr string) bool {
	ip := extractRemoteIP(remoteAddr)
	if ip == nil {
		return false
	}
	for _, ipNet := range TrustedProxyNets {
		if ipNet.Contains(ip) {
			return true
		}
	}
	return false
}

// IsTailscaleOrLoopback reports whether remoteAddr is loopback or within Tailscale's CGNAT/ULA ranges.
func IsTailscaleOrLoopback(remoteAddr string) bool {
	ip := extractRemoteIP(remoteAddr)
	if ip == nil {
		return false
	}
	for _, ipNet := range tailscaleNets {
		if ipNet.Contains(ip) {
			return true
		}
	}
	return false
}

// AppLink is one Android app allowed to open this server's invite links.
type AppLink struct {
	Package     string
	Fingerprint string
}

// parseAppLinks reads "package:AA:BB:..." entries. The fingerprint is the
// signing certificate's SHA-256 in keytool's colon-separated form; the first
// colon splits the package from it.
func parseAppLinks(entries []string) []AppLink {
	var links []AppLink
	for _, entry := range entries {
		pkg, fingerprint, ok := strings.Cut(entry, ":")
		pkg = strings.TrimSpace(pkg)
		fingerprint = strings.ToUpper(strings.TrimSpace(fingerprint))
		if !ok || pkg == "" || len(strings.ReplaceAll(fingerprint, ":", "")) != 64 {
			log.Printf("JAM_ANDROID_APP_LINKS entry %q is not package:SHA256; ignoring", entry)
			continue
		}
		links = append(links, AppLink{Package: pkg, Fingerprint: fingerprint})
	}
	return links
}

// Open-app heartbeats. The interval is handed back on every ping, so it can be
// raised here under load without an app update. An install stays counted for
// one interval plus the grace period after its last ping.
var (
	PresenceIntervalSec     = getInt("JAM_PRESENCE_INTERVAL_SEC", 60)
	PresenceGraceSec        = getInt("JAM_PRESENCE_GRACE_SEC", 30)
	PresenceMaxEntries      = getInt("JAM_PRESENCE_MAX_ENTRIES", 200000)
	PresencePingsPerMinute  = getInt("JAM_PRESENCE_PINGS_PER_MINUTE", 20)
	PresenceRateLimitMaxIPs = getInt("JAM_PRESENCE_RATE_LIMIT_MAX_IPS", 100000)
)
