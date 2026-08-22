# WiKiLocast

Turn any Wikipedia article into an endless radio station. WiKiLocast reads full articles aloud, automatically jumping to related topics to keep things interesting — perfect for road trips, commutes, or falling asleep.

> Inspired by a road trip where I wanted to listen to Wikipedia in the car and couldn't.

---

## Features

### Playback
- **Full articles** — reads the complete Wikipedia article, not just the intro (10–60 min per topic)
- **Endless radio** — auto-jumps to a related article when one finishes (or on a timer)
- **Pause/resume** — resumes at the exact sentence where you stopped
- **Speed control** — 1× 1.5× 2× 2.5× 3×, applies instantly without restarting
- **Seekable progress bar** — drag to any point in the article
- **Skip** — jumps to a related article immediately

### Discovery
- **Search** — type any topic, pick from 8 Wikipedia results
- **Shuffle** — plays a completely random article
- **Suggestions** — related articles appear while you're listening

### Library
- **Queue** — add articles to play next from search results or suggestions
- **Bookmarks** — save articles to come back to
- **History** — last 50 articles played, auto-logged

### Controls
- **Sleep timer** — 15 / 30 / 45 / 60 / 90 min
- **Auto-jump interval** — end of article, or every 1 / 3 / 5 / 10 min
- **Background playback** — notification with controls, keeps playing with screen off

---

## Voice Quality

WiKiLocast uses Android's on-device TTS engine. The default voices sound robotic, but you can get neural-quality voices for free:

1. **Settings → Accessibility → Text-to-speech output**
2. Engine → **Google Text-to-speech**
3. Gear icon → **Install voice data**
4. Download **English (United Kingdom) — High Quality** (~80 MB, works offline)

The app automatically picks the highest quality voice available, preferring British/Australian accents for their calmer tone.

---

## Building

### Requirements
- Android Studio (or Android SDK command-line tools)
- Java 17+
- Connected Android device (API 26+) or emulator

### Build & install
```bash
git clone https://github.com/CoderShibay/WiKiLocast.git
cd WiKiLocast

# Build
ANDROID_HOME=~/Library/Android/sdk \
JAVA_HOME=$(/usr/libexec/java_home) \
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open in Android Studio and press Run.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material3 |
| Architecture | MVVM — ViewModel + Foreground Service |
| Networking | Retrofit + OkHttp |
| Navigation | Navigation Compose (3-tab bottom nav) |
| Storage | SharedPreferences + Gson (bookmarks, history) |
| TTS | Android TextToSpeech (on-device, no API keys) |

### Wikipedia APIs used
- `w/api.php?prop=extracts&explaintext=true` — full article text
- `w/api.php?list=search` — article search
- `api/rest_v1/page/related/{title}` — related articles
- `api/rest_v1/page/random/summary` — random article

> **Note:** All requests include a `User-Agent` header — Wikipedia silently blocks requests without one.

---

## License

Apache 2.0
