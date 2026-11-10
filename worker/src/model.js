// Workers AI calls and tolerant JSON extraction from model output.

export async function runModel(env, messages, maxTokens = 1024, model = env.MODEL) {
  const result = await env.AI.run(model, { messages, max_tokens: maxTokens, temperature: 0.1 });
  return result?.response;
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
