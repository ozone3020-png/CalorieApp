// Food-name normalisation shared by the AFCD search.

const STOP = new Set([
  "a", "an", "and", "the", "of", "with", "in", "on", "or", "fresh", "plain", "homemade",
  "piece", "pieces", "slice", "slices", "serve", "serving", "portion", "some", "side",
]);

// US/UK -> Australian food terms, applied to the whole phrase before tokenising.
const PHRASES = [
  [/\bground (beef|pork|lamb|chicken|turkey|veal)\b/g, "$1 mince"],
  [/\bminced (beef|pork|lamb|chicken|turkey|veal)\b/g, "$1 mince"],
  [/\bbell peppers?\b/g, "capsicum"],
  [/\bcourgettes?\b/g, "zucchini"],
  [/\baubergines?\b/g, "eggplant"],
  [/\bcilantro\b/g, "coriander"],
  [/\bscallions?\b/g, "spring onion"],
  [/\bshrimps?\b/g, "prawn"],
  [/\b(french )?fries\b/g, "potato chips"],
  [/\bhot chips?\b/g, "potato chips"],
  [/\btoast\b/g, "bread toasted"],
  [/\b(coke|coca[- ]cola|pepsi)\b/g, "cola soft drink"],
  [/\b(zero|diet|sugar[- ]free|no sugar|max)\b/g, "intense sweetened diet"],
  [/\b(soda|fizzy drink|pop)\b/g, "soft drink"],
  [/\b(spaghetti|penne|fettuccine|linguine|fusilli|rigatoni|tagliatelle|macaroni|farfalle)\b/g, "$1 pasta"],
  [/\bcookies?\b/g, "biscuit"],
  [/\bmashed potato(es)?\b/g, "potato mashed"],
];

// Preparation words only help rank foods that already match on the food itself.
export const PREP = new Set([
  "cooked", "raw", "boiled", "grilled", "fried", "baked", "roasted", "roast", "steamed", "stir",
  "poached", "toasted", "microwaved", "drained", "canned", "frozen", "dried", "dry", "uncooked",
]);

export function stem(word) {
  if (word.length > 4 && word.endsWith("ies")) return word.slice(0, -3) + "y";
  if (word.length > 4 && word.endsWith("oes")) return word.slice(0, -2);
  if (word.length > 4 && /(s|x|ch|sh)es$/.test(word)) return word.slice(0, -2);
  if (word.length > 3 && word.endsWith("s") && !word.endsWith("ss")) return word.slice(0, -1);
  return word;
}

export function tokens(text, { stopwords = true } = {}) {
  let t = text.toLowerCase();
  for (const [re, rep] of PHRASES) t = t.replace(re, rep);
  return t
    .split(/[^a-z0-9]+/)
    .filter((w) => w && !(stopwords && STOP.has(w)))
    .map(stem);
}
