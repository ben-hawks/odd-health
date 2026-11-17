# Releasing

Releases are built by GitHub Actions when a GitHub release is published, signed
with the project's release key, and attached to that release as
`odd-health-X.Y.Z.apk` plus a `.sha256` checksum.

## One-time setup

### 1. Create the release key

Do this once, on your own computer, and keep the result safe:

```sh
keytool -genkeypair -v \
  -keystore odd-health-release.jks \
  -alias odd-health \
  -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` asks for a keystore password, then your name/organisation (any
values), then a key password (pressing Enter reuses the keystore password).

**Back up `odd-health-release.jks` and both passwords** somewhere safe, such as a
password manager. Every future release must be signed with this exact key. If
it is lost, existing users can't update: they would have to uninstall (losing
the app's stored data) and install again.

Never commit the keystore. `.gitignore` excludes `*.jks` and
`android/keystore.properties`.

### 2. Add the GitHub secrets

In the repository: **Settings → Secrets and variables → Actions → New
repository secret**. Add four:

| Secret | Value |
|---|---|
| `ODD_KEYSTORE_BASE64` | The keystore file, base64-encoded (see below) |
| `ODD_KEYSTORE_PASSWORD` | The keystore password |
| `ODD_KEY_ALIAS` | `odd-health` (or the alias you chose) |
| `ODD_KEY_PASSWORD` | The key password (same as the keystore password if you pressed Enter) |

To get the base64 text:

```sh
base64 -w0 odd-health-release.jks > keystore.b64   # Linux
base64 -i odd-health-release.jks -o keystore.b64   # macOS
```

Paste the contents of `keystore.b64` as the secret value, then delete
`keystore.b64`.

### 3. Add the workflow

Copy [`docs/ci/release.yml`](ci/release.yml) to `.github/workflows/release.yml`
and commit it. (It lives under `docs/` because the tool that prepared this repo
can't create workflow files.) It needs no other configuration: it uses the
built-in `GITHUB_TOKEN` to upload to the release.

## Making a release

1. Update [CHANGELOG.md](../CHANGELOG.md): move the "Unreleased" notes under the
   new version.
2. On GitHub: **Releases → Draft a new release**.
3. **Choose a tag**: type a new tag `vMAJOR.MINOR.PATCH` (for example `v0.14.0`)
   and target `main`.
4. Title it, paste the changelog notes, and **Publish release**.
5. The **Release** workflow runs (Actions tab, about 10 minutes): it runs the
   tests, builds and signs the APK, and attaches it to the release.

The version comes from the tag: `v1.2.3` gives versionName `1.2.3` and
versionCode `10203` (major × 10000 + minor × 100 + patch). Android only
installs updates with a higher versionCode, so tags must keep increasing.
Minor and patch numbers must stay below 100.

If the workflow fails, fix the cause, then re-run it from the Actions tab; the
upload replaces any earlier files on that release.

## Building a release locally

Create `android/keystore.properties` (git-ignored):

```properties
storeFile=/absolute/path/to/odd-health-release.jks
storePassword=...
keyAlias=odd-health
keyPassword=...
```

or set `ODD_KEYSTORE`, `ODD_KEYSTORE_PASSWORD`, `ODD_KEY_ALIAS` and
`ODD_KEY_PASSWORD` in the environment. Then:

```sh
cd android
./gradlew :app:assembleRelease -PversionName=1.2.3
# -> app/build/outputs/apk/release/app-release.apk
```

Without signing settings the build still succeeds but produces
`app-release-unsigned.apk`, which Android won't install.

## What a release build does differently

- **R8 shrinking and optimisation** (`isMinifyEnabled`, `isShrinkResources`):
  the APK is about 3 MB instead of about 70 MB. Rules are in
  `android/app/proguard-rules.pro`; the libraries used ship their own.
- **Signed with the release key**, not the committed debug key. A release
  can't be installed over a debug build or the other way round; switching
  needs an uninstall.

## Before publishing: checklist

- [ ] All tests pass: `python3 -m unittest discover -s tests` and
      `cd android && ./gradlew :protocol:test :core:test :app:testDebugUnitTest`.
- [ ] A release build installed on a real phone: the ring connects, a pull to
      refresh brings data in, and Health Connect receives it. R8 problems only
      show up at runtime.
- [ ] If the Room schema changed (`android/app/schemas/`), a migration was
      added and the database version bumped. The app currently has no
      migrations; without one, an update that changes the schema crashes on
      start.
- [ ] If what a record type is written as in Health Connect changed, its
      revision in `HealthConnectMapping.revision` was bumped so stored records
      are sent again.
- [ ] If new data, permissions or destinations were added:
      [PRIVACY.md](../PRIVACY.md), [DISCLAIMER.md](../DISCLAIMER.md), the
      About screen text (`about_*` strings) and the README table are updated.
- [ ] CHANGELOG.md updated.

## Health Connect outside the Play Store

Health Connect shows apps installed from outside the Play Store normally, but
on some phones its permission screen doesn't open from the app; users then
grant access in Health Connect settings (the user guide explains this).
Publishing on the Play Store would additionally require a hosted privacy
policy and Google's health-apps declaration; [PRIVACY.md](../PRIVACY.md) is
written to serve as that policy.
