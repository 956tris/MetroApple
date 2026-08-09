<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" alt="MetroFuse+ app icon" width="180" />

# MetroFuse+

### MetroFuse 7.0 with opt-in playback, cache, and YouTube Music history experiments.

<br/>

[![Latest release](https://img.shields.io/badge/releases-GitHub-181717?style=for-the-badge&logo=github&labelColor=0d1117)](releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge&labelColor=0d1117)](LICENSE)
[![Downloads](https://img.shields.io/github/downloads/caduHD4/MetroFuse/total?style=for-the-badge&labelColor=0d1117)](releases)
[![Android](https://img.shields.io/badge/platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white&labelColor=0d1117)](#download)
[![Discord](https://img.shields.io/badge/Discord-Join-5865F2?style=for-the-badge&logo=discord&logoColor=white&labelColor=0d1117)](https://discord.gg/ddHxkVDgt3) join ts 💔
<br/>

[**Experiments**](#metrofuse-experiments) - [**Download**](#download) - [**Features**](#features) - [**Screenshots**](#screenshots) - [**Credits**](#credits)

</div>

> [!WARNING]
> MetroFuse+ connects to third-party services selected by the user. Availability, quality, catalog coverage, login behavior, and regional access can change at any time and may require your own account or provider access.

MetroFuse+ is an unofficial community fork. Please report fork-specific issues in this repository, not to the upstream maintainers.

---

## MetroFuse+ experiments

MetroFuse+ adds the following independent switches under **Settings → Experimental → Playback reliability experiments**. They are disabled by default, so turning all of them off preserves the MetroFuse 7.0 playback behavior.

- **Fast audio-source search:** queries providers concurrently with a short timeout, caches recent matches, and keeps the configured provider order in the result.
- **Limit provider playback attempts:** stops waiting for an unresponsive provider after 20 seconds and tries the next source in order.
- **Prefer Deezer for automatic playback:** attempts Deezer first without modifying the saved provider order.
- **Deezer resolver fallback:** retries the built-in resolver if the configured Deezer resolver fails.
- **Ask before skipping after playback failure:** offers retry or skip only after recovery and every matched source has failed.
- **Detailed playback diagnostics:** records attempt order, duration, and result in Android logcat.
- **Accurate SoundCloud health rows:** prevents provider health results from overwriting one another.
- **Prefer cached audio after a quality change:** reuses already cached audio instead of downloading the same song again at the newly selected quality.
- **Sync listens to YouTube Music history:** after a real listen, sends authenticated playback and watch-time tracking to YouTube Music, regardless of the audio source used. YouTube watch history must be enabled.

These options are experimental because provider behavior can change. Enable only the features you want and disable an option if it causes trouble.

---

## What is MetroFuse+?

MetroFuse+ is based on [MetroFuse](https://github.com/956tris/MetroFuse) 7.0 and combines multiple music front pages and playback providers in one Android app. It keeps the Material 3 player, library, lyrics, queue, widgets, and playlist tools while adding optional reliability features that remain isolated from the base behavior.

---

## Features

| Playback | Discovery |
| --- | --- |
| Configurable multi-provider playback routing | YouTube Music home feed and other frontends |
| Provider fallback when a stream misses | Spotify-style personalized frontpage |
| Background playback | TIDAL-style personalized frontpage |
| Downloads and cache for offline use | Search songs, albums, artists, videos, and playlists |
| Skip silence and sleep timer | Open external playlists inside MetroFuse |

| Audio | Library |
| --- | --- |
| Format, bitrate, and sample-rate display when available | Full library management |
| Audio normalization | Local playlists |
| Tempo and pitch control | Import playlists |
| Equalizer | Reorder songs in playlist or queue |
| Spotify Canvas support and animated album covers | Lyrics, translation, and synced lyrics |

---

## Screenshots

<div align="center">

<img src="fastlane/metadata/android/en-US/images/screenshots/screenshot_1.png" alt="Home screen" width="30%" />
<img src="fastlane/metadata/android/en-US/images/screenshots/screenshot_2.png" alt="Artist screen" width="30%" />
<img src="fastlane/metadata/android/en-US/images/screenshots/screenshot_3.png" alt="Recognize music screen" width="30%" />
<img src="fastlane/metadata/android/en-US/images/screenshots/screenshot_4.png" alt="Listen together screen" width="30%" />
<img src="fastlane/metadata/android/en-US/images/screenshots/screenshot_5.png" alt="Player screen" width="30%" />
<img src="fastlane/metadata/android/en-US/images/screenshots/screenshot_6.png" alt="Lyrics screen" width="30%" />

</div>

---

## Download

Grab the latest signed APK from the [GitHub releases page](releases/latest). Use the standard FOSS build unless you specifically need the GMS build for Google Cast. Debug builds are only for testing.

> MetroFuse+ uses the package ID `com.metrofuse.plus`, so it installs separately from MetroFuse and does not automatically inherit the old app's data, settings, downloads, or cache.

---

## Build

```bash
./gradlew :app:assembleFossDebug
```

Release builds require the project signing setup used by the maintainer.

---

## Credits

MetroFuse+ is based on [MetroFuse](https://github.com/956tris/MetroFuse), which is built on Metrolist and other excellent open-source Android music projects.

Special thanks to:

- [Metrolist](https://github.com/MetrolistGroup/Metrolist)
- [InnerTune](https://github.com/z-huang/InnerTune)
- [OuterTune](https://github.com/DD3Boh/OuterTune)
  
- [Better Lyrics](https://better-lyrics.boidu.dev)
- [MusicRecognizer](https://github.com/aleksey-saenko/MusicRecognizer)
- [Canvas Api](https://github.com/Paxsenix0/Spotify-Canvas-API)


## License

MetroFuse+ is licensed under GPL-3.0. See [LICENSE](LICENSE) for details.

---

## Disclaimer

MetroFuse+ is an independent, unofficial project. It is not affiliated with, funded, authorized, endorsed by, or associated with YouTube, Google, Qobuz, Spotify, TIDAL, MetroFuse, Metrolist Group, or any of their affiliates.

All trademarks, service marks, catalogs, artwork, metadata, and content remain the property of their respective owners. Users are responsible for how they access third-party services and for following the rules, rights, and availability requirements of those services in their region.
