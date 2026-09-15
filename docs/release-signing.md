# Release Signing

Every APK/AAB uploaded to Google Play must be signed with the **same** key for
the life of the app. This document covers generating that key, wiring it into
the build, and the CI path.

> **The keystore is unrecoverable.** If you lose `numify-release.jks` or its
> passwords, you can never publish an update to `com.contacts.callerid.number.lookup`
> again — you'd have to ship a new listing under a new package name and lose every
> install and review. Back it up somewhere off this machine before you ship.
> (Play App Signing, §5, softens this — enrol.)

---

## 1. Generate the keystore

Run this **once**, from the repo root. It prompts for the passwords and for your
organisation details — nothing is passed on the command line, so nothing lands in
your shell history.

```bash
keytool -genkeypair -v -keystore numify-release.jks -alias numify -keyalg RSA -keysize 4096 -validity 10000
```

Notes on the flags:

| Flag | Why |
|---|---|
| `-keysize 4096` | Play's minimum is 2048; 4096 costs nothing here. |
| `-validity 10000` | ~27 years. Play **requires** a key valid past 2033-10-22, and a key that expires ends your ability to update. |
| `-alias numify` | Must match `release.keyAlias` below. |

When prompted for "first and last name" (CN) etc., any accurate value is fine —
Play does not surface it. Use a **different** password for the store and the key,
or the same; just record which is which.

`*.jks` is gitignored, so the file stays out of version control. Keep it at the
repo root or anywhere else — the path is configurable.

---

## 2. Point the build at it

Add four lines to `local.properties` (already gitignored, and already where the
LightHouse credentials live):

```properties
release.storeFile=numify-release.jks
release.storePassword=<the store password you just chose>
release.keyAlias=numify
release.keyPassword=<the key password you just chose>
```

`release.storeFile` may be absolute, or relative to the **repo root**.

---

## 3. Build

```bash
./gradlew :app:bundleRelease
```

The AAB lands in `app/build/outputs/bundle/release/` — that's the format Play
wants. For a signed APK to sideload or hand to a tester, use
`./gradlew :app:assembleRelease` (`app/build/outputs/apk/release/`).

Both are named via the `base { archivesName }` block, e.g.
`Numify_com.contacts.callerid.number.lookup_v1.0.0(1)_Aug.12.2026`.

### Verify the signature

`apksigner` is a Java program, so `JAVA_HOME` must be set (it fails with
"Unable to locate a Java Runtime" otherwise):

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ~/Library/Android/sdk/build-tools/37.0.0/apksigner verify --print-certs --verbose app/build/outputs/apk/release/*.apk
```

Expect:

```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
```

**v1 being `false` is correct here.** JAR signing is only needed below API 24,
and `minSdk` is 24 — the exact level v2 signing shipped in — so `enableV1Signing`
is off. Record the `certificate SHA-256 digest` line; that's what you compare
against on later uploads to prove it's the same key.

---

## 4. How the wiring behaves

In `app/build.gradle.kts`:

- Credentials resolve from `local.properties` **first**, then environment
  variables (`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD`) for CI. There are no defaults and no fallback values —
  a missing secret is never silently substituted.
- The `release` signing config is only **created** when all four are present and
  the keystore file actually exists. Otherwise `signingConfigs.findByName("release")`
  returns null and the release build is unsigned — so a fresh clone with no
  keystore still builds instead of failing at configuration time.
- That unsigned case logs a `WARNING: release signing is not configured (...)`
  naming the first missing piece.

### CI

Store the keystore as a base64 secret and decode it in the job:

```bash
echo "$RELEASE_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/numify-release.jks"
export RELEASE_STORE_FILE="$RUNNER_TEMP/numify-release.jks"
```

Set the other three as masked environment variables. Do **not** write them into
`local.properties` in CI — env vars keep them out of the workspace.

---

## 5. Play App Signing (recommended)

With Play App Signing, Google holds the *app signing key* and you keep an *upload
key* (the one above). If the upload key is ever lost or compromised you can request
a reset — without it, loss is terminal. Enrol at first upload:
**Play Console → Release → Setup → App signing**.

This does not change anything in this document; you still sign every upload with
the key generated in §1.
