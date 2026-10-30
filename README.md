<div align="center">

<br/>
<br/>

<img src="Logo.png" alt="BitChord app icon" width="200" />

# BitChord-X

### BitChord, extended

<br/>

[![Latest release](https://img.shields.io/github/v/release/AnGorRoc/BitChord-X?style=for-the-badge&labelColor=0d1117)](https://github.com/AnGorRoc/BitChord-X/releases)
[![License](https://img.shields.io/github/license/AnGorRoc/BitChord-X?style=for-the-badge&labelColor=0d1117)](https://github.com/AnGorRoc/BitChord-X/blob/main/LICENSE)
[![Downloads](https://img.shields.io/github/downloads/AnGorRoc/BitChord-X/total?style=for-the-badge&labelColor=0d1117)](https://github.com/AnGorRoc/BitChord-X/releases)

<br/>

[**Download**](#download) · [**Features**](#features) · [**What's added**](#whats-added) · [**Credits**](#credits) · [**Disclaimer**](#disclaimer)

<br/>

</div>

> [!IMPORTANT]
> BitChord is not affiliated with, endorsed by, or connected to YouTube or Google in any way. Use it at your own discretion.

> [!NOTE]
> BitChord-X is an independent project that builds on [BitChord](https://github.com/kushagrasinghx/BitChord) by Kushagra Singh and runs in parallel with it. It keeps everything the original does and adds more on top. See [What's added](#whats-added) and [Credits](#credits).

---

<div align="center">

<img src="Banner.png" alt="BitChord banner" width="100%" />

<h1><a id="features"></a>Features</h1>

<table>
  <tr>
    <td width="50%" valign="top">

#### Playback
- **Search, browse and play** anything available on YouTube Music.
- **Hi-Res lossless audio** — FLAC/ALAC from your own addons, plus FLAC from [Monochrome](https://monochrome.st), a built-in source that is off until you opt in (up to 24-bit, as each file states), with YouTube Music as fallback.
- **Gapless playback with true crossfade**, adjustable 0–12s.
- **Automix [Beta]** — DJ-style transitions with beat-matching and tempo-stretching.
- **Offline downloads** — save tracks with embedded metadata.
- **Local music library** integration.
- **Background playback** via a proper foreground media session.
- **Apple-like lyrics animation** — credit to [binimum](https://github.com/binimum/am-lyrics).

#### Connectivity & Accounts
- **Sign in with your Google account** for personalized content.
- **Spotify integration** — connect your account to play your playlists and Liked Songs.
- **Discord Rich Presence** — in-app login, live track/artist/album and progress.
- **Scrobbling** to Last.fm and ListenBrainz.
- **Pluggable sources** — add, edit, test and health-check addon sources, ranked ahead of Monochrome, JioSaavn and YouTube Music.

    </td>
    <td width="50%" valign="top">

#### Experience
- **Animated album canvas** — motion artwork on the now-playing screen.
- **Word-synced lyrics** — word/syllable-level highlighting from multiple sources.
- **Lyrics providers** — credit to [lrc.red](https://lrc.red), [BiniLyrics](https://github.com/binimum), [BetterLyrics](https://github.com/better-lyrics/better-lyrics), [PaxSenix](https://lyrics.paxsenix.org), [LyricsPlus](https://github.com/ibratabian17/YouLyPlus), [SimpMusic](https://github.com/maxrave-dev/SimpMusic), [Unison](https://unison.boidu.dev), [Megalobiz](https://www.megalobiz.com), [KuGou](https://www.kugou.com), [LRCLIB](https://lrclib.net), [Musixmatch](https://www.musixmatch.com) and [Genius](https://genius.com).
- **Dynamic, artwork-driven theming** — Material palette extracted from album art.
- **Frosted-glass UI** — Telegram-style translucent bars via Haze, Material 3 theming.

#### Controls & Tweaks
- **Per-network audio quality** — separate quality ceilings for Wi-Fi and mobile data.
- **Playback speed control** (0.5×–2.0×) and **skip silence**.
- **Sleep timer** — fixed presets or "stop after this track".
- **System equalizer** integration.
- **Bit-perfect USB exclusive output** — sends lossless tracks to a USB DAC unchanged, at each track's own rate and bit depth. Thanks to [Mornye](https://github.com/mornyeapp/Mornye) for the reference.
- **Stats for nerds** — codec, bit depth, sample rate, and more on the now-playing screen.

    </td>
  </tr>
</table>

</div>

---

<div align="center">

<h1><a id="whats-added"></a>What's added</h1>

</div>

On top of the original feature set, BitChord-X adds:

- **Hi-Res Lossless source** — FLAC up to 24-bit with latency-ranked provider failover and providers you can type in by hand. Off until you switch it on.
- **Smarter downloads and cache** — one download planner, a separate cache budget for lossless files, and in-place upgrades of cached songs to a better copy.
- **Opening links from other services** — Spotify, Apple Music, Deezer, SoundCloud, Tidal and Amazon Music links open in the app.
- **Spotify import and sync** — whole library or chosen playlists, incremental updates, and retry for the ones that failed.
- **Export playlists to YouTube Music**, paced to stay within the API limits.
- **Interface** — library as grid or rows, artwork prefetch, a morphing search bar with a persistent mini player, a lyric line above the scrubber, and a redesigned ⋮ menu.
- **Updates** — the in-app check reads this project's releases.

The full list is in [ADDITIONS.md](ADDITIONS.md).

---

<div align="center">

<h1><a id="download"></a>Download</h1>

<a href="https://github.com/AnGorRoc/BitChord-X/releases" target="_blank" rel="noopener noreferrer"><img src="https://cdn.jsdelivr.net/gh/JMcrafter26/badges@main/src/assets/supported/android/cozy.svg" alt="Download for Android" height="55"/></a>

Grab the latest signed APK from the [Releases](https://github.com/AnGorRoc/BitChord-X/releases) page. Sideloading requires enabling "Install unknown apps" for whichever app you download it with.


</div>

---

<div align="center">

<h1><a id="contributing"></a>Contributing</h1>

We welcome contributions to BitChord-X! Please review our [Contributing Guide](CONTRIBUTING.md) and [Code of Conduct](CODE_OF_CONDUCT.md) before submitting a Pull Request.

[**Contributing Guide**](CONTRIBUTING.md) · [**Code of Conduct**](CODE_OF_CONDUCT.md) · [**Maintainers**](MAINTAINERS.md) · [**Additional Docs**](ADDITIONAL.md)

</div>

---

<div align="center">

<h1><a id="credits"></a>Credits</h1>

BitChord-X would not exist without [BitChord](https://github.com/kushagrasinghx/BitChord), created by [Kushagra Singh](https://github.com/kushagrasinghx) and its [contributors](https://github.com/kushagrasinghx/BitChord/graphs/contributors). If you like this app, the original project is the place to say thanks, and you can support its author on [Ko-fi](https://ko-fi.com/kushagrasinghx).

[**Maintainers & contributors**](MAINTAINERS.md)

</div>

---

<div align="center">

<h1><a id="disclaimer"></a>Disclaimer & Legal Notice</h1>

BitChord-X is an independent, community-driven third-party audio player and client, derived from BitChord. It is **not** associated with Google LLC, YouTube Music, Deezer, Telegram, or any of their parent companies.

* **No Media Hosting:** BitChord-X does not host, upload, or store copyrighted music files. It operates strictly as an interface to scan local device storage or stream media directly from public, public-facing, or user-authenticated APIs.
* **Fair Use & API Usage:** This software is created solely for personal research, educational, and fair-use purposes. The user is entirely responsible for ensuring their usage aligns with their local copyright laws and YouTube Terms of Service.
* **No Ad-Blocking Guarantee:** While BitChord focuses on providing a clean listening environment, it does not guarantee permanent bypasses or modifications to commercial third-party platform conditions.
* **Copyleft:** BitChord-X is free software under the GPLv3, like the project it derives from. The license does not let anyone forbid others from selling or redistributing copies, but any distribution must come with the Corresponding Source under the same license.

</div>

---

<div align="center">

<h1><a id="license"></a>License</h1>

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. See the [LICENSE](LICENSE) file for details.

</div>
