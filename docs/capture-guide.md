# What to collect

Goal: confirm on our own ring that the documented protocol still holds, and
close the open items in [protocol.md](protocol.md). In rough order of value.

Captures contain health data, Bluetooth addresses and device serials. Put them
in `captures/` (git-ignored) and do not commit them.

## 0. Versions

Write down: phone model and Android version, Even app version, ring firmware
version (Even app → ring settings). The published notes date from August 2026;
a firmware update since then is the most likely reason for any mismatch.

## 1. Direct read with nRF Connect — no root, no code

This is the real test: can a client other than the Even app read the ring.

1. Force-stop the Even app (Android Settings → Apps → Even → Force stop). It
   must stay stopped for the whole test; the ring only talks to one client.
2. Install **nRF Connect for Mobile**. Open it, find the ring under the bonded
   devices, connect.
3. Request MTU 247 (connection menu → Request MTU).
4. Open service `bae80001-…` and enable notifications on `bae80013` and `bae80011`.
5. On a computer run `python3 tools/r1tool.py frames`. It prints one hex frame
   per line.
6. In nRF Connect write each frame to `bae80012`, in order, as a byte array,
   using write type "command" / "without response". Start with `pairAuth` and
   check that a notification comes back on `bae80013` before continuing.
7. Save the nRF Connect log as text into `captures/`.
8. Decode and check against a fresh export taken the same day:

   ```
   python3 tools/r1tool.py decode captures/nrf.log > captures/decoded.jsonl
   python3 tools/r1tool.py compare captures/decoded.jsonl captures/Even_health_data
   ```

Every health push in `decoded.jsonl` includes a `packet_ack_hex`. Writing it
back should make the ring send its next stored batch; try this for one metric
and note how far back the data goes.

While nRF Connect is still connected, also run the gesture and coexistence
checks in [ring-and-glasses.md](ring-and-glasses.md#test-on-real-hardware).

The default frames only read. `frames --with-setup` adds the clock sync and
health-enable writes the official app sends; leave them out unless the decoded
records come back without timestamps.

If nothing answers `pairAuth`: check the Even app is really stopped (it restarts
itself for background sync), toggle Bluetooth, retry. Then reopen the Even app
and confirm it still syncs normally.

## 2. Even app log during a sync

The Even app has logged its ring traffic and parsed values in plain text. With
USB debugging on:

```
adb logcat -c
adb logcat --pid="$(adb shell pidof -s com.even.sg)" > captures/even-sync.log
```

Open the Even app, pull to sync health, wait a minute, stop the capture. Run
`r1tool.py decode` on it as well; if the app prints raw frames they decode
directly, and its own parsed lines show the command order.

## 3. Bluetooth HCI snoop log of an Even app sync

Byte-exact record of what the official app does. Needed for the open items:
history depth, the packetAck loop, the outbound CRC-16, sleep type 2.

1. Developer options → Enable Bluetooth HCI snoop log. Toggle Bluetooth off and on.
2. Open the Even app and let it do a full sync (best after the ring has been
   disconnected for a few hours, so there is history to pull).
3. `adb bugreport captures/bugreport.zip`; the log is `btsnoop_hci.log` inside
   (`FS/data/misc/bluetooth/logs/`, or `FS/data/log/bt/` on Samsung).
4. Turn the snoop log off again.
5. Convert to text and decode:

   ```
   tshark -r btsnoop_hci.log -Y btatt.value -T fields \
     -e frame.time_relative -e btatt.opcode -e btatt.handle -e btatt.value \
     > captures/snoop.txt
   python3 tools/r1tool.py decode captures/snoop.txt
   ```

## 4. A fresh export

Export health data from the Even app right after steps 1–3 so the decoded
values can be compared with rows from the same hours.
