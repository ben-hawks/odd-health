# Ring, glasses and phone: who is connected to whom

Question: if our app holds the ring's connection to the phone, do ring gestures
on the G2 glasses keep working?

Short answer: the firmware evidence says yes. The ring talks to the glasses
over its own Bluetooth link, separate from its link to the phone. None of this
is tested on our hardware yet; the test is at the end.

Sources: ring firmware analysis in
[evenRealities-openCFW](https://github.com/kalanihelekunihi/evenRealities-openCFW)
(`r1/docs/protocol.md` and `r1/docs/correlation/`, firmware 2.2.6–2.2.8) and the
[hermes-g2](https://github.com/not-benny/hermes-g2) app.

## Topology

```
            phone link (bae8 service, "phone role")
   phone  ────────────────────────────────────────  ring
     │                                               │
     │ phone ↔ glasses link                          │ glasses link ("glasses role")
     │                                               │
   glasses ──────────────────────────────────────────┘
```

- The ring is a BLE peripheral configured for **three simultaneous links**, and
  its firmware tracks two roles: one phone, one pair of glasses.
- The glasses connect to the ring directly. The ring stores two 6-byte glasses
  addresses (one per arm) and only keeps a glasses link to a matching address.
  It uses its own connection parameters for that link.
- Touch input goes ring → glasses on that link. The glasses then report ring
  input to whichever phone app is connected to *them* (service ids `0x90`/`0x91`
  in the G2 protocol). hermes-g2 relies on this: its ring status is documented as
  "glasses-forwarded ring events still work without" a direct ring connection.
- The ring also sends vitals straight to the glasses for their own health display.
- `pairAuth` with data `01`, the first frame of our session, claims the **phone**
  role on the link it arrives on. It does not touch the glasses role.

So "one client at a time" means one *phone-role* session. The glasses are not
that client.

## What could break gestures

Only commands that change the ring's glasses state. We never send them, and
`tools/r1/protocol.py` refuses to build them:

| Command | Effect |
|---|---|
| `0x0A` advStart | replaces the stored glasses addresses; disconnects a non-matching glasses link |
| `0x82` removeRingNotify | erases the stored glasses addresses |
| `0x07` touchSwitch | selector 2 opens or closes the glasses touch source |
| `0x13` pairDelete, `0x12` powerControl, `0x09` otaStart, `0x11` nvRecover, `0x0C` setAlgoKey | pairing, power, firmware, stored data, keys |

Pairing the ring with glasses (`advStart`) is done once by the Even app and is
stored on the ring. If the glasses are ever re-paired or replaced, that has to
be done in the Even app with our app paused.

## What our app does take away from the Even app

While our app runs the phone session, the Even app's own ring features compete
with it: health sync into the Even app, ring battery and settings screens, ring
firmware updates. Glasses features are unaffected because the Even app keeps
its own connection to the glasses.

Two consequences for the design:

1. **The app needs a "release the ring" switch**, for firmware updates and
   re-pairing in the Even app.
2. **Acknowledged data is gone.** `packetAck` marks records as synced on the
   ring. Whichever app acknowledges a batch is the one that gets it, so if our
   app owns the sync the Even app's health history will have gaps (and the
   reverse).

On Android, two apps can be connected to the same peripheral at once: the
system shares one physical link, and notifications are delivered to every app
that subscribed. That means the Even app cannot be locked out by "holding" the
connection, and also that our app could listen to the Even app's syncs without
sending anything. This is how the Android Bluetooth stack normally behaves, not
something verified with this ring. hermes-g2 saw commands from both apps time
out when both were sending, so the working assumption is: both may be
connected, only one should be sending.

## Storage on the ring

From the firmware analysis, relevant to how often we must sync:

- The ring starts a sync by itself when a phone-role session is open and at
  least 3 hours have passed since the last one: heart rate, SpO2, HRV, activity,
  then unsynced sleep.
- Offline queues: 144 activity buckets (24 hours of 10-minute data), 24 records
  each for heart rate, SpO2 and HRV, 16 sleep records. Whether a heart-rate
  "record" is an hour or a day is not clear from the notes; assume the ring
  holds about one day and sync at least that often until measured.

## Test on real hardware

During step 1 of the [capture guide](capture-guide.md), with nRF Connect
connected to the ring and the read frames sent:

1. Put the glasses on and use ring gestures (tap, swipe, scroll). They should
   work exactly as before, with the Even app stopped and again with it running.
2. Open the Even app while nRF Connect is still connected and subscribed. Note
   whether the Even app connects to the ring, whether its health sync completes,
   and whether its frames show up in the nRF Connect log.
3. Disconnect nRF Connect, confirm the Even app syncs the ring normally.

Result 1 settles the gesture question. Result 2 decides between an app that
takes turns with the Even app and one that only listens.
