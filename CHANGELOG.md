# Changelog

Versions follow `MAJOR.MINOR.PATCH`. Releases are tagged `vX.Y.Z`; see
[docs/release.md](docs/release.md).

## Unreleased

## 0.14.0

First release-ready build.

- Reads the Even Realities R1 ring alongside the Even app, read-only, and
  stores heart rate, HRV, blood oxygen, steps, calories, sleep and nightly
  temperature on the phone.
- Writes to Health Connect: heart rate (hourly average and live readings),
  resting heart rate (estimated per night), HRV, oxygen saturation, steps,
  total and active calories, sleep sessions with stages, body temperature
  (finger) and skin temperature change from a 30-night baseline.
- Today, per-metric detail by day with per-value sync status, Ring and Sync tabs,
  pull to refresh, optional frequent heart-rate polling.
- Import of Even health exports for older history.
- Optional debug log (off by default) with share and delete.
- About and privacy screen, and a first-launch notice.
- Adaptive launcher icon with a themed layer.
- Release builds: R8 shrinking, signing from local properties or CI secrets,
  version from the git tag.
