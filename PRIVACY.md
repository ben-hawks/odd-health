# Privacy

Odd Health has no account, no servers, no analytics, no advertising and no
crash reporting. The app does not request internet access: Android does not let
it reach the network at all.

## What the app handles

| Data | Where it comes from |
|---|---|
| Heart rate, HRV, blood oxygen (hourly), live heart rate readings | Your ring |
| Steps and calories (10-minute blocks) | Your ring |
| Sleep sessions, stages, sleep score and efficiency | Your ring |
| Nightly finger temperature | Your ring |
| Ring battery level, firmware version, whether it is being worn | Your ring |
| Resting heart rate | Calculated by the app from the above |
| Older history | An Even health export, only if you choose to import one |

The app does not collect your name, contact details, location, contacts or any
identifier other than the ring's Bluetooth name and address, which Android
provides to connect to it.

## Where it is stored

- In the app's **private storage** on your phone, which other apps cannot read.
- **Excluded from backups.** Android cloud backup and device-to-device transfer
  skip the app's data, so it is not copied to Google's servers or to a new phone.
- **Deleted when you uninstall the app** (or clear its storage in Android settings).

## When data leaves the app

Only when you choose to, in these ways:

1. **Health Connect.** With your permission, the app *writes* the data listed
   in the [README](README.md#what-it-does) to Health Connect on your phone. It
   does not read anything from Health Connect. Once there, any other app you
   give Health Connect access to can read it, under that app's own privacy
   policy. You can revoke Odd Health's permissions, or delete the data it wrote,
   in Health Connect settings at any time. Deleting data in Odd Health does not
   delete what it already wrote to Health Connect.
2. **Debug logs.** Off by default. When you turn them on (Ring → Debug
   logging), the app saves the raw Bluetooth traffic with your ring and app
   events to daily files in its private storage. They contain your health data
   and your ring's Bluetooth address. They are only shared when you tap
   **Share logs** and pick where to send them; **Delete logs** removes them.
3. **Even export import.** The app reads a zip file you pick. It does not keep
   a copy of the file.

## The ring and the Even app

The app shares the ring's Bluetooth connection with the official Even app and
only *reads* from the ring. It never marks data as synced on the ring, never
changes ring settings or firmware, and never changes the ring's pairing with
your glasses. What the Even app does with your data is covered by Even
Realities' own privacy policy.

## Permissions

| Permission | Why |
|---|---|
| Nearby devices (Bluetooth connect) | To connect to your already-paired ring |
| Notifications | For the ongoing notification shown while connected to the ring |
| Run in the foreground (connected device) | To stay connected to the ring in the background |
| Run at startup | To reconnect after the phone restarts, if "Stay connected" is on |
| Health Connect: write heart rate, resting heart rate, HRV, oxygen saturation, steps, total and active calories, sleep, body and skin temperature | To write your data to Health Connect |

## Contact

Questions or concerns: open an issue on the project's GitHub repository.
