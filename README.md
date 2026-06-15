# Sumo — Voice client for Pentad

Native Android voice companion for [pentadsumo](https://github.com/Sumothewrestler/sumopentad).

**What it does:**

1. **Talk to Sumo from anywhere.** Tap the home-screen icon (or say *"Hey Google, open Sumo"*) → app opens straight into mic-active mode → speak → reply plays. Works through Bluetooth headsets automatically.
2. **Speaks task reminders at the due time.** If "Start spoken reminders" is enabled, the app polls every 60s and at each task's exact due time, it speaks the task aloud through your phone speaker or paired Bluetooth headset. No notification, no ringtone — like an assistant just piping up.

No CRUD UI in this app. Task management still happens via the **pentadsumo webapp** on the browser/MacBook. This app is a microphone + speaker that talks to the same backend.

---

## Architecture

```
┌───────────────────────────────────────────────────────┐
│ Sumo Android (this repo)                              │
│  • MainActivity   — auto-listen on open               │
│  • LoginActivity  — one-time auth                     │
│  • DueTaskService — 60s polling, foreground service   │
│  • SpeechCapture  — Android built-in STT, multilingual│
│  • AudioPlayback  — MediaPlayer for WAV from Sarvam   │
│  • PentadClient   — Retrofit + OkHttp + cookie jar    │
└──────────────────────┬────────────────────────────────┘
                       │ HTTPS
                       ▼
┌───────────────────────────────────────────────────────┐
│ pentadsumo backend (already running in production)    │
│  POST /api/auth/login/      → accessToken             │
│  POST /api/auth/refresh/    → refresh on 401          │
│  POST /api/agent/chat/      → LLM turn                │
│  POST /api/agent/speak/     → Sarvam TTS → WAV bytes  │
│  GET  /api/tasks/task/      → list filter for reminders│
└───────────────────────────────────────────────────────┘
```

All TTS quality + multilingual translation is handled server-side by the existing Sarvam integration. The Android app just plays bytes. No on-device LLM, no Sarvam key on the device.

---

## Building locally

### Prerequisites

1. **Android Studio Hedgehog (2023.1.1) or newer** — install from <https://developer.android.com/studio>
2. **JDK 17** (bundled with Android Studio)
3. **A physical Android device** running Android 8.0 (API 26) or newer
   - Wake-word + foreground services are fragile in emulators. Always test on real hardware.

### One-time setup

```bash
# Clone
git clone git@github.com:Sumothewrestler/sumo-android.git
cd sumo-android

# Copy the SDK pointer template
cp local.properties.example local.properties
# Edit local.properties — set sdk.dir to your Android SDK path
# Android Studio fills this in automatically the first time you open the project.
```

Open the project in Android Studio. The first sync downloads dependencies (~3 minutes on a fresh machine). Subsequent builds are seconds.

### Build + side-load via USB

1. On your phone: enable **Developer Options** (tap Build Number 7 times in Settings → About Phone) and **USB Debugging**.
2. Plug the phone in via USB. Accept the "Allow debugging?" prompt.
3. In Android Studio: click **Run ▶** (or `Shift+F10`). The APK builds, installs, and launches.

Or build the APK from the CLI and side-load manually:

```bash
./gradlew assembleDebug
# APK lands at:
ls -lh app/build/outputs/apk/debug/app-debug.apk

# Send to phone (adb must be on PATH):
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## First-launch flow

1. **Permissions.**
   - **Microphone** (required) — Sumo can't hear you otherwise.
   - **Notifications** (Android 13+) — only needed if you enable spoken reminders.
2. **Login.** Username + password (your pentadsumo credentials). The access token lives in SharedPreferences; the refresh cookie lives in a persistent cookie jar. 15-minute access tokens auto-refresh.
3. **Pick your voice language** in the dropdown. Default is `en-IN` (Indian English).
4. **Toggle "Start spoken reminders"** if you want due-time auto-alerts (off by default).
5. **Test:** tap the big mic chip in the middle of the screen and say *"how many tasks do I have"*.

---

## Setting up the Google Assistant shortcut

Once installed, the app automatically registers a Google Assistant App Shortcut. You don't need to do anything special — Google Assistant indexes it within a few minutes of install.

After install, try:
- *"Hey Google, open Sumo"*
- *"Hey Google, talk to Sumo"*
- *"Hey Google, ask Sumo"*

Any of those routes Google Assistant → our `MainActivity` with the `auto_listen=true` extra → mic hot.

### Lock-screen voice

For *"Hey Google, open Sumo"* to work when the phone is locked:

1. Open Google Assistant settings (long-press the home button → Settings, or `Settings → Google → Settings for Google apps → Search, Assistant & Voice → Voice`).
2. Enable **Hey Google** + **Voice Match**.
3. Enable **Lock screen personal results**.

Some devices (especially work-profile Android) restrict lock-screen Assistant — that's an OS-level constraint we can't override.

### Bluetooth headset button

If your Bluetooth headset has an Assistant button (most do), pressing it triggers Google Assistant on Android. You can then say *"open Sumo"* and the same path fires — reply plays back through the headset.

---

## Spoken reminders — how they work

When you toggle **Start spoken reminders** on:

- A foreground service starts (`DueTaskService`), shown by a small persistent notification: "Sumo Reminders".
- Every 60 seconds, the service:
  1. Calls `GET /api/tasks/task/?status__in=pending,in_progress&dueDate=<today>`
  2. For each task whose `dueDate + dueTime` falls between the previous tick and now, calls `POST /api/agent/speak/` to get the spoken reminder
  3. Plays it via `MediaPlayer` — routes to Bluetooth headset automatically when one is connected
- Each task is announced once per day (tracked in SharedPreferences; resets at midnight).

To stop reminders: tap **Stop spoken reminders** in the app, or tap **Stop** in the notification.

**Battery cost:** ~1-2% extra daily on average. The foreground service holds no mic; it's purely outbound HTTP + occasional playback.

**Tasks without a `dueTime`:** skipped. Voice reminders are time-of-day specific. Date-only tasks live in the webapp.

---

## Project layout

```
sumo-android/
├── app/
│   ├── build.gradle.kts                         # deps + Android config
│   └── src/main/
│       ├── AndroidManifest.xml                  # permissions, services, activities
│       ├── java/in/rfidpro/sumo/
│       │   ├── SumoApp.kt                       # Application: init + channels
│       │   ├── LoginActivity.kt                 # auth screen
│       │   ├── MainActivity.kt                  # voice screen + Compose UI
│       │   ├── api/
│       │   │   ├── PentadApi.kt                 # Retrofit interface + DTOs
│       │   │   ├── PentadClient.kt              # OkHttp + Retrofit singleton
│       │   │   ├── AuthInterceptor.kt           # 401 → refresh → retry
│       │   │   └── TokenStore.kt                # SharedPreferences wrapper
│       │   ├── service/
│       │   │   ├── DueTaskService.kt            # foreground reminder service
│       │   │   ├── SpeechCapture.kt             # SpeechRecognizer wrapper
│       │   │   └── AudioPlayback.kt             # MediaPlayer wrapper
│       │   └── ui/
│       │       └── Theme.kt                     # brand palette + Material3 theme
│       └── res/
│           ├── values/strings.xml
│           ├── values/colors.xml
│           ├── values/themes.xml
│           ├── drawable/ic_launcher_background.xml
│           ├── drawable/ic_launcher_foreground.xml
│           ├── mipmap-anydpi-v26/ic_launcher*.xml
│           └── xml/
│               ├── shortcuts.xml                # Google Assistant App Shortcut
│               ├── backup_rules.xml
│               └── data_extraction_rules.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/gradle-wrapper.properties
```

---

## Out of scope (intentional)

- **iOS app.** v1 is Android-only. If validated, port via Flutter later.
- **Task list / create / edit UI.** Use the webapp on your laptop. This app is voice-only.
- **Multi-account login.** Single-user, single-device.
- **Voice ID / "only my voice" filtering.** Anyone who taps the icon (or says "Hey Google, open Sumo") gets through. Personal-device assumption.
- **Always-on "Hey Sumo" wake word.** Replaced by Google Assistant + Bluetooth Assistant button. See [`plans/phase-1-starter-breezy-dream.md`](https://github.com/Sumothewrestler/sumopentad) for the design discussion.
- **Conversation history view.** Conversation state lives in the backend's Redis cache, keyed by user. The Android app is stateless beyond auth.
- **Tap-to-talk button mid-screen.** The big chip ON the screen IS the tap-to-talk affordance; we don't have a secondary one.

---

## Future v2

- **FCM push instead of 60s polling** — instant reminders, ~1-2 days of backend + Android work.
- **Reminder lead times** ("5 minutes before due") — needs UI in the webapp manifest.
- **Quiet hours** so reminders don't fire 11pm–7am.
- **Body-based refresh** in the backend if the cookie jar proves fragile across Android updates.
- **iOS port** via Flutter once Android usage proves the voice-first thesis.

---

## License

Private. Not for distribution. Single-user personal client.
