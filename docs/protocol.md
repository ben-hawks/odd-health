# Even R1 ring health protocol

Working notes for reading health data from the Even Realities R1 ring over BLE.

Almost everything here was first worked out by the
[hermes-g2](https://github.com/not-benny/hermes-g2) project (`docs/ring-health`,
`notes/ring-*.md`), from captures of the official app and ring firmware analysis
in August 2026. It is restated here so this repo stands alone. Each section is
marked:

- **confirmed** — reproduced on our ring (firmware 2.3.2.0008, hardware
  603MV1.9.3), see [First capture](#first-capture-2026-11-17)
- **documented** — taken from hermes-g2, not yet reproduced on our ring
- **open** — unknown or unverified

## Link (confirmed)

- The ring must already be bonded to the phone by the official Even app. The
  link is encrypted; there is no application-level secret.
- The ring has one phone link. On Android every app connected to the ring
  shares it: each app's commands are answered and every app receives every
  notification. The glasses use a separate link; see
  [ring-and-glasses.md](ring-and-glasses.md).
- Request MTU 247 after service discovery, before subscribing.

Service `bae80001-4f05-4503-8e65-3af1f7329d1f`:

| Characteristic | Use |
|---|---|
| `bae80012` | command writes (write without response) |
| `bae80013` | command responses and health data (notify) |
| `bae80010` / `bae80011` | separate status / phone-notify channel (write / notify) |

A second, protobuf-framed channel (`0xAA` prefix) also exists and the official
app polls it for live values. It is not needed for health history.

## Framing (confirmed)

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

## Commands (confirmed, except where noted)

Health (`module=2`), `sub-command=1` (daily). A request is a bare frame with no data.

| cmd | Metric |
|---:|---|
| 1 | heart rate |
| 2 | SpO2 |
| 3 | temperature (no answer from our ring, not even an ack) |
| 4 | HRV |
| 5 | activity: steps and calories |
| 6 | sleep |

Sub-commands 2 (`point`) and 3 (`measure`) exist; their payloads are **open**.

System (`module=1`, `cmd=0`), selected by sub-command:

| sub | Name | Notes |
|---:|---|---|
| 0x01 | deviceStatus | response `[battery percent][state: 1 charging, 2 not charging, 3 full]…` |
| 0x02 | deviceInfo | response: two 16-byte NUL-padded strings, firmware then hardware version |
| 0x05 | systemTime | status 2, data `[tz offset minutes i16 LE][epoch seconds u32 LE]` |
| 0x08 | pairAuth | status 0, data `01`; opens the session; ring acks with data `00` and also sends a push with data `00` |
| 0x0E | healthSettingsStatus | set: `[epoch u32 LE][01][19 zero bytes]` enables tracking |
| 0x7E | packetAck | status 1, see below |

**Never send** 0x07 touchSwitch, 0x09 otaStart, 0x0A advStart, 0x0C setAlgoKey,
0x11 nvRecover, 0x12 powerControl, 0x13 pairDelete, 0x82 removeRingNotify. They
change firmware, pairing, keys, stored data, power state or the ring's link to
the glasses. `tools/r1/protocol.py` refuses to build them.

### Session

Every request gets an ack (status 3, same sequence number, usually no data).
Data follows as separate pushes (status 2) numbered by the ring's own counter.

1. Write the pairAuth frame; wait for its ack on `bae80013`.
2. Optionally deviceInfo, deviceStatus.
3. One daily request per metric, about 200 ms apart.
4. For every health push received, write packetAck with data
   `[module][cmd][sub-command][00][sequence of the received frame u16 LE][00 00 00 00]`.
   This is what makes the ring send its next stored batch.

The official app also sends healthSettingsStatus and systemTime each session.
A ring that the Even app syncs regularly already has both set.

**Open:** how many days the ring keeps, and whether the packetAck loop walks
back through all of them. Firmware analysis suggests about a day of offline
storage and an automatic push every 3 hours while a phone session is open; see
[ring-and-glasses.md](ring-and-glasses.md#storage-on-the-ring). Our export has a third source label, `ring1MultiData`,
on activity rows, which is the app's name for multi-packet history batches.

## Payloads

### Heart rate, SpO2, HRV — hourly (confirmed)

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

### Activity — 10-minute buckets (confirmed)

```
[0] count u8   [1..2] timezone offset i16 LE   [3..6] local-midnight epoch u32 LE
then 7-byte records: [slot u8][steps u16][active kcal u16][total kcal u16]
```

Time = local midnight + slot × 600. Resting kcal = total − active.

### Sleep (confirmed)

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

## First capture, 2026-11-17

nRF Connect on Android, connected to the ring while the Even app was also
connected. The log holds one Even app sync (seen passively: only the ring's
side is visible) followed by our own read-only frames.

- Both CRCs, fragment reassembly (3-fragment activity batches) and the frame
  header check out on every one of 54 frames.
- Decoded values against the Even export taken an hour earlier: heart rate 210,
  HRV 216, SpO2 213, steps 259 and calories 259 values match, **none differ**.
  The only unmatched values are hours after the export was taken.
- All of our requests except temperature were acked. pairAuth, deviceInfo and
  deviceStatus returned the expected data.
- The Even app's sync received **four days** per vital (three complete days plus
  the current one), one push per day, and three days of activity. Those days were
  already in the export, so the ring re-sends days it has synced before.
- Our own requests three minutes later returned only the current day for heart
  rate, SpO2 and HRV, and an ack with no data for activity and sleep.

**Open after this capture:**

- What decides whether a request returns the multi-day history or only the
  current day: packetAck from the Even app, first request on a new connection,
  or something else. Next test: Even app stopped, fresh connection, request each
  metric without acknowledging, then acknowledge and request again.
- Sleep has not been seen on the wire.
- The ring also pushed a 121-byte system `0x11` frame containing its serial
  numbers and what looks like calibration data, during the Even app's sync.
  We ignore it.

## Second capture, 2026-11-17 (listener app 0.1)

- Decoded values again match the export with no differences.
- A request from our app returned the full history: four days each of heart
  rate, SpO2, HRV and activity. So the history is not reserved for the Even app.
- After that the app acknowledged all 16 pushes with packetAck. Every later
  request, ours and the Even app's, returned only the current day. Still two
  explanations: acknowledged days are not re-sent, or only the first request
  on a connection gets the history. Test: with acknowledging off, request
  twice on one connection.
- **Status 11.** While the Even app was pulling a large non-health transfer,
  the ring answered each of our health requests with a frame of status 11 and
  no data instead of an ack. Treat it as "busy, ask again later".
- **Other traffic on `bae80013`.** The same fragment transport also carries
  inner frames that do not start with `0x64`: a 10-byte header, a 1-byte reply
  and about 4 KB transfers in 18 fragments whose content looks like ring
  firmware logs. The Even app requests these after it starts. The large ones
  fail our CRC-32 check; we skip them.
- System sub-command `0x84` exists (seen as an ack to the Even app); unknown.
- Sleep still not seen: both sleep requests were acked with no data.

## Third capture, 2026-11-17 (listener app 0.2)

- 75 seconds on the link with no drops, on the same connection that 0.1 had
  been knocking over. The ring reported the `bae80013` subscription already on,
  so the app wrote nothing during setup.
- Two requests a few seconds apart, acknowledging off: both returned only the
  current day. This does not settle the history question, because the same
  connection had already been acknowledged earlier. The deciding test is a
  request on a fresh connection with acknowledging off.
- Ring gestures made during the capture produced nothing on `bae80013`, and
  nothing reached us on `bae80011` (which we no longer subscribe to).
- The ring pushed system `wearStatus` (0x03) by itself, one data byte, value 2.
  Meaning of the value not yet known.

## Overnight capture, 2026-11-17 (listener app 0.2)

The app stayed connected from 01:05 to 05:38 with no drops, alongside the Even
app's own background connection. The one disconnect came when the Even app was
opened and reconnected.

**Sleep, confirmed on the wire.** One type-1 record, pushed during the Even
app's sync at 05:35, eight minutes after the end time it carries. Every
consistency check holds: asleep = REM + light + deep, end − start = asleep +
awake, each stage's runs sum to its total, the four share bytes sum to 100.
Hourly syncs before waking got only an ack for sleep, so the ring hands over a
night only once it has ended.

**Acknowledged data is consumed.** The Even app synced in the background at
:07 every hour. Each sync returned only the hours from the last synced hour
onwards (01:07 → hour 1, 02:07 → hours 1–2, 03:07 → hours 2–3 …), with the
current, unfinished hour re-sent each time. So the ring keeps a cursor that
packetAck advances; unacknowledged days stay available to the next request.
For us this means:

- never acknowledge, so the Even app's history is unaffected;
- store records by metric and timestamp and let a later value for the same
  hour replace an earlier one.

**Other periodic traffic** (all seen passively, sent by the Even app or the ring):

| What | When |
|---|---|
| deviceStatus request (battery) | Even app, every 15 minutes |
| health sync, all five metrics | Even app, hourly at :07 |
| heartbeatPack (0x7F) push, 7 bytes | ring, hourly; same layout as deviceStatus |
| wearStatus (0x03) push, 1 byte | ring, on change: 3 at 03:34:49, back to 2 at 03:35:27 |
| ~90 KB of non-health transfers | Even app, once around 04:51 |

wearStatus is 2 while worn; 3 is probably "not detected", since it appeared
for 40 seconds in the middle of the night. Unconfirmed.

## Wear and charging test, 2026-11-17 (app 0.17)

Ring on the charger 09:25–09:54, off the finger 12:16–12:20.

| Time | Event | wearStatus (system 0x03) |
|---|---|---|
| 09:25:08 | put on the charger; deviceStatus "charging" at the same moment | 1 |
| 09:54:25 | taken off the charger, not on a finger | 1, then 3 |
| 10:06:18 | put on a finger | 2 |
| 12:16–12:20 | taken off for 4 minutes, put back | nothing sent |

So **1 = on the charger, 2 = worn, 3 = not worn**. The ring does not watch
wear continuously: it reports changes at charger events and when a
measurement fails (the 40-second "3" seen overnight), so a short removal
between heart-rate readings can go unreported.

While charging, the ring pushes deviceStatus every 30 seconds (34 % to 67 %
in 30 minutes). The phone link dropped and reconnected about 50 times in those
30 minutes (GATT status 8 and 133) and stayed steady once the ring was back on
a finger; this appears to be the ring's behaviour on the charger.

## Sleep detail test, 2026-11-17

Looking for more than the nightly temperature. Sent with nRF Connect after
pairAuth (acked as usual):

| Request | Frame | Reply |
|---|---|---|
| sleep detail (health 6, sub-command 2), status 0 (request) | `00b03c1ea2640264014f0006020c003d3e` | none, not even an ack |
| sleep detail, status 1 (set) | `00f9c33a59640264024f0106020c0018ab` | none |
| sleep daily (health 6, sub-command 1), status 0 | `0068bdb145640264034f0006010c008816` | ack, no data (night already synced by the Even app) |

The firmware analysis says the sleep-detail, heart-rate-measure and
SpO2-measure handlers require request-flag bit 1 and reject bit 0, which
would be status 2. Status 0 and 1 being ignored fits that. Status 2 is
untested: `001ab047b8640264044f0206020c001229`.
