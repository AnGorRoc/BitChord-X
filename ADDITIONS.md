# What BitChord-X adds

BitChord-X builds on [BitChord](https://github.com/kushagrasinghx/BitChord) by Kushagra Singh and runs in parallel with it. This page documents everything added or enhanced in BitChord-X; everything else behaves as in the original. See [MAINTAINERS.md](MAINTAINERS.md) for maintainers and contributors.

---

## 1. Playback, DSP & Audio Engineering

- **Hi-Res Lossless source**: Real FLAC up to 24-bit with latency-ranked provider failover. Off until opted in via Settings.
- **Sample-accurate quality upgrades**: Mid-track upgrades from standard lossy streams to lossless FLAC align samples before crossfading to eliminate auditory glitches.
- **Lossless from the start (optional)**: Option to wait briefly for the FLAC source to resolve before starting playback.
- **Adaptive network handling**: Automatic progressive fallbacks to standard quality on constrained connections, plus cellular data consumption notices.
- **Harmonic Automix with Camelot Key Guard**:
  - Full 24-key Camelot Wheel compatibility mapping (`CamelotKey.kt`).
  - Strict harmonic mixing guards that enforce relative major/minor matches and adjacent fifths ($\pm 1$), preventing harsh harmonic collisions (distance $\ge 2$).
  - **BPM Stretch Guard**: Caps tempo stretching to $\le 16\%$ to preserve natural pitch, cadence, and vocal timbre.
  - **Lyric & Vocal Collision Guard**: Tracks `lastVocalTime` on outgoing tracks and `firstVocalTime` on incoming tracks to ensure outgoing vocals finish naturally and transitions start on instrumental breaks without vocal clash.
  - **Equal-Power Transitions**: Calibrated equal-power gain curves ($L^2 + R^2 \approx 1.0$) and bass swap on the downbeat for punchy transitions.
  - **Analyzing Mix Indicator**: Reactive animated badge under the scrubber displaying `AutoAwesome`, pulsing scale, and shimmer effect while beat grids and harmonic keys are computed.
- **Sing / Karaoke Mode**:
  - Real-time vocal suppression sheet with liquid capsule fader.
  - Selectable audio DSP engines for vocal extraction/isolation.
- **Audio DSP engines & formats**:
  - ReplayGain loudness normalization.
  - Native Direct Stream Digital (DSD) decoding support (`.dsd`, `.dsf`, `.dff`).
  - Bit-perfect USB exclusive audio DAC output with automatic rate switching.

---

## 2. Interface, Gestures & Visual Polish

- **120 FPS Liquid Glass Design**: Low-opacity frosted-glass surfaces via Haze, optimized draw passes, and smooth syllable glowing effects.
- **Dock Compaction on Scroll**:
  - Scrolling down in Home, Explore, or Library compacts the navigation dock and floating miniplayer into a unified, single-row pill (*GlassNavBar*):
    - **Left**: 58dp circle displaying the active tab icon.
    - **Center**: Inline miniplayer featuring album art, scrolling marquee text, play/pause button (with party host lock support), and swipe-to-skip / drag-up gestures.
    - **Right**: 58dp circular search button.
  - Scrolling up or tapping the active tab circle instantly restores the dual-level navigation dock.
- **Native Morph Play/Pause & Kinetic Controls**:
  - Geometric icon morphing transition between play and pause states across player controls, miniplayer, and dock.
  - Kinetic skip kicks: Directional impulse animation with elastic spring rebound when pressing Next or Previous.
  - Spring pop & scale animations when presenting or dismissing the floating miniplayer.
- **Immersive Player Mode**:
  - Real-time album artwork reflection below the cover with finely tuned mesh boundaries and opacity falloff.
  - Atmospheric background veil with HSL chromatic tuning matched to the dominant palette of the artist and track.
  - Unified artwork dimensions with smooth crossfade dissolve between static and animated canvas covers.
- **Enhanced Lyrics Presentation**:
  - Centered active lyric line with enlarged typography.
  - Spotify-style automatic lyrics provider re-connection and fallback.
  - Preserved past lyrics history during playback.
  - Optional synchronized lyric line positioned directly above the progress scrubber.
- **Navigation & Library**:
  - Morphing bottom search bar with persistent miniplayer and Hold & Slide gesture to switch tabs.
  - Library view toggling (grid or rows) with prefetching for gapless scrolling.
  - Redesigned popup ⋮ context menus, clean 3-button player action row (lyrics, cast, queue), and animated playlist deletion.

---

## 3. Desktop Application (Compose Multiplatform)

- **Native Multiplatform Desktop**: Standalone desktop app for Windows, macOS, and Linux built with Compose Multiplatform (Java 21 JVM target).
- **Native Windows DWM Integration**:
  - Real rounded corners, native drop shadows, and Windows 11 snap layouts via JNA.
  - Custom Windows title bar with full-height sidebar and hover caption buttons in the now-playing view.
  - Title bar color and dark mode synchronization matching the UI theme.
- **Apple Music-Style Interface**: Floating player capsule, collapsible full-height sidebar, and inline search.
- **Zero-Dependency Bundles**: Installers include the bundled JRE, FFmpeg, ONNX runtime, and Automix models out-of-the-box. See [DESKTOP.md](DESKTOP.md) for platform binaries and build instructions.

---

## 4. Connectivity, Social & Services

- **Listen Together (Party Mode)**:
  - Synchronized real-time playback across multiple devices.
  - Persistent interactive QR code zoom modal displayed directly above the party code on mobile and desktop.
  - Enabled by default in the player capsule.
- **Enhanced Spotify Sync**:
  - Dedicated import modal with introductory explanation.
  - Destination playlist selector (create new playlist or merge into existing).
  - Skipped/unmatched songs diagnostic dialog and background retry.
  - Optimized PCM decoding and matching algorithms.
- **Universal Link Resolution**: Deep links from Spotify, Apple Music, Deezer, SoundCloud, Tidal, and Amazon Music open in-app and search automatically.
- **Dynamic Radio Pages**: Dynamic radio station detail pages and playback source origin tags.
- **Network Streaming**: SMB and WebDAV network folders directly browsable as local sources.
- **Replay & Discovery**: Periodic listening replay with animated story cards, artist fact cards, artist signatures, and shareable lyric cards.
- **Universal Backup & Restore**: Full export and import of application settings, local databases, and source configurations.
- **Playlist Export**: Export local playlists to YouTube Music with rate-limiting protection.

---

## 5. Downloads, Cache & Updates

- **Unified Download Planner**: Ranks active song, upcoming queue, and offline lists in a single download scheduler.
- **Separate Lossless Cache Budget**: Dedicated storage allocation (1 GB default) with automatic background upgrades of cached tracks when higher-tier versions become available.
- **Retention Tier Control**: Granular lossless retention policies (off, CD quality, or Hi-Res).
- **In-App Release Updates**: Points directly to [BitChord-X Releases](https://github.com/AnGorRoc/BitChord-X/releases).

---

## 6. Branding & Localization

- **Dev Branding & Adaptive Icon**: Dedicated adaptive development icon (`devDebug`) with clean color stripes and multi-density mipmaps.
- **Full Spanish Translation**: Complete native Spanish strings across mobile, desktop, and Wear OS modules.

---

## Additional Documentation
- [Desktop Application Guide](DESKTOP.md): Setup, platform packages (macOS, Linux, Windows), and build instructions.
- [Listen Together Server Documentation](backend/README.md): Architecture, synchronization protocol, and deployment instructions for the party server.
- [Lyrics Translation](docs/LYRICS_TRANSLATION.md): Pipeline documentation for on-demand lyrics translation, timing projection, and UI transitions.
