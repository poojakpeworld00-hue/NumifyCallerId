# Remote Config — Ask AI

Four keys. They go inside the `GET_DATA_LIST` blob (and `DEBUG_GET_DATA_LIST`),
alongside `IsAdsON` and the rest — **in both the `marketing` and `organic`
objects**, because `AdAwareActivity.audienceRoot()` resolves one audience and
ingests only that one. A key present in `organic` but missing from `marketing`
simply does not exist for paid installs.

Every key is optional. `ingestConfig` guards each with `root.has(key)`, so an
older config that omits them leaves the compiled defaults untouched — it never
force-disables the assistant.

| Key | Type | Default | What it does |
|---|---|---|---|
| `ai_assistant_enabled` | boolean | `false` release, `true` debug | Master switch. Off means no Ask AI button, no caller verdict, no post-call summary, no missed-call reply. |
| `ai_assistant_home_tooltip` | boolean | `true` | Whether the first-run tooltip appears under the Home button. Shows once for 3s per install regardless. |
| `ai_assistant_endpoint` | string | `""` | Base URL of the AI proxy. **Blank keeps every answer on-device.** |
| `ai_assistant_free_queries` | int | `5` | Questions that reach the model before the paywall. Inert today — there is no billing. |

## Paste this into both audiences

```json
"_group_AskAI": "----- Ask AI assistant -----",
"ai_assistant_enabled": true,
"ai_assistant_home_tooltip": true,
"ai_assistant_endpoint": "",
"ai_assistant_free_queries": 5
```

## Turning it on

`ai_assistant_enabled: true` is all that is needed. With `ai_assistant_endpoint`
still blank the assistant answers spam verdicts, top-caller, missed-call and
blocklist questions entirely on-device — free, offline, and with no number
leaving the phone. Anything broader replies that the service is not switched on.

Set `ai_assistant_endpoint` only once the `/ai/query` proxy exists. The model key
must live on that server: a key shipped in the APK is extractable, and the app's
own `CredentialProvider` already says as much about a far less valuable secret.

## What a user can still turn off

Remote Config gates the feature; the user gates each surface. Both must agree,
and the four user switches live in **Ask AI settings** (the gear on the Ask AI
screen, or Settings → Ask AI), backed by `SettingsRepository`:

| Preference | Controls |
|---|---|
| `aiHomeButtonEnabled` | the Home entry point |
| `aiCallerVerdictEnabled` | the verdict line on the incoming-call card |
| `aiCallSummaryEnabled` | the post-call summary card |
| `aiSmartReplyEnabled` | the missed-call notification |

All default to on, so enabling the feature remotely does not land users on a
screen where everything is already switched off.

## Verifying it arrived

Debug builds log the ingest. `ai_assistant_enabled` reaching the store is the
thing to confirm — if it is missing from the config, the compiled default
(`BuildConfig.DEBUG`) applies, which is why the feature appears to work in debug
and stays invisible in release.

```
adb logcat -s AdAwareActivity | grep ingested
```

## Every key the app ingests

66 in total, plus the `screen_order` and `custom_ads` arrays and the Facebook
init pair. Anything not on this list is ignored — `ingestConfig` works from an
explicit allow-list, so a new key needs a code change as well as a config change.

**Boolean (21)** — `IsAdsON`, `IsFail_FB`, `isLoaderForFB`, `IsCustomADS`,
`IsBack`, `NativeBannerPresenter`, `BannerAdPresenter`, `In_App_Update_Show`,
`In_App_Update_Force_Show`, `Iscountry_Counter`, `HD_VBC_Show`, `HD_VBC_Native`,
`is_preload_ads`, `InterAds`, `AppopenAds`, `NativeAd`, `is_rateus`,
`screen_wise_ad`, `screen_wise_default`, `ai_assistant_enabled`,
`ai_assistant_home_tooltip`

**String / nested JSON (32)** — `IsAdType`, `In_App_Update_Link`,
`CountryList_Counter_NShow`, `PrivacyPolicy`, `TermLink`, `DirectLink`,
`MarketLink`, `HD_VBC_Native_ID`, `HD_VBC_Banner_ID`, `googleS_Inter`,
`googleBackInter`, `googleInter`, `googleAppopen`, `googleNative`,
`googleBanner`, `googleRewarded`, `faceB_InterAds`, `faceB_NativeAds`,
`faceB_NativeBannerAds`, `faceB_BannerAds`, `NativeTheme`, `HD_VBC_Type`,
`NativeBgColor`, `NativebtnColor`, `NativetxtColor`, `NativebtntxtColor`,
`screen`, `exit`, `ScreenAds`, `api_config`, `rate_us`, `ai_assistant_endpoint`

**Int (13)** — `InterCounter`, `InterBackCounter`, `MarketInterCounter`,
`MarketBackCounter`, `NativeCounter`, `MarketNativeCounter`, `MidNativeCounter`,
`BannerCounter`, `MarketBannerCounter`, `MarketAppopenCounter`,
`AppopenCounter`, `HD_VBC_Hrs`, `ai_assistant_free_queries`

**Arrays / other** — `screen_order`, `custom_ads`, `FbAppId`, `FbClientToken`
