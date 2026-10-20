# Music Presence for Android (Apple Music Edition) 🎵

> Ultra-lightweight, battery-efficient Discord Rich Presence specifically crafted for **Apple Music on Android**. Inspired by the simplicity and aesthetics of [Music Presence](https://musicpresence.com) on Desktop.

---

## ⚡ Why This Exists (vs Kizzy)

Kizzy is great, but it was designed as an all-in-one general presence manager that comes with heavy overhead:
* **Background Polling Loop**: Kizzy polls `UsageStatsManager` every 5 seconds to detect running apps, causing background wakelocks and battery drain.
* **Heavyweight UI**: Built with Jetpack Compose + Material 3 + Dagger Hilt + 300+ game presets, resulting in high baseline memory usage (80MB - 150MB).
* **Artwork Upload Overhead**: Attempts to upload local notification bitmaps to external image proxies.

### Our Approach: Bare Minimum & Razor Sharp
1. **100% Event-Driven (Zero Polling)**: Hooks directly into Android's native `MediaSessionManager` filtered strictly to `com.apple.android.music`. Sleep when Apple Music is idle, wakes only when track metadata or playback state changes.
2. **iTunes Search API + LRU Cache**: Instead of uploading local notification bitmaps, we query the public iTunes Search API to resolve official 1000x1000 HD album art and direct song links. Results are cached in an in-memory LRU cache (0 network calls on track replay).
3. **Ultra Lightweight**: Pure Android ViewBinding, no Compose, no DI frameworks. Target background RAM footprint is **~15MB - 25MB**.
4. **Tasteful Customization (Kustomisasi Tipis)**:
   - Toggle Album Art
   - Toggle "Listen on Apple Music" button
   - Toggle live timestamps / duration progress bar

---

## 🏗 Architecture

```
music-presence-android/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/skyler/musicpresence/
│   │   │   ├── MusicPresenceApp.kt
│   │   │   ├── core/
│   │   │   │   ├── discord/
│   │   │   │   │   ├── DiscordGateway.kt        # WebSocket client for Discord Gateway (v9)
│   │   │   │   │   └── DiscordPayloads.kt       # Presence OP 3, Heartbeat OP 1, Identify OP 2
│   │   │   │   ├── itunes/
│   │   │   │   │   └── iTunesSearchClient.kt    # iTunes search with LRU caching
│   │   │   │   ├── media/
│   │   │   │   │   ├── AppleMediaListenerService.kt # NotificationListenerService + MediaSession
│   │   │   │   │   └── AppleMusicMetadata.kt
│   │   │   │   └── prefs/
│   │   │   │       └── AppPreferences.kt        # SharedPreferences wrapper
│   │   │   └── ui/
│   │   │       └── MainActivity.kt              # Sleek Music Presence inspired control panel
│   │   └── res/
│   │       ├── layout/activity_main.xml
│   │       ├── values/{colors, strings, themes}.xml
│   │       └── drawable/
```

---

## 🚀 How to Build

1. Clone this repository.
2. Open with **Android Studio Hedgehog / Jellyfish / Ladybug**.
3. Let Gradle sync dependencies (JDK 17 required).
4. Build APK:
   ```bash
   ./gradlew assembleRelease
   # or
   ./gradlew assembleDebug
   ```

---

## 📱 How to Use

1. Install APK on your Android device.
2. Grant **Notification Listener Access** when prompted (required for Android to read active media sessions).
3. Tap **Disable Battery Optimization** so your OEM (HyperOS, OneUI, ColorOS) doesn't freeze the service.
4. Paste your **Discord User Token**.
5. Play any track on **Apple Music** — your Discord profile will immediately show *Listening to Apple Music* with live cover art, song title, artist, and timestamps!

---

## ⚠️ Security & Discord ToS Note

Connecting to Discord's Gateway with a user token falls into selfbot territory according to Discord Terms of Service. This app implements strict rate-limiting (1.2s debounce on track changes) and heartbeating to mirror official client behavior, but usage is at your own discretion.
