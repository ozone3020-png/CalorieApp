// AI providers (Cloudflare Workers AI, Google Gemini) behind one interface, with automatic fallback,
// plus tolerant JSON extraction from model output.

export const PROVIDERS = ["workers-ai", "gemini"];

/**
 * An AI session for one request. `preferred` is tried first; if it fails (daily limit, outage, missing key)
 * the other provider is tried, and later calls in the same request start with whichever worked.
 * `ai.provider` is the provider that answered the first call; `ai.fellBack` says the preferred one failed.
 */
export function makeAi(env, preferred = "workers-ai", defaults = {}) {
  const order = (preferred === "gemini" ? ["gemini", "workers-ai"] : ["workers-ai", "gemini"])
    .filter((p) => p !== "gemini" || env.GEMINI_API_KEY);
  const ai = {
    preferred,
    provider: null,
    fellBack: false,
    /**
     * @param messages OpenAI-style [{role, content}], content a string or [{type:"text"}, {type:"image_url"}]
     * @param opts.model Workers AI model override (local model comparisons only)
     */
    async run(messages, maxTokens = 1024, opts = {}) {
      if (!order.length) throw new Error("no AI provider configured");
      const errors = [];
      for (const p of [...order]) {
        try {
          const text = await CALL[p](env, messages, maxTokens, { ...defaults, ...opts });
          if (!ai.provider) ai.provider = p;
          if (p !== preferred) ai.fellBack = true;
          const i = order.indexOf(p);
          if (i > 0) order.unshift(...order.splice(i, 1)); // later calls in this request start with p
          return text;
        } catch (err) {
          errors.push(`${p}: ${String(err).slice(0, 200)}`);
          console.log(`${p} failed, trying the next provider:`, String(err).slice(0, 300));
        }
      }
      throw new Error(errors.join(" | "));
    },
  };
  return ai;
}

const CALL = {
  "workers-ai": async (env, messages, maxTokens, opts) => {
    const result = await env.AI.run(opts.model ?? env.MODEL, { messages, max_tokens: maxTokens, temperature: 0.1 });
    return result?.response;
  },

  gemini: async (env, messages, maxTokens, opts) => {
    const models = (opts.geminiModel ?? env.GEMINI_MODEL ?? "gemini-3.5-flash").split(",").map((m) => m.trim());
    let lastError;
    for (const model of models) {
      try {
        return await callGemini(env, model, messages, maxTokens);
      } catch (err) {
        lastError = err;
        // Retired or overloaded: the next model may work. Anything else (quota, timeout): give up on Gemini.
        if (!/HTTP (404|503)/.test(String(err))) break;
      }
    }
    throw lastError;
  },
};

async function callGemini(env, model, messages, maxTokens) {
    const hasImage = messages.some((m) => Array.isArray(m.content) && m.content.some((p) => p.type === "image_url"));
    const system = messages.filter((m) => m.role === "system").map((m) => m.content).join("\n\n");
    const contents = messages
      .filter((m) => m.role !== "system")
      .map((m) => ({ role: m.role === "assistant" ? "model" : "user", parts: toGeminiParts(m.content) }));
    const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
      body: JSON.stringify({
        ...(system ? { systemInstruction: { parts: [{ text: system }] } } : {}),
        contents,
        generationConfig: {
          temperature: 0.1,
          maxOutputTokens: maxTokens,
          responseMimeType: "application/json",
          // No hidden "thinking": faster, and it keeps the free-tier token use down.
          thinkingConfig: { thinkingBudget: 0 },
        },
      }),
      // Give up quickly enough that the fallback provider still answers in reasonable time.
      signal: AbortSignal.timeout(hasImage ? 35_000 : 20_000),
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      const quota = res.status === 429 ? " (free-tier limit reached)" : "";
      throw new Error(`Gemini ${model} HTTP ${res.status}${quota}: ${body?.error?.message ?? ""}`.slice(0, 400));
    }
    const text = body?.candidates?.[0]?.content?.parts?.map((p) => p.text ?? "").join("");
    if (!text) throw new Error(`Gemini returned no text (${body?.candidates?.[0]?.finishReason ?? "unknown"})`);
    return text;
}

function toGeminiParts(content) {
  if (typeof content === "string") return [{ text: content }];
  return content.map((part) => {
    if (part.type === "image_url") {
      const [, mime, data] = /^data:([^;]+);base64,(.*)$/s.exec(part.image_url.url) ?? [];
      return { inline_data: { mime_type: mime ?? "image/jpeg", data: data ?? "" } };
    }
    return { text: part.text ?? "" };
  });
}

/** Model output may be an object already, or a string with prose / ``` fences around the JSON. */
export function parseJson(response) {
  if (response && typeof response === "object") return response;
  if (typeof response !== "string") return null;
  const start = response.search(/[[{]/);
  const end = Math.max(response.lastIndexOf("}"), response.lastIndexOf("]"));
  if (start === -1 || end <= start) return null;
  try {
    return JSON.parse(response.slice(start, end + 1));
  } catch {
    return null;
  }
}

export function num(value, decimals = 1) {
  const n = Number(value);
  if (!Number.isFinite(n) || n < 0) return 0;
  const f = 10 ** decimals;
  return Math.round(n * f) / f;
}
