import Anthropic from "@anthropic-ai/sdk";
import { zodOutputFormat } from "@anthropic-ai/sdk/helpers/zod";
import { z } from "zod";

export interface Env {
  ANTHROPIC_API_KEY: string;
  MODEL: string;
  DAILY_LIMIT: string;
  RATE_LIMIT?: KVNamespace;
}

/** What the app receives. Mirrors `ParsedCommand` in android/.../command/IntentClient.kt. */
const ParsedCommand = z.object({
  action: z.enum(["book_ride", "order_food", "remember", "unsupported"]),
  destination: z.string().nullable(),
  restaurant: z.string().nullable(),
  items: z.array(z.string()),
  fact: z.string().nullable(),
  message: z.string(),
});
type ParsedCommand = z.infer<typeof ParsedCommand>;

/** On-phone memory summary sent with each command. Mirrors `MemorySummary` in android/.../memory/MemoryStore.kt. */
const MemorySummary = z.object({
  facts: z.array(z.string().max(200)).max(50),
  frequentDestinations: z.array(z.string().max(300)).max(10),
  restaurants: z
    .array(z.object({ name: z.string().max(200), timesOrdered: z.number().int(), lastItems: z.array(z.string().max(200)).max(20) }))
    .max(10),
});
type MemorySummary = z.infer<typeof MemorySummary>;

const IntentRequest = z.object({
  deviceId: z.string().uuid(),
  text: z.string().min(1).max(500),
  places: z.object({ home: z.string().max(300), work: z.string().max(300) }),
  memory: MemorySummary.default({ facts: [], frequentDestinations: [], restaurants: [] }),
});

// Fixed instructions only; per-request data (places, memory, command) goes in the user message.
const SYSTEM_PROMPT = `You turn a short phone command into an action for Vecto, an assistant that operates apps on the user's Android phone in London, UK.

Supported actions:
- book_ride: open Uber with a drop-off. Set "destination" to a full address or place name Uber can search (e.g. "King's Cross Station, London"). If the user says "home" or "work", use their saved address; if that saved address is empty, return unsupported and ask them to add it in the Vecto app.
- order_food: order on Uber Eats. Set "restaurant" to the restaurant name only, and "items" to dishes they named (empty list if none).
- remember: the user wants Vecto to remember something about them ("remember I'm vegetarian", "I always take UberX"). Set "fact" to a short, self-contained statement, e.g. "Is vegetarian".
- unsupported: anything else, or too vague to act on.

You also get the user's memory, which is stored on their phone: facts they told Vecto, places they often go, and restaurants they order from with their last order. Use it to resolve references: "the usual from Dishoom" means their last items at Dishoom; "cab to the gym" may match a frequent destination; "my usual Indian place" may match a restaurant. Only use memory when the command refers to it; never add items or places the user didn't ask for.

Always set "message" to one short, friendly line for the user, e.g. "Getting you an Uber to King's Cross", "Got it, I'll remember that" or "I can only book rides and order food for now". Unused fields are null (or an empty list for items). Never invent a destination or restaurant that isn't in the command or the memory.`;

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/v1/intent") {
      return json({ error: "not_found" }, 404);
    }

    const parsed = IntentRequest.safeParse(await request.json().catch(() => null));
    if (!parsed.success) return json({ error: "bad_request" }, 400);
    const { deviceId, text, places, memory } = parsed.data;

    // TODO before public launch: verify a Play Integrity token here so only the real app can call us.

    if (!(await allowRequest(env, deviceId))) return json({ error: "rate_limited" }, 429);

    // No key yet (local dev): simple keyword parser so the app can be tested for free.
    if (!env.ANTHROPIC_API_KEY) return json(mockParse(text, places, memory));

    const client = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY });
    try {
      const response = await client.messages.parse({
        model: env.MODEL,
        max_tokens: 2048,
        system: SYSTEM_PROMPT,
        output_config: { effort: "low", format: zodOutputFormat(ParsedCommand) },
        messages: [
          {
            role: "user",
            content: `Saved home: ${places.home || "(not set)"}\nSaved work: ${places.work || "(not set)"}\n\n<memory>\n${formatMemory(memory)}\n</memory>\n\nCommand: ${text}`,
          },
        ],
      });

      if (response.stop_reason === "refusal" || !response.parsed_output) {
        return json({
          action: "unsupported",
          destination: null,
          restaurant: null,
          items: [],
          fact: null,
          message: "Sorry, I can't help with that one.",
        } satisfies ParsedCommand);
      }
      return json(response.parsed_output);
    } catch (error) {
      if (error instanceof Anthropic.RateLimitError) return json({ error: "busy" }, 503);
      if (error instanceof Anthropic.APIError) {
        console.error(`Claude API error ${error.status}: ${error.message}`);
        return json({ error: "upstream" }, 502);
      }
      throw error;
    }
  },
} satisfies ExportedHandler<Env>;

function formatMemory(memory: MemorySummary): string {
  const lines = [
    `Facts: ${memory.facts.length ? memory.facts.join("; ") : "(none)"}`,
    `Frequent destinations: ${memory.frequentDestinations.length ? memory.frequentDestinations.join("; ") : "(none)"}`,
    "Restaurants:",
    ...(memory.restaurants.length
      ? memory.restaurants.map(
          (r) => `- ${r.name} (ordered ${r.timesOrdered}x), last order: ${r.lastItems.length ? r.lastItems.join(", ") : "(unknown)"}`,
        )
      : ["(none)"]),
  ];
  return lines.join("\n");
}

/**
 * Dev-only stand-in for Claude: "cab/uber/ride to X", "cab home/work", "order (from) X",
 * "(order) the usual from X", "remember X".
 */
function mockParse(text: string, places: { home: string; work: string }, memory: MemorySummary): ParsedCommand {
  const t = text.trim();
  const base = { destination: null, restaurant: null, items: [] as string[], fact: null };
  const remember = t.match(/^remember\s+(?:that\s+)?(.+)$/i);
  if (remember) {
    return { ...base, action: "remember", fact: remember[1].trim(), message: "Got it, I'll remember that." };
  }
  const usual = t.match(/^(?:order\s+)?(?:my\s+|the\s+)?usual\s+(?:from\s+)?(.+)$/i);
  if (usual) {
    const name = usual[1].trim();
    const known = memory.restaurants.find((r) => r.name.toLowerCase() === name.toLowerCase());
    if (!known?.lastItems.length) {
      return { ...base, action: "unsupported", message: `I don't know your usual from ${name} yet.` };
    }
    return {
      ...base,
      action: "order_food",
      restaurant: known.name,
      items: known.lastItems,
      message: `Ordering your usual from ${known.name}`,
    };
  }
  const ride = t.match(/^(?:book\s+)?(?:a\s+)?(?:cab|uber|ride|taxi)\s+(?:to\s+)?(.+)$/i);
  if (ride) {
    const target = ride[1].trim();
    const saved = /^home$/i.test(target) ? places.home : /^work$/i.test(target) ? places.work : target;
    if (!saved) return { ...base, action: "unsupported", message: `Add your ${target} address in the Vecto app first.` };
    return { ...base, action: "book_ride", destination: saved, message: `Getting you an Uber to ${saved}` };
  }
  const food = t.match(/^order\s+(?:food\s+)?(?:from\s+)?(.+)$/i);
  if (food) {
    const restaurant = food[1].trim();
    return { ...base, action: "order_food", restaurant, message: `Opening ${restaurant} on Uber Eats` };
  }
  return { ...base, action: "unsupported", message: "Try \"cab to King's Cross\" or \"order from Dishoom\"." };
}

/** Counts requests per device per UTC day in KV. Skipped when KV isn't configured (local dev). */
async function allowRequest(env: Env, deviceId: string): Promise<boolean> {
  if (!env.RATE_LIMIT) return true;
  const key = `${deviceId}:${new Date().toISOString().slice(0, 10)}`;
  const count = Number((await env.RATE_LIMIT.get(key)) ?? "0");
  if (count >= Number(env.DAILY_LIMIT)) return false;
  await env.RATE_LIMIT.put(key, String(count + 1), { expirationTtl: 60 * 60 * 48 });
  return true;
}
