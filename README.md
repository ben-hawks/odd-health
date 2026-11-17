# odd-health

Read health data from an Even Realities R1 ring over Bluetooth, without the
Even app, and sync it to Health Connect on Android.

Status: protocol confirmed on a real ring (firmware 2.3.2.0008) with nRF Connect;
heart rate, HRV, SpO2, steps and calories decode to exactly the values in the
Even app's export. Sleep is not yet verified. The Android app listens and
records; it does not store history or write to Health Connect yet.

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

Needs Android 12 or later and a ring already paired through the Even app.

It keeps a connection to the ring alongside the Even app and records every
notification the ring sends, including the Even app's own syncs. On screen it
shows the connection state and one line per decoded frame.

- **Request data** opens a session and asks the ring for device info, battery,
  heart rate, SpO2, HRV, activity and sleep. Reads only.
- **Acknowledge health data** (off by default) answers each health push with a
  packetAck, which marks that data as synced on the ring.
- **Share log** sends the current day's capture file. `tools/r1tool.py decode`
  reads it directly.

The app never turns ring notifications off and cannot build the commands listed
as dangerous in [docs/protocol.md](docs/protocol.md).

Build: `cd android && ./gradlew :app:assembleDebug` (needs the Android SDK,
platform 35). The APK lands in `android/app/build/outputs/apk/debug/`. Debug
builds are signed with the key in `android/debug.keystore` so each build
installs over the previous one. Tests: `./gradlew :protocol:test`.

## Plan

1. **Verify on a real ring.** Follow the capture guide; fix the decoders until
   `r1tool.py compare` reports no mismatches against an Even export.
2. **Android app.** Listener and capture log are done. Next: store decoded
   records locally, request data by itself when the Even app has not synced.
3. **Health Connect.** Write the stored records:

   | Ring data | Health Connect record |
   |---|---|
   | hourly heart rate, plus polled current value | `HeartRateRecord` |
   | hourly HRV | `HeartRateVariabilityRmssdRecord` (if the ring's HRV is RMSSD; unconfirmed) |
   | hourly SpO2 | `OxygenSaturationRecord` |
   | 10-minute steps | `StepsRecord` |
   | 10-minute calories | `TotalCaloriesBurnedRecord`, `ActiveCaloriesBurnedRecord` |
   | sleep with stages | `SleepSessionRecord` |
   | nightly skin temperature | `SkinTemperatureRecord` |

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
