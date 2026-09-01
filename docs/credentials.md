# Credentials

Every secret this app needs, where it lives, and how it gets into the binary.

## Setup for a fresh clone

`local.properties` is gitignored, so a new checkout has none of these. Add:

```properties
# LightHouse push SDK
lighthouse.apiKey=<ask the team>
lighthouse.baseUrl=<ask the team>

# callerid.kpeworld.com lookup API
lookup.apiId=<ask the team>
lookup.apiHash=<ask the team>
lookup.apiToken=Bearer <jwt>
```

Release signing adds four more — see [release-signing.md](release-signing.md).

A missing value does **not** fail the build; it compiles to an empty string and
fails at runtime with a 401 that looks like a server fault. The build warns for
each blank key instead:

```
WARNING: lookup.apiToken is missing from local.properties — the feature it powers will fail at runtime.
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
| `lookup.apiId` / `apiHash` / `apiToken` | `local.properties` | No | Obfuscated |
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

### Known exposure: the lookup JWT

`lookup.apiToken` decodes to `{"user_id": 1433, "iat": ...}` with **no `exp`
claim** — it never expires. It was previously plaintext in `CredentialProvider.kt` and is
therefore in git history and in every APK already released. Moving it to
`local.properties` stops *future* leakage but does not un-leak it.

The durable fix is server side:

1. Issue a replacement token **with an `exp` claim** and revoke `user_id: 1433`'s
   current one.
2. Rate-limit per account so a lifted token has limited value.
3. Only then is scrubbing git history worth considering — and it is not
   sufficient on its own, since released APKs still carry the old value.
