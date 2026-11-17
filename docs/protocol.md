# Even R1 ring health protocol

Working notes for reading health data from the Even Realities R1 ring over BLE.

Almost everything here was first worked out by the
[hermes-g2](https://github.com/not-benny/hermes-g2) project (`docs/ring-health`,
`notes/ring-*.md`), from captures of the official app and ring firmware analysis
in August 2026. It is restated here so this repo stands alone. Each section is
marked:

- **documented** — taken from hermes-g2, not yet reproduced on our ring
- **export-consistent** — also agrees with our own Even health export
- **open** — unknown or unverified

Nothing below has been confirmed against our ring over the air yet. That is the
next step; see [capture-guide.md](capture-guide.md).

## Link (documented)

- The ring must already be bonded to the phone by the official Even app. The
  link is encrypted; there is no application-level secret.
- The ring serves **one command session at a time**. If the Even app is running
  it holds the session and a second client's commands time out. Force-stop the
  Even app before connecting.
- Request MTU 247 after service discovery, before subscribing.

Service `bae80001-4f05-4503-8e65-3af1f7329d1f`:

| Characteristic | Use |
|---|---|
| `bae80012` | command writes (write without response) |
| `bae80013` | command responses and health data (notify) |
| `bae80010` / `bae80011` | separate status / phone-notify channel (write / notify) |

A second, protobuf-framed channel (`0xAA` prefix) also exists and the official
app polls it for live values. It is not needed for health history.

## Framing (documented)

Every write and every notification:

```
[0]      fragment index u8   counts down; 0 on the last or only fragment
[1..4]   CRC-32 u32 LE       over the whole reassembled inner frame; same in every fragment
[5..]    slice of the inner frame
```

CRC-32: polynomial `0x1EDC6F41`, MSB-first, init 0, no final xor. Concatenate
slices in arrival order (descending index) and check the CRC.

Inner frame:

```
[0]       0x64
[1]       module        1 system, 2 health, 3 sport
[2]       0x64
[3..4]    sequence u16 LE
[5]       status        0 request, 1 set, 2 push, 3 ack
[6]       command
[7]       sub-command
[8..9]    inner length u16 LE (header + data)
[10..11]  CRC-16 u16 LE
[12..]    data
```

Ring → phone frames carry CRC-16/MODBUS computed with bytes 10..11 as zero.

**Open:** the CRC-16 the official app puts on phone → ring frames is not
identified (it is not a common CRC-16 over any obvious span). The ring reportedly
does not check it; we send CCITT-FALSE like hermes-g2 does.

## Commands (documented)

Health (`module=2`), `sub-command=1` (daily). A request is a bare frame with no data.

| cmd | Metric |
|---:|---|
| 1 | heart rate |
| 2 | SpO2 |
| 3 | temperature (ring reportedly does not answer) |
| 4 | HRV |
| 5 | activity: steps and calories |
| 6 | sleep |

Sub-commands 2 (`point`) and 3 (`measure`) exist; their payloads are **open**.

System (`module=1`, `cmd=0`), selected by sub-command:

| sub | Name | Notes |
|---:|---|---|
| 0x01 | deviceStatus | response `data[0]` = battery percent |
| 0x02 | deviceInfo | response starts with 16-byte NUL-padded firmware version |
| 0x05 | systemTime | status 2, data `[tz offset minutes i16 LE][epoch seconds u32 LE]` |
| 0x08 | pairAuth | status 0, data `01`; opens the session; ring acks with data `00` |
| 0x0E | healthSettingsStatus | set: `[epoch u32 LE][01][19 zero bytes]` enables tracking |
| 0x7E | packetAck | status 1, see below |

**Never send** 0x09 otaStart, 0x0A advStart, 0x0C setAlgoKey, 0x11 nvRecover,
0x12 powerControl, 0x13 pairDelete. They change firmware, pairing, keys, stored
data or power state. `tools/r1/protocol.py` refuses to build them.

### Session

1. Write the pairAuth frame; wait for its ack on `bae80013`.
2. Optionally deviceInfo, deviceStatus.
3. One daily request per metric, about 200 ms apart.
4. For every health push received, write packetAck with data
   `[module][cmd][sub-command][00][sequence of the received frame u16 LE][00 00 00 00]`.
   This is what makes the ring send its next stored batch.

The official app also sends healthSettingsStatus and systemTime each session.
A ring that the Even app syncs regularly already has both set.

**Open:** how many days the ring keeps, and whether the packetAck loop walks
back through all of them. Our export has a third source label, `ring1MultiData`,
on activity rows, which is the app's name for multi-packet history batches.

## Payloads

### Heart rate, SpO2, HRV — hourly (export-consistent)

```
[0]       record count u8
[1..2]    timezone offset minutes i16 LE
[3..6]    local-midnight epoch seconds u32 LE
[7..10]   timestamp of the current reading u32 LE
[11..]    current reading: u8 (HR, SpO2) or u16 LE (HRV)
then      records
```

- HR / SpO2 record, 4 bytes: `[hour][avg][max][min]`
- HRV record, 7 bytes: `[hour][avg u16][max u16][min u16]`, milliseconds

Record time = local midnight + hour × 3600. The header fields at 1..10 are zero
if the ring's clock was never set; treat such records as undated.

The ring stores nothing finer than hourly min/max/avg. The header's current
reading is the only live value, so polling it is the way to get denser heart rate.

### Activity — 10-minute buckets (export-consistent)

```
[0] count u8   [1..2] timezone offset i16 LE   [3..6] local-midnight epoch u32 LE
then 7-byte records: [slot u8][steps u16][active kcal u16][total kcal u16]
```

Time = local midnight + slot × 600. Resting kcal = total − active.

### Sleep (export-consistent)

Type 1, a full night:

```
[0]       1
[1]       efficiency percent
[2]       sleep score
[3]       0
[4..7]    stage share percentages
[8..9]    skin temperature u16 LE, 0.1 °C; 0 = none
[10..11]  timezone offset minutes i16 LE
[12..15]  start epoch seconds u32 LE
[16..19]  end epoch seconds u32 LE
[20..29]  asleep, awake, REM, light, deep — seconds, u16 LE each
[30]      run count
[31]      0
[32..]    runs: [stage u8][length u16 LE in 30-second units]
last 4    unknown trailer
```

Stages: 0 awake, 1 REM, 2 light, 3 deep.

Type 2 (`[0]=2`) carries only two relative second counts at 12..15 and 16..19
with no known time base. **Open.**

## What our export says

The Even app export (`Even_health_data_YYYYMMDD.zip`) is the reference the
decoders must reproduce. Ours covers May–November 2026:

| File | Resolution | Matches wire layout |
|---|---|---|
| `heart_rate.csv`, `blood_oxygen.csv`, `hrv.csv` | hourly, three rows per hour (`ty` = min/max/avg) | hourly records |
| `steps.csv`, `calories.csv` | 10 minutes; calories split resting + active | activity records |
| `sleep.csv` | per night; `stages` = `{type, half_minutes}` runs, types 0–3 | sleep type 1 |
| `skin_temperature.csv` | one value per sleep record | sleep bytes 8..9 |

`source` is `R1` (the app's consolidated history), `ring1Notify` (a direct ring
push) or `ring1MultiData` (multi-packet history batch). In our export every
`ring1Notify` skin temperature row shares its timestamp with a sleep row, and in
every `ring1Notify` sleep row total = REM + light + deep, as the layout predicts.
The `stages` JSON uses the same run encoding as the wire format.
