# Musicalified - RPC 🎵

> Ultra-lightweight, battery-efficient Discord Rich Presence companion for Apple Music on Android. Designed for seamless, set-and-forget background operation.

---

## ✨ Features

- **100% Event-Driven (Zero Polling)**: Hooks directly into Android's native `MediaSessionManager` for active media events. No background polling loops, no aggressive wakelocks. Sleeps when playback is idle.
- **HD Artwork & Metadata Resolution**: High-resolution album artwork (1000x1000) and track links resolved dynamically with zero third-party image upload overhead.
- **In-Memory LRU Caching**: Previously resolved tracks are cached in memory for instant presence updates and zero redundant network traffic on repeat plays.
- **Minimalist & Battery Friendly**: Native ViewBinding architecture with no heavy UI framework runtimes. Target background memory footprint is only **~15MB - 25MB**.
- **Essential Customization**:
  - Toggle album artwork display
  - Toggle "Listen on Apple Music" interactive button
  - Toggle live playback duration and timestamps
  - Configurable Discord Application ID

---

## 🏗 Architecture

```
musicalified-rpc/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/skyler/musicpresence/
│   │   │   ├── MusicPresenceApp.kt
│   │   │   ├── core/
│   │   │   │   ├── discord/
│   │   │   │   │   ├── DiscordGateway.kt        # WebSocket client for Discord Gateway
│   │   │   │   │   └── DiscordPayloads.kt       # Gateway payload builder (Presence, Heartbeat, Identify)
│   │   │   │   ├── itunes/
│   │   │   │   │   └── iTunesSearchClient.kt    # Metadata resolver with LRU cache
│   │   │   │   ├── media/
│   │   │   │   │   ├── AppleMediaListenerService.kt # Android NotificationListener + MediaSession hook
│   │   │   │   │   └── AppleMusicMetadata.kt
│   │   │   │   └── prefs/
│   │   │   │       └── AppPreferences.kt        # Local configuration storage
│   │   │   └── ui/
│   │   │       └── MainActivity.kt              # Clean, dark-mode control interface
│   │   └── res/
│   │       ├── layout/activity_main.xml
│   │       ├── values/{colors, strings, themes}.xml
│   │       └── drawable/
```

---

## 🚀 Building & Installing

### Automated Builds (Recommended)
Every push to `main` automatically triggers a GitHub Actions build. You can download the latest pre-compiled debug APK directly from the **Actions** tab (or **Releases**).

### Manual Build
```bash
git clone https://github.com/skilerrr/musicalified-rpc.git
cd musicalified-rpc
./gradlew assembleDebug
```
The compiled APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📱 Quick Setup

1. Install the APK on your Android device.
2. Grant **Notification Listener Permission** when prompted (required by Android to read active media session state).
3. Whitelist the app from system battery optimizations to prevent background freeze on OEM devices (MIUI, OneUI, ColorOS).
4. Enter your Discord User Token in the setup screen and save.
5. Play your favorite track on Apple Music — your Discord profile will update in real time.

---

## 🛡 Disclaimer & Trademarks

- **Musicalified - RPC** is an independent open-source project and is **not** affiliated with, maintained, authorized, endorsed, or sponsored by Apple Inc. or Discord Inc.
- **Apple Music** and **iTunes** are trademarks of Apple Inc., registered in the U.S. and other countries.
- **Discord** is a registered trademark of Discord Inc.
- All product and company names are trademarks™ or registered® trademarks of their respective holders. Use of them does not imply any affiliation with or endorsement by them.
- This utility interacts directly with the Discord Gateway. Rate-limiting and standard client heartbeating intervals are strictly adhered to, but user discretion is advised.

---

## 📄 License

Distributed under the [MIT License](LICENSE).
