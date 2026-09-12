# Credentials

Every secret this app needs, where it lives, and how it gets into the binary.

## Setup for a fresh clone

`local.properties` is gitignored, so a new checkout has none of these. Add:

```properties
# LightHouse push SDK
lighthouse.apiKey=<ask the team>
lighthouse.baseUrl=<ask the team>

# contact-saver.dailymorningupdate.com — number lookup + contact upload
contactsaver.apiKey=<ask the team>
```

Release signing adds four more — see [release-signing.md](release-signing.md).

A missing value does **not** fail the build; it compiles to an empty string and
fails at runtime with a 401 that looks like a server fault. The build warns for
each blank key instead:

```
WARNING: contactsaver.apiKey is missing from local.properties — the feature it powers will fail at runtime.
```

## How the obfuscation works

`local.properties` → `xorByteArrayLiteral()` in `app/build.gradle.kts` →
`BuildConfig` `byte[]` → `SecretDecoder.decode()` at runtime.

The `byte[]` step matters. A `static final String` is **inlined by the compiler
at every call site**, so it reappears verbatim in a decompiled APK no matter how
the field is declared. A `static final byte[]` is not inlined. That is also why
`CredentialProvider` uses `val ... by lazy` rather than `const val` — `const` would
reintroduce the inlining.

**This raises the bar; it does not make the secret secret.** Anything shipped in
an APK is recoverable by a determined reader. Treat it as friction against
casual extraction, not as protection.

## Inventory

| Credential | Stored in | In git? | State |
|---|---|---|---|
| Firebase API key | `app/google-services.json` | Yes | Fine — Android keys are public by design; restrict by package + SHA-1 in Cloud Console |
| `lighthouse.apiKey` / `baseUrl` | `local.properties` | No | Obfuscated |
| `contactsaver.apiKey` | `local.properties` | No | Obfuscated; sent as `x-api-key`, HTTPS only, API host only |
| `conduit.user` / `conduit.password` | `gradle.properties` | **Yes** | **Exposed — see below** |
| AdMob app id | `AndroidManifest.xml` | Yes | Google test id — must be replaced |
| AdMob ad-unit ids (7) | Remote Config | n/a | All test ids |
| `FbAppId` / `FbClientToken` / 4 placements | Remote Config | n/a | Placeholder junk |

### Known exposure: `conduit.password`

`gradle.properties` is committed and contains the LightHouse Maven repo consumer
credentials in plaintext. Unlike the runtime secrets above, this one is a
**build-time** credential — it never ships inside the APK, so the blast radius is
read access to the artifact repo rather than anything user-facing. It should
still move to `~/.gradle/gradle.properties` (per-developer, outside the repo) or
a CI secret. Left in place for now because changing it breaks every developer's
build until they each add it locally — coordinate before moving it.

### Retired: the callerid.kpeworld.com JWT

The app no longer talks to `callerid.kpeworld.com`. `lookup.apiId`,
`lookup.apiHash` and `lookup.apiToken` are read by nothing and can be deleted
from `local.properties`.

That does not close the old exposure. `lookup.apiToken` decoded to
`{"user_id": 1433, "iat": ...}` with **no `exp` claim** — it never expires — and
it was plaintext in `CredentialProvider.kt` for a time, so it is in git history
and in every APK already released. Dropping the code that uses it stops future
leakage and nothing more. **Revoke `user_id: 1433`'s token server side**; until
that happens the credential is live for anyone who has it.

### The contact-saver API key

`contactsaver.apiKey` ships inside the APK, obfuscated. That is friction, not
secrecy — assume a determined reader can recover it. What limits the damage:

- It travels as a header, so it stays out of server and proxy access logs, unlike
  the `?hash_key=` query parameter the old API used.
- `ApiKeyInterceptor` sends it only over HTTPS and only to the host in
  `contacts_base_url`. The base URL is steerable from Remote Config and Remote
  Config is publicly readable, so without that check anyone able to influence it
  could have the key delivered to a host of their choosing.
- It is redacted from both Chucker and the OkHttp logger, so a debug screenshot
  or an `adb logcat` does not spill it.

Server side, the things that actually matter: rate-limit per key, and be able to
revoke and reissue one. Rotating it today needs a new release — deliberately, as
putting it in Remote Config would make it *easier* to lift, not harder.

Worth considering if this endpoint ever carries more than it does today:
certificate pinning on `contact-saver.dailymorningupdate.com`, which would defeat
an interception proxy. It is not in place, because a pin that outlives its
certificate breaks every installed copy of the app until they update.
