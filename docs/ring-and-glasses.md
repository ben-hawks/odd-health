# Ring, glasses and phone: who is connected to whom

Question: if our app holds the ring's connection to the phone, do ring gestures
on the G2 glasses keep working?

Short answer: the firmware evidence says yes. The ring talks to the glasses
over its own Bluetooth link, separate from its link to the phone. Sharing the
phone link with the Even app is confirmed on our ring; the gesture test at the
end has not been run yet.

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

### Sharing the phone link (confirmed 2026-11-17)

On Android the Even app and a second app are connected to the ring at the same
time over one physical link. In the first capture nRF Connect received the
whole of an Even app sync without sending anything, and its own requests were
answered while the Even app stayed connected. So:

- The Even app cannot be locked out, and does not need to be.
- Our app can collect data just by listening whenever the Even app syncs.
- Our app can also ask for data itself. Short read requests did not collide
  with the Even app in this capture; hermes-g2 saw timeouts when both apps
  were sending at once, so keep our own traffic brief.
- **The notification switch is shared too.** Turning notifications off on
  `bae80013` from one app turns them off for the Even app as well. Our app must
  never disable them.
- **Do not write the `bae80011` subscription.** Three times out of three
  patterns, writing it on a link the Even app had already opened as the phone
  was followed by the link dropping six seconds later (once from nRF Connect,
  then about twenty times in a row from listener 0.1, which re-wrote it on every
  reconnect and so kept the ring in a reconnect loop). The one time the write
  came before the Even app's pairAuth, the link stayed up. `bae80010`/`bae80011`
  is the channel the glasses use, and the firmware notes say subscription
  events on the service drive glasses-role assignment, so the likely cause is
  the ring treating the phone link as a glasses link. This is an inference from
  timing, not proven. From 0.2 the app leaves `bae80011` alone, and only writes
  the `bae80013` subscription if the ring reports it off.

## Storage on the ring

From the firmware analysis, relevant to how often we must sync:

- The ring starts a sync by itself when a phone-role session is open and at
  least 3 hours have passed since the last one: heart rate, SpO2, HRV, activity,
  then unsynced sleep.
- Offline queues: 144 activity buckets, 24 records each for heart rate, SpO2
  and HRV, 16 sleep records.
- Measured on our ring: a sync returned three complete past days plus the
  current day for heart rate, SpO2 and HRV, and three days of activity. So the
  ring keeps at least three days.

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
