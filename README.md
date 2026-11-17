# odd-health

Read health data from an Even Realities R1 ring over Bluetooth, without the
Even app, and sync it to Health Connect on Android.

Status: protocol notes and an offline decoder. Nothing has talked to a ring yet.

## Layout

- [docs/protocol.md](docs/protocol.md) — the ring's BLE health protocol, with
  what is confirmed and what is still open
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

The main constraint: the ring answers one client at a time, so the sync app and
the Even app cannot both be connected.

## Credits

The protocol was reverse-engineered by the
[hermes-g2](https://github.com/not-benny/hermes-g2) project (GPL-3.0). The code
here is written from its published protocol notes.

Captures and health exports are personal data; `captures/` is git-ignored.
