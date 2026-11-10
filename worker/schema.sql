-- Sync storage. Records are encrypted on the phone; the server only sees opaque ids, timestamps and ciphertext.
-- Apply with: npx wrangler d1 execute calorie-sync --remote --file schema.sql
CREATE TABLE IF NOT EXISTS accounts (
  account    TEXT PRIMARY KEY,           -- hash derived from the user's sync key (not the key itself)
  rev        INTEGER NOT NULL DEFAULT 0, -- last revision number handed out for this account
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS records (
  account    TEXT NOT NULL,
  kind       TEXT NOT NULL,              -- "meal" | "favourite"
  id         TEXT NOT NULL,              -- random id chosen on the phone
  updated_at INTEGER NOT NULL,           -- phone's clock; last write wins
  deleted    INTEGER NOT NULL DEFAULT 0,
  data       TEXT,                       -- encrypted record (null when deleted)
  rev        INTEGER NOT NULL,           -- account-wide revision when stored; phones pull rev > their last
  PRIMARY KEY (account, kind, id)
);
CREATE INDEX IF NOT EXISTS records_by_rev ON records (account, rev);
