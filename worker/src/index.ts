// Cloudwind inbox Worker.
//
// Holds only ciphertext and per-object status (random id, size, timestamps).
// Two bearer tokens, two roles:
//   PHONE_TOKEN  upload objects, read status
//   AGENT_TOKEN  list pending, download, confirm synced
// Neither role can delete or overwrite anything.
//
// Guards (all configurable in wrangler.toml):
//   * inbox byte cap with reservations for in-flight multipart uploads
//   * synced copies are evictable cache; unsynced objects are never evicted
//   * per-object size max, per-object download cap, per-role request rate limit
//   * stale multipart uploads are aborted by the daily cron

export interface Env {
  INBOX: R2Bucket;
  DB: D1Database;
  PHONE_TOKEN: string;
  AGENT_TOKEN: string;
  GRACE_DAYS: string;
  INBOX_CAP_BYTES: string;
  SOFT_FRACTION: string;
  MAX_OBJECT_BYTES: string;
  MAX_DOWNLOADS: string;
  LIMITER?: RateLimit;
}

type Role = "phone" | "agent";

const GIB = 1024 ** 3;
const ID = /^[0-9a-f]{32}$/;
const MAX_PART = 90 * 1024 * 1024; // Workers request body limit is 100 MB
const STALE_UPLOAD_SECS = 24 * 3600;
const now = () => Math.floor(Date.now() / 1000);
const num = (v: string | undefined, d: number) => {
  const n = Number(v);
  return v !== undefined && v !== "" && Number.isFinite(n) && n >= 0 ? n : d;
};

const json = (data: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json", ...headers } });
const err = (status: number, message: string, extra: Record<string, unknown> = {}) =>
  json({ error: message, ...extra }, status);

function same(a: string, b: string): boolean {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  return x.length === y.length && crypto.subtle.timingSafeEqual(x, y);
}

function authenticate(req: Request, env: Env): Role | null {
  const m = /^Bearer (.+)$/.exec(req.headers.get("authorization") ?? "");
  if (!m) return null;
  if (env.PHONE_TOKEN && same(m[1], env.PHONE_TOKEN)) return "phone";
  if (env.AGENT_TOKEN && same(m[1], env.AGENT_TOKEN)) return "agent";
  return null;
}

const key = (id: string) => `o/${id}`;

// ---- limits & accounting ---------------------------------------------------

const limits = (env: Env) => {
  const cap = num(env.INBOX_CAP_BYTES, 8 * GIB);
  return {
    cap,
    soft: Math.floor(cap * Math.min(1, num(env.SOFT_FRACTION, 0.6))),
    maxObject: num(env.MAX_OBJECT_BYTES, 5 * GIB),
    maxDownloads: num(env.MAX_DOWNLOADS, 5),
  };
};

/** Bytes held in R2 (not yet purged) plus bytes reserved by in-flight multipart uploads. */
async function usedBytes(env: Env): Promise<number> {
  const [a, b] = await env.DB.batch([
    env.DB.prepare("SELECT COALESCE(SUM(size), 0) AS n FROM objects WHERE purged = 0"),
    env.DB.prepare("SELECT COALESCE(SUM(reserved), 0) AS n FROM uploads"),
  ]);
  return ((a.results[0] as any)?.n ?? 0) + ((b.results[0] as any)?.n ?? 0);
}

/** Delete cloud copies (R2 + mark purged). Batches keep us under the 50-subrequest limit. */
async function purge(env: Env, ids: string[]) {
  for (let i = 0; i < ids.length; i += 90) {
    const chunk = ids.slice(i, i + 90);
    await env.INBOX.delete(chunk.map(key));
    await env.DB.prepare(`UPDATE objects SET purged = 1 WHERE id IN (${chunk.map(() => "?").join(",")})`)
      .bind(...chunk).run();
  }
}

/**
 * Make room for `need` bytes. Above the soft line, evict synced copies oldest-first
 * (the PC already verified them). Unsynced objects are never touched.
 * Returns null if it fits, or a 507 response if the hard cap would be exceeded.
 */
async function makeRoom(env: Env, need: number): Promise<Response | null> {
  const L = limits(env);
  let used = await usedBytes(env);
  if (used + need > L.soft) {
    const rows = await env.DB.prepare(
      "SELECT id, size FROM objects WHERE synced_at IS NOT NULL AND purged = 0 ORDER BY synced_at LIMIT 200",
    ).all<{ id: string; size: number }>();
    const ids: string[] = [];
    for (const r of rows.results) {
      if (used + need <= L.soft) break;
      ids.push(r.id); used -= r.size;
    }
    if (ids.length) await purge(env, ids);
  }
  if (used + need > L.cap) {
    return err(507, "inbox_full", { free_bytes: Math.max(0, L.cap - used), cap_bytes: L.cap });
  }
  return null;
}

async function record(env: Env, id: string, size: number) {
  await env.DB.prepare("INSERT OR IGNORE INTO objects (id, size, uploaded_at) VALUES (?, ?, ?)")
    .bind(id, size, now()).run();
}

async function exists(env: Env, id: string) {
  return (await env.DB.prepare("SELECT 1 FROM objects WHERE id = ?").bind(id).first()) !== null;
}

const contentLength = (req: Request) => {
  const n = Number(req.headers.get("content-length") ?? "");
  return Number.isFinite(n) && n > 0 ? n : 0;
};

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname;
    if (path === "/" || path === "/health") return json({ ok: true, service: "cloudwind" });

    const role = authenticate(req, env);
    if (!role) return err(401, "unauthorized");
    const need = (r: Role) => (role === r ? null : err(403, `requires ${r} token`));

    if (env.LIMITER) {
      const { success } = await env.LIMITER.limit({ key: role });
      if (!success) return json({ error: "rate_limited", retry_after: 30 }, 429, { "retry-after": "30" });
    }

    const L = limits(env);
    let m: RegExpExecArray | null;

    // ---- phone: upload -------------------------------------------------
    if ((m = /^\/v1\/objects\/([^/]+)$/.exec(path)) && req.method === "PUT") {
      const bad = need("phone"); if (bad) return bad;
      const id = m[1];
      if (!ID.test(id)) return err(400, "bad id");
      const len = contentLength(req);
      if (!len) return err(411, "content-length required");
      if (len > MAX_PART) return err(413, "too_large_for_single_put", { max_single_bytes: MAX_PART });
      if (len > L.maxObject) return err(413, "object_too_large", { max_object_bytes: L.maxObject });
      if (await exists(env, id)) return err(409, "already uploaded");
      const full = await makeRoom(env, len); if (full) return full;
      await env.INBOX.put(key(id), req.body);
      await record(env, id, len);
      return json({ id, size: len }, 201);
    }

    if ((m = /^\/v1\/objects\/([^/]+)\/multipart$/.exec(path)) && req.method === "POST") {
      const bad = need("phone"); if (bad) return bad;
      const id = m[1];
      if (!ID.test(id)) return err(400, "bad id");
      const body = (await req.json().catch(() => null)) as { size?: number } | null;
      const size = Number(body?.size);
      if (!Number.isInteger(size) || size <= 0) return err(400, "size_required");
      if (size > L.maxObject) return err(413, "object_too_large", { max_object_bytes: L.maxObject });
      if (await exists(env, id)) return err(409, "already uploaded");
      const full = await makeRoom(env, size); if (full) return full;
      const up = await env.INBOX.createMultipartUpload(key(id));
      await env.DB.prepare("INSERT INTO uploads (upload_id, id, reserved, created_at) VALUES (?, ?, ?, ?)")
        .bind(up.uploadId, id, size, now()).run();
      return json({ id, uploadId: up.uploadId }, 201);
    }

    if ((m = /^\/v1\/objects\/([^/]+)\/multipart\/([^/]+)\/(\d+)$/.exec(path)) && req.method === "PUT") {
      const bad = need("phone"); if (bad) return bad;
      const [, id, uploadId, n] = m;
      const part = Number(n);
      if (!ID.test(id) || part < 1 || part > 10000) return err(400, "bad id or part");
      const len = contentLength(req);
      if (!len) return err(411, "content-length required");
      if (len > MAX_PART) return err(413, "part too large");
      const up = env.INBOX.resumeMultipartUpload(key(id), uploadId);
      const p = await up.uploadPart(part, req.body!);
      return json({ partNumber: p.partNumber, etag: p.etag });
    }

    if ((m = /^\/v1\/objects\/([^/]+)\/multipart\/([^/]+)\/complete$/.exec(path)) && req.method === "POST") {
      const bad = need("phone"); if (bad) return bad;
      const [, id, uploadId] = m;
      if (!ID.test(id)) return err(400, "bad id");
      if (await exists(env, id)) return err(409, "already uploaded");
      const res = await env.DB.prepare("SELECT reserved FROM uploads WHERE upload_id = ? AND id = ?")
        .bind(uploadId, id).first<{ reserved: number }>();
      if (!res) return err(404, "unknown upload");
      const body = (await req.json().catch(() => null)) as { parts?: R2UploadedPart[] } | null;
      if (!body?.parts?.length) return err(400, "parts required");
      const up = env.INBOX.resumeMultipartUpload(key(id), uploadId);
      const obj = await up.complete(body.parts);
      await env.DB.prepare("DELETE FROM uploads WHERE upload_id = ?").bind(uploadId).run();
      if (obj.size > res.reserved) {
        // Client declared less than it sent; drop it so the cap can't be dodged.
        await env.INBOX.delete(key(id));
        return err(400, "size_mismatch", { declared: res.reserved, actual: obj.size });
      }
      await record(env, id, obj.size);
      return json({ id, size: obj.size }, 201);
    }

    // ---- phone: status -------------------------------------------------
    if (path === "/v1/status" && req.method === "GET") {
      const bad = need("phone"); if (bad) return bad;
      const since = Number(url.searchParams.get("since") ?? 0) || 0;
      const rows = await env.DB.prepare(
        "SELECT id, size, uploaded_at, synced_at FROM objects WHERE uploaded_at >= ? OR synced_at >= ? ORDER BY uploaded_at LIMIT 1000",
      ).bind(since, since).all();
      const used = await usedBytes(env);
      return json({
        objects: rows.results.map((r: any) => ({
          id: r.id,
          state: r.synced_at ? "synced" : "uploaded",
          size: r.size,
          uploaded_at: r.uploaded_at,
          synced_at: r.synced_at,
        })),
        inbox_bytes: used,
        limits: {
          cap_bytes: L.cap,
          soft_bytes: L.soft,
          free_bytes: Math.max(0, L.cap - used),
          max_object_bytes: L.maxObject,
        },
      });
    }

    // ---- agent: pull ---------------------------------------------------
    if (path === "/v1/pending" && req.method === "GET") {
      const bad = need("agent"); if (bad) return bad;
      const rows = await env.DB.prepare(
        "SELECT id, size FROM objects WHERE synced_at IS NULL AND purged = 0 ORDER BY uploaded_at LIMIT 500",
      ).all();
      return json({ objects: rows.results });
    }

    if ((m = /^\/v1\/objects\/([^/]+)$/.exec(path)) && req.method === "GET") {
      const bad = need("agent"); if (bad) return bad;
      const id = m[1];
      if (!ID.test(id)) return err(400, "bad id");

      // Resume support: only the open-ended form `bytes=N-` (what the agent sends).
      const rm = /^bytes=(\d+)-$/.exec(req.headers.get("range") ?? "");
      const offset = rm ? Number(rm[1]) : 0;

      // A full download (offset 0) counts toward the cap; resumed tails don't.
      if (offset === 0) {
        const r = await env.DB.prepare(
          "UPDATE objects SET downloads = downloads + 1 WHERE id = ? AND purged = 0 AND downloads < ?",
        ).bind(id, L.maxDownloads).run();
        if (!r.meta.changes) {
          const row = await env.DB.prepare("SELECT downloads, purged FROM objects WHERE id = ?").bind(id)
            .first<{ downloads: number; purged: number }>();
          if (!row || row.purged) return err(404, "not found");
          return json({ error: "download_limit", limit: L.maxDownloads }, 429, { "retry-after": "86400" });
        }
      }

      const head = await env.INBOX.head(key(id));
      if (!head) return err(404, "not found");
      if (offset >= head.size && offset > 0) return new Response(null, { status: 416, headers: { "content-range": `bytes */${head.size}` } });
      const obj = await env.INBOX.get(key(id), offset > 0 ? { range: { offset } } : undefined);
      if (!obj) return err(404, "not found");
      const headers: Record<string, string> = {
        "content-type": "application/octet-stream",
        "content-length": String(head.size - offset),
        "accept-ranges": "bytes",
      };
      if (offset > 0) headers["content-range"] = `bytes ${offset}-${head.size - 1}/${head.size}`;
      return new Response(obj.body, { status: offset > 0 ? 206 : 200, headers });
    }

    if ((m = /^\/v1\/objects\/([^/]+)\/synced$/.exec(path)) && req.method === "POST") {
      const bad = need("agent"); if (bad) return bad;
      if (!ID.test(m[1])) return err(400, "bad id");
      const r = await env.DB.prepare(
        "UPDATE objects SET synced_at = COALESCE(synced_at, ?) WHERE id = ?",
      ).bind(now(), m[1]).run();
      return r.meta.changes ? json({ id: m[1], state: "synced" }) : err(404, "unknown id");
    }

    return err(404, "not found");
  },

  // Daily: drop synced copies past the grace period and abort abandoned multipart uploads.
  async scheduled(_c: ScheduledController, env: Env): Promise<void> {
    const cutoff = now() - Number(env.GRACE_DAYS || "7") * 86400;
    const rows = await env.DB.prepare(
      "SELECT id FROM objects WHERE synced_at IS NOT NULL AND synced_at < ? AND purged = 0 LIMIT 500",
    ).bind(cutoff).all<{ id: string }>();
    if (rows.results.length) await purge(env, rows.results.map((r) => r.id));

    const stale = await env.DB.prepare(
      "SELECT upload_id, id FROM uploads WHERE created_at < ? LIMIT 20",
    ).bind(now() - STALE_UPLOAD_SECS).all<{ upload_id: string; id: string }>();
    for (const u of stale.results) {
      try { await env.INBOX.resumeMultipartUpload(key(u.id), u.upload_id).abort(); } catch { /* already gone */ }
      await env.DB.prepare("DELETE FROM uploads WHERE upload_id = ?").bind(u.upload_id).run();
    }
  },
};
