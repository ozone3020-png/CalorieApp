// POST /sync — encrypted, last-write-wins sync of meals and favourites between a user's phones.
//
// Request:  { account, since, changes: [{ kind, id, updated_at, deleted, data }] }
// Response: { records: [{ kind, id, updated_at, deleted, data, rev }], rev, more }
//
// `account` is derived from the user's sync key on the phone (the key never leaves it), and `data` is encrypted
// there with a key also derived from it, so this server can't read anyone's log. Each stored change gets the next
// account-wide revision number; a phone sends the highest revision it has seen (`since`) and gets everything newer,
// in pages of PAGE_SIZE (`more` says to call again).

const KINDS = new Set(["meal", "favourite"]);
const MAX_CHANGES = 500;
const MAX_DATA_CHARS = 32_000;
const PAGE_SIZE = 500;

export async function sync(body, env, json) {
  if (!env.DB) return json({ error: "sync not set up", detail: "This server has no sync database." }, 501);

  const account = typeof body.account === "string" ? body.account : "";
  if (!/^[A-Za-z0-9_-]{43}$/.test(account)) return json({ error: "bad account" }, 400);
  const since = Number.isInteger(body.since) && body.since >= 0 ? body.since : 0;
  const changes = Array.isArray(body.changes) ? body.changes : [];
  if (changes.length > MAX_CHANGES) return json({ error: `at most ${MAX_CHANGES} changes per request` }, 413);

  const valid = [];
  for (const c of changes) {
    const ok = c && KINDS.has(c.kind) && typeof c.id === "string" && c.id.length > 0 && c.id.length <= 200 &&
      Number.isFinite(c.updated_at) &&
      (c.deleted ? true : typeof c.data === "string" && c.data.length <= MAX_DATA_CHARS);
    if (!ok) return json({ error: "bad change", detail: JSON.stringify(c).slice(0, 200) }, 400);
    valid.push(c);
  }

  const now = Date.now();
  await env.DB.prepare("INSERT OR IGNORE INTO accounts (account, rev, created_at) VALUES (?, 0, ?)")
    .bind(account, now).run();

  if (valid.length) {
    // Reserve a block of revision numbers atomically, then store each change with its own number.
    const { rev: top } = await env.DB.prepare("UPDATE accounts SET rev = rev + ? WHERE account = ? RETURNING rev")
      .bind(valid.length, account).first();
    const first = top - valid.length + 1;
    await env.DB.batch(valid.map((c, i) => env.DB.prepare(
      `INSERT INTO records (account, kind, id, updated_at, deleted, data, rev) VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT (account, kind, id) DO UPDATE SET
         updated_at = excluded.updated_at, deleted = excluded.deleted, data = excluded.data, rev = excluded.rev
       WHERE excluded.updated_at > records.updated_at`,
    ).bind(account, c.kind, c.id, Math.trunc(c.updated_at), c.deleted ? 1 : 0, c.deleted ? null : c.data, first + i)));
  }

  const { results } = await env.DB.prepare(
    "SELECT kind, id, updated_at, deleted, data, rev FROM records WHERE account = ? AND rev > ? ORDER BY rev LIMIT ?",
  ).bind(account, since, PAGE_SIZE + 1).all();
  const more = results.length > PAGE_SIZE;
  const records = results.slice(0, PAGE_SIZE).map((r) => ({ ...r, deleted: r.deleted === 1 }));
  const rev = records.length ? records[records.length - 1].rev : since;
  return json({ records, rev, more });
}
