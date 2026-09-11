import Anthropic from "@anthropic-ai/sdk";
import { zodOutputFormat } from "@anthropic-ai/sdk/helpers/zod";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getAppCheck } from "firebase-admin/app-check";
import { initializeApp } from "firebase-admin/app";
import { onRequest } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import { z } from "zod";

initializeApp();

/**
 * The model key. Held here and nowhere else - never in the APK, never in Remote
 * Config (which is publicly readable to anyone holding google-services.json).
 * Set it with:  firebase functions:secrets:set ANTHROPIC_API_KEY
 */
const ANTHROPIC_API_KEY = defineSecret("ANTHROPIC_API_KEY");

/** Matches AiQueryRequest / AiCallContext in the Android app. */
const CallDigest = z.object({
  label: z.string(),
  type: z.string(),
  minutesAgo: z.number(),
  durationSec: z.number(),
});

const QueryRequest = z.object({
  question: z.string().min(1).max(500),
  locale: z.string().max(35).default("en"),
  context: z.object({
    callsThisWeek: z.number(),
    missedThisWeek: z.number(),
    unsavedThisWeek: z.number(),
    blockedTotal: z.number(),
    topCaller: z.object({ label: z.string(), calls: z.number() }).nullable().optional(),
    recent: z.array(CallDigest).max(25),
  }),
});

/** Matches AiQueryResponse in the Android app. */
const AnswerSchema = z.object({
  answer: z.string(),
  followUps: z.array(z.string()),
});

const SYSTEM = `You answer questions about one person's own phone call history.

You are given a digest of their call log and nothing else. Rules:
- Answer only from the digest. Never invent a call, a name, a number or a time.
- If the digest cannot answer the question, say so plainly in one sentence. Do
  not guess, and do not pad the answer to sound helpful.
- Callers are identified by a saved name, or by the last four digits of an
  unsaved number written as "…1234". Refer to them exactly that way. You have
  never been given a full phone number and must never write one.
- Two sentences at most. This is read in a chat bubble on a phone.
- Answer in the language of the user's locale.
- Offer at most two short follow-up questions that this same digest could
  answer. If none fit, return an empty list.`;

/** A hard ceiling on the whole project, so a loop or a leak cannot run up a bill. */
const DAILY_REQUEST_CAP = 2000;

export const aiQuery = onRequest(
  {
    secrets: [ANTHROPIC_API_KEY],
    cors: false,
    region: "us-central1",
    // The model answers in seconds; this bounds a hung request.
    timeoutSeconds: 60,
    memory: "256MiB",
    maxInstances: 10,
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ answer: null });
      return;
    }

    // App Check, verified by hand: `enforceAppCheck` is an onCall option and
    // does nothing on onRequest. Without this the endpoint is an open, billable
    // API for whoever finds the URL.
    if (!(await isGenuineApp(req.header("X-Firebase-AppCheck")))) {
      res.status(401).json({ answer: null });
      return;
    }

    const parsed = QueryRequest.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ answer: null });
      return;
    }

    if (!(await withinDailyCap())) {
      res.status(429).json({ answer: null });
      return;
    }

    const client = new Anthropic({ apiKey: ANTHROPIC_API_KEY.value() });
    const { question, locale, context } = parsed.data;

    try {
      const response = await client.messages.parse({
        model: "claude-opus-5",
        // Deliberately small: the answer is two sentences in a chat bubble, and
        // a cap this tight is also a cost ceiling per question.
        max_tokens: 1024,
        // A short factual read over a small digest. Raise to "high" if answers
        // come back shallow; measure before you do - it costs tokens.
        output_config: {
          effort: "low",
          format: zodOutputFormat(AnswerSchema),
        },
        system: SYSTEM,
        messages: [
          {
            role: "user",
            content: [
              `Locale: ${locale}`,
              `Call log digest: ${JSON.stringify(context)}`,
              `Question: ${question}`,
            ].join("\n\n"),
          },
        ],
      });

      // parsed_output is null when the model's output did not satisfy the
      // schema. The app treats a null answer as "not reachable" and says so,
      // which is the right outcome - better than a blank bubble.
      const answer = response.parsed_output;
      if (!answer) {
        res.status(502).json({ answer: null });
        return;
      }

      res.status(200).json({
        answer: answer.answer,
        followUps: answer.followUps.slice(0, 2),
      });
    } catch (error) {
      // Most specific first. Every branch returns the same shape: the app has
      // one failure mode on screen and does not need to distinguish these.
      if (error instanceof Anthropic.AuthenticationError) {
        console.error("ANTHROPIC_API_KEY is missing or invalid");
      } else if (error instanceof Anthropic.RateLimitError) {
        console.warn("Rate limited by the Claude API");
      } else if (error instanceof Anthropic.APIError) {
        console.error(`Claude API error ${error.status}: ${error.message}`);
      } else {
        console.error("Unexpected proxy failure", error);
      }
      res.status(502).json({ answer: null });
    }
  },
);

/**
 * True when the caller presented a valid App Check token — that is, when it is
 * a real install of the app rather than curl.
 *
 * Requires Play Integrity to be registered for the Android app in the Firebase
 * console. A missing or forged token is refused: this is the only thing standing
 * between the endpoint and anyone who reads the URL out of the APK.
 */
async function isGenuineApp(token: string | undefined): Promise<boolean> {
  if (!token) return false;
  try {
    await getAppCheck().verifyToken(token);
    return true;
  } catch {
    return false;
  }
}

/**
 * Counts requests per UTC day and refuses past [DAILY_REQUEST_CAP].
 *
 * This is a spend ceiling for the project, not a per-user quota. A real
 * per-user allowance needs an identity to key on - add Firebase anonymous auth
 * to the app, verify the ID token here, and count against the uid. The client's
 * own counter in SettingsRepository explains the limit to the user; it does not
 * enforce it, because a SharedPreferences edit resets it.
 */
async function withinDailyCap(): Promise<boolean> {
  const day = new Date().toISOString().slice(0, 10);
  const ref = getFirestore().collection("ai_usage").doc(day);

  try {
    const snapshot = await ref.get();
    if ((snapshot.data()?.count ?? 0) >= DAILY_REQUEST_CAP) return false;
    await ref.set({ count: FieldValue.increment(1) }, { merge: true });
    return true;
  } catch (error) {
    // Firestore being unavailable must not take the feature down with it, but
    // it does mean this request is uncounted - noted rather than silent.
    console.warn("Daily cap check failed; allowing the request", error);
    return true;
  }
}
