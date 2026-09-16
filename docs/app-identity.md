# App identity

The names and ids this app ships under, and what has to happen outside the
repo before a build carrying them can go to Play.

## Names

| Where | Value |
| --- | --- |
| Play Store listing title | `Contacts: Dialer & Caller ID` |
| Launcher / in-app name (`app_name`) | `Contacts` |
| Application label (`app_label`) | `Contacts`, with two leading non-breaking spaces so the app sorts to the top of system lists such as "Display over other apps" |
| Hashtag (`app_name1`) | `#Contacts` |

The store listing title lives only in Play Console — it is not a resource and
nothing in the APK reads it. It is written down here so the two names cannot
drift apart unnoticed.

`app_name` is the launcher activity's label and is what the home screen shows.
It is a proper noun, so every locale carries the same value; the localised
taglines the old name had ("Numify: Phone Lookup" and its translations) are
gone, because the name is now one word in every language.

## Ids

| Where | Value |
| --- | --- |
| `applicationId` | `com.callerid.numberlookup.home` |
| Kotlin `namespace` | `com.callerid.numberlookup.home` |

The two match, and the source tree matches both: everything lives under
`com/callerid/numberlookup/home`. The old `com.numify.callerid` root had
two children, `.lookup` and `.monetize`; `.lookup` became the new root (so
`…callerid.lookup.feature` is now `…number.lookup.feature`, not a second
`lookup` segment deeper) and `.monetize` moved in beside the feature packages
as `…number.lookup.monetize`. The `Numify` name survives nowhere in code except
`NumifyApplication`, which became `ContactsApplication`, and in comments citing
design handoffs by their real filenames ("design: Numify Splash v2") — those are
artefact names, not branding.

## Before this can ship — not done in the repo

1. ~~**Firebase.**~~ Done: `app/google-services.json` is the real file for
   `com.callerid.numberlookup.home` in its own project,
   `contacts-dialer-caller-id` (project number 885272339028). The old
   `numify-caller-id-lookup` project is no longer referenced by this app —
   **the Remote Config template lives there, not here**, so every parameter the
   app reads (`GET_DATA_LIST`, `ai_assistant`, `api_config`, the overlay and
   blocklist flags — see the other files in this folder) has to be recreated in
   the new project before a build behaves as it did.
2. **AdMob.** The manifest's `com.google.android.gms.ads.APPLICATION_ID` is
   Google's public test id, so nothing breaks — but the real AdMob app is tied
   to a package name, so a new one has to be created for the new applicationId
   and its id put here along with the live ad unit ids.
3. **Play Console.** An applicationId cannot be changed once an app is
   published. If `com.callerid.numberlookup.home` was ever uploaded, this is a new listing,
   not a rename.
