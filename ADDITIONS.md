# What BitChord-X adds

BitChord-X builds on [BitChord](https://github.com/kushagrasinghx/BitChord) by Kushagra Singh and runs in parallel with it. This page lists what it adds or changes; everything else behaves as in the original. See [MAINTAINERS.md](MAINTAINERS.md) for credits.

## Playback and audio
- **Hi-Res Lossless source**: real FLAC up to 24-bit. Off until you switch it on in Settings.
- **Configurable lossless providers**, including ones typed in by hand, with failover ranked by latency.
- **Sample-accurate quality upgrades**: when a lossy track is swapped for FLAC mid-song, the two versions are aligned before the crossfade.
- **Lossless from the start** (optional): wait a few seconds for the FLAC copy instead of starting with the standard one.
- **Automatic downgrade** to standard quality when the network can't sustain FLAC, with progressive retries.
- **Mobile-data notice** when playing lossless on a cellular connection.

## Downloads and cache
- **One download planner** that ranks the current song, the next one and the rest of the queue.
- **Separate lossless cache budget** (1 GB by default), independent of the standard cache.
- **Lossless retention levels**: off, CD quality or Hi-Res.
- **Cached songs get upgraded** in the background when a better copy is available.
- **Automatic playlist downloads** inside the app, off by default, with a silent notice.

## Links and services
- **Links from Spotify, Apple Music, Deezer, SoundCloud, Tidal and Amazon Music** open in the app and search the song by title and artist.
- **Spotify**: import your library or chosen playlists, incremental sync that only looks up new songs, multi-select, and retry for the lists that failed.
- **Export local playlists to YouTube Music**, sent in batches with pauses to respect the API limits.

## Interface
- **Library as grid or rows**, with 64 dp artwork in the row view.
- **Artwork prefetch** for the rows coming up, so scrolling has no gaps.
- **Blurred placeholder** while high-resolution artwork loads on the now-playing screen.
- **Morphing search bar** in the navbar, persistent mini player and a hold-and-slide gesture.
- **Lyric line above the scrubber** (optional).
- **Redesigned ⋮ menu and player artwork**, plus an exit animation when deleting playlists.
- **Full Spanish translation**, in addition to the existing languages.

## Updates
- The in-app update check reads the releases of [BitChord-X](https://github.com/AnGorRoc/BitChord-X/releases).

## Other documents
For the Listen Together server and lyrics translation, see [ADDITIONAL.md](ADDITIONAL.md).
