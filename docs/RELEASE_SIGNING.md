# Release Signing

Signed release builds are produced by the **Android Release (signed)** workflow
(`.github/workflows/android-release.yml`), using a keystore you generate and store as
repository secrets. The key never enters this repository.

## Why the release key is separate from the debug key

The debug key (`ci-debug.keystore`, see [STABLE_SIGNING.md](STABLE_SIGNING.md)) exists so PR
APKs install as updates. It must **not** be reused for release:

- Its password falls back to a literal string in `app/build.gradle.kts`.
- It is decoded on every PR build, including on runners handling untrusted PR branches.
- A Play Store upload key cannot be swapped freely once you have published under it — recovery
  requires Google's key-reset process.

Release signing is therefore a distinct key, distinct secrets, and a distinct workflow that
never runs on pull requests.

## One-time setup

### 1. Generate the release keystore (locally, in a private terminal)

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore release.keystore \
  -alias compressor-release -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Compressor,O=Zfkirke0109,C=US"
```

Choose a strong password when prompted. **Back this file up somewhere durable and private.**
If you lose it after publishing, you lose the ability to ship updates under the same identity.

`release.keystore`, `*.jks`, `*.p12`, and `*.keystore` are all in `.gitignore`.

### 2. Get the certificate fingerprint

```bash
keytool -exportcert -keystore release.keystore -alias compressor-release \
  | sha256sum | awk '{print toupper($1)}'
```

### 3. Encode the keystore

```bash
base64 -w 0 release.keystore
```

### 4. Add the repository secrets

Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | output of step 3 |
| `RELEASE_KEYSTORE_PASSWORD` | the store password from step 1 |
| `RELEASE_KEY_ALIAS` | `compressor-release` |
| `RELEASE_KEY_PASSWORD` | the key password (same as the store password unless you set a different one) |
| `RELEASE_SIGNER_SHA256` | fingerprint from step 2 |

## Building

- **On every pull request:** a signed release APK is built and uploaded as an artifact, so you
  can install the actual release-signed build straight from the PR. The `.aab` is skipped here
  — it is only needed for Play upload and would just add build time.
- **Manually:** Actions → *Android Release (signed)* → Run workflow. The `build_aab` input
  controls whether a Play-ready `.aab` is produced alongside the APK.
- **On a version tag:** pushing a `v*` tag builds both automatically.

Artifacts: `compressor-release-apk` and `compressor-release-aab`.

Combined with the debug signing in `android-ci.yml` and `android.yml`, every APK this repo
produces from Actions is signed — debug builds with the stable debug key, release builds with
the release key.

### Before the secrets exist

Until the five `RELEASE_*` secrets are configured, pull-request runs **skip** the signed
release build and log a notice naming the missing secrets. They do not fail — configuring
release signing should not be a prerequisite for CI being green.

A tag push or a manual run behaves the opposite way and **fails** on missing secrets: a real
release must never be silently unsigned.

### Fork and collaborator note

The job does not run for pull requests opened from forks, because GitHub does not expose
secrets to them. For same-repo pull requests the workflow file used is the one **on the PR
branch**, so anyone who can push a branch can modify this workflow and read the release key.
That is fine for a single-maintainer repository; revisit it before adding collaborators.

## What the workflow guarantees

1. **Fails fast on a missing secret**, naming which one.
2. **Verifies the keystore fingerprint before building** — signing with the wrong key is
   effectively unrecoverable once published, so this is checked up front rather than discovered
   later.
3. **Verifies the finished APK is actually signed by the expected key**, via `apksigner`. This
   matters because Gradle deliberately falls back to an *unsigned* release when credentials are
   absent (so local and fork builds still work) — without this check, a misconfiguration would
   produce a silently unsigned artifact that looks successful.
4. **Deletes the keystore from the runner** in an `if: always()` step.
5. **Never runs on forks** (`github.repository` guard), where secrets are unavailable anyway.

## Local behavior

`./gradlew assembleRelease` with no keystore or credentials produces an **unsigned** release
APK — unchanged from before. Signing engages only when the keystore file *and* all three
credentials are present; a partial configuration falls back to unsigned rather than failing
late inside AGP.

To sign locally, place `release.keystore` at the repository root and set:

```bash
export COMPRESSOR_RELEASE_KEYSTORE_PASSWORD=...
export COMPRESSOR_RELEASE_KEY_ALIAS=compressor-release
export COMPRESSOR_RELEASE_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

Confirm which config a variant will use with `./gradlew :app:signingReport`.
