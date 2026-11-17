# odd-health

Read health data from an Even Realities R1 ring over Bluetooth, without the
Even app, and sync it to Health Connect on Android.

Status: protocol confirmed on a real ring (firmware 2.3.2.0008) with nRF Connect;
heart rate, HRV, SpO2, steps and calories decode to exactly the values in the
Even app's export. Sleep is not yet verified. No Android app yet.

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

## Plan

1. **Verify on a real ring.** Follow the capture guide; fix the decoders until
   `r1tool.py compare` reports no mismatches against an Even export.
2. **Android app.** Kotlin port of `tools/r1`, a background sync that connects
   to the bonded ring, runs the session and stores records locally.
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
