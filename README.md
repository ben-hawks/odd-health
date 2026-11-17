# odd-health

Read health data from an Even Realities R1 ring over Bluetooth, without the
Even app, and sync it to Health Connect on Android.

Status: protocol confirmed on a real ring (firmware 2.3.2.0008); every metric
decodes to the values in the Even app's export. The Android app stores ring
data and syncs it to Health Connect.

## Layout

- [docs/protocol.md](docs/protocol.md) — the ring's BLE health protocol, with
  what is confirmed and what is still open
- [docs/ring-and-glasses.md](docs/ring-and-glasses.md) — how the ring, glasses
  and phone are connected, and what holding the ring does and does not affect
- [docs/capture-guide.md](docs/capture-guide.md) — what to capture from a real
  ring and how
- `tools/r1/` — Python framing and payload decoders (standard library only)
- `tools/r1tool.py` — generate the read-only sync frames, decode captured logs,
  compare decoded records with an Even health export
- `tests/` — `python3 -m unittest discover -s tests`
- `android/` — the Android app: `protocol/` is the Kotlin port of `tools/r1`,
  `app/` is the listener

## Android app

Needs Android 12 or later, Health Connect, and a ring already paired through
the Even app.

- **Today**: last night's sleep, heart rate, HRV, blood oxygen, activity and
  skin temperature. Tap a card for that metric by day, with each value marked
  as in Health Connect, waiting, or kept in this app only.
- **Ring**: connection, battery, firmware, recent ring activity, and
  "Request data". The app stays connected alongside the Even app and stores
  every sync it sees. It never acknowledges data, so the Even app's history
  is unaffected. If nothing arrives for 75 minutes it asks the ring itself.
- **Sync**: Health Connect status and permissions, what is written as which
  type, and import of an Even health export for older history.
- **Debug logging** (Ring tab, off by default): records raw Bluetooth traffic
  and app events to daily files that `tools/r1tool.py decode` reads, to share
  when something goes wrong.

What goes to Health Connect: hourly average heart rate plus live readings,
hourly HRV (RMSSD) and SpO₂, 10-minute steps and total/active calories, sleep
sessions with stages (score and efficiency in the notes), and nightly skin
temperature as the change from a 30-night baseline. Hourly min/max and the
sleep score are shown in the app only.

### Code

- `android/protocol`: ring framing and payload decoding (pure Kotlin).
- `android/core`: the record model, ring and Even-export ingestion, and the
  sync engine. A sync destination is a `SyncTarget`; adding another health
  service means adding one implementation.
- `android/healthconnect`: the Health Connect `SyncTarget`.
- `android/app`: Room storage, the Bluetooth service, Compose UI (Material 3
  Expressive, Navigation 3).

Build: `cd android && ./gradlew :app:assembleDebug` (Android SDK with platform
37, JDK 17+). Tests: `./gradlew :protocol:test :core:test :app:testDebugUnitTest`.
Screenshots of each screen: `./gradlew :app:recordRoborazziDebug`, written to
`android/app/screenshots/`. Debug builds are signed with `android/debug.keystore`
so each build installs over the previous one.

## Plan

1. **Verify on a real ring.** Follow the capture guide; fix the decoders until
   `r1tool.py compare` reports no mismatches against an Even export.
2. **Android app.** Done: listener, local storage, Even export import.
3. **Health Connect.** Done; see above.

This app and the Even app share the ring's phone link, and both receive
everything the ring sends. The ring's link to the glasses is separate, so
gestures should be unaffected; see
[docs/ring-and-glasses.md](docs/ring-and-glasses.md).

## Credits

The protocol was reverse-engineered by the
[hermes-g2](https://github.com/not-benny/hermes-g2) project, with firmware-level
detail from [evenRealities-openCFW](https://github.com/kalanihelekunihi/evenRealities-openCFW).
The code here is written from their published notes.

## Licence

GPL-3.0; see [LICENSE](LICENSE).

Captures and health exports are personal data; `captures/` is git-ignored.
