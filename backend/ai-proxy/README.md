# Ask AI proxy

The backend half of Ask AI. It exists for one reason: **the model key must never
be in the APK.** Anything shipped in the app is extractable, and a leaked model
key is spent money rather than a rate-limited lookup.

```
Android app  ──▶  aiQuery (this function)  ──▶  Claude API
                  holds ANTHROPIC_API_KEY
```

## Which questions get here

Most do not, and that is the design. `AiAssistantRepository.ask()` settles the
question on the device first — 9 intents over the call log, ~21 answers — and
only calls this function when `LocalIntentResolver` returns null. "How many calls
did I miss" costs nothing and works offline; "what should I say to my landlord"
comes here.

## Setup

```bash
cd backend/ai-proxy
npm install
firebase functions:secrets:set ANTHROPIC_API_KEY   # paste the key when prompted
npm run deploy
```

Then point the app at it — Firebase console → Remote Config → `ai_assistant`:

```json
{
  "ai_assistant_enabled": true,
  "ai_assistant_endpoint": "https://us-central1-<project>.cloudfunctions.net/aiQuery",
  "ai_assistant_free_queries": 5
}
```

The app reads those keys through `AiFeatureConfig`. While `ai_assistant_endpoint`
is blank the feature stays on-device and nothing is billed, so you can ship the
app before the backend exists — which is what it does today.

## Before you deploy

**Register Play Integrity for App Check** (Firebase console → App Check). The
function sets `enforceAppCheck: true`, so without it every request is rejected —
and if you turn that off instead, you have published an open endpoint that bills
you for whoever finds the URL.

## What it sends to the model

Not your users' phone numbers. `AiContextBuilder` reduces the log to counts, and
callers to a saved name or the last four digits of an unsaved number (`…7129`).
The system prompt tells the model it has never been given a full number.

This still means call-log *patterns* leave the device once the endpoint is set.
That changes what the app must declare in Play Data safety — it is currently
"no data collected" for this feature. Update that disclosure in the same release
that turns the endpoint on.

## Cost

`claude-opus-5` at $5/M input, $25/M output. A question plus the digest is
roughly 500 input tokens and ~100 output, so about **$0.005 per question** — and
only for questions the phone could not answer. `DAILY_REQUEST_CAP` in
`src/index.ts` is a hard ceiling on the whole project (2000/day ≈ $10/day) so a
loop or a leak cannot run up a bill while you sleep.

`output_config.effort` is `"low"` — right for a short factual read over a small
digest. If answers come back shallow, raise it to `"high"` and measure; effort
costs tokens.

## Per-user quota

There isn't one, and the client's counter is not it. `SettingsRepository.aiQueryCount`
explains the limit to the user; a SharedPreferences edit resets it. The daily cap
here is per *project*, not per person.

For a real allowance you need an identity to count against: add Firebase
anonymous auth to the app, send the ID token, verify it here, and key the
counter on the uid instead of the date.

## Contract

`POST` with an App Check token.

```jsonc
// request
{
  "question": "what should I say to the number that keeps calling?",
  "locale": "en-IN",
  "context": {
    "callsThisWeek": 123, "missedThisWeek": 16, "unsavedThisWeek": 8,
    "blockedTotal": 0,
    "topCaller": { "label": "Asha", "calls": 24 },
    "recent": [{ "label": "…7129", "type": "missed", "minutesAgo": 41, "durationSec": 0 }]
  }
}

// response
{ "answer": "…", "followUps": ["…", "…"] }
```

Any failure returns `{"answer": null}` with a 4xx/5xx. The app treats that as
"not reachable" and says so — it never renders a blank bubble, and it does not
charge the user's quota for an answer it did not get.

Model-proposed `followUps` carry no intent, so tapping one re-enters the same
path as typed text: classified on the device first, escalated only if that finds
nothing.
