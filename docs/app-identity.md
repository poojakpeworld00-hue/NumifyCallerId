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
| `applicationId` | `com.contacts.callerid.number.lookup` |
| Kotlin `namespace` | `com.numify.callerid.lookup` |

**These two deliberately differ.** The applicationId is the app's identity on
Play; the namespace is where the source lives, is invisible to users, and
renaming ~400 files to match it would be churn with no product effect. Nothing
outside `build.gradle.kts` and `google-services.json` referenced the old
applicationId, so the change is those two lines.

## Before this can ship — not done in the repo

1. **Firebase.** `app/google-services.json` has had its `package_name` edited to
   the new applicationId so the project builds, but `mobilesdk_app_id` and the
   API key are still those of the app registered under `com.numify.callerid`.
   Register `com.contacts.callerid.number.lookup` as a new Android app in the
   `numify-caller-id-lookup` Firebase project and replace this file with the one
   the console hands back. Until then Analytics, Remote Config and Crashlytics
   all report against the old app.
2. **AdMob.** The manifest's `com.google.android.gms.ads.APPLICATION_ID` is
   Google's public test id, so nothing breaks — but the real AdMob app is tied
   to a package name, so a new one has to be created for the new applicationId
   and its id put here along with the live ad unit ids.
3. **Play Console.** An applicationId cannot be changed once an app is
   published. If `com.numify.callerid` was ever uploaded, this is a new listing,
   not a rename.
