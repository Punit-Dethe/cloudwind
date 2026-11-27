// Cloudwind inbox Worker.
//
// Holds only ciphertext and per-object status (random id, size, timestamps).
// Two bearer tokens, two roles:
//   PHONE_TOKEN  upload objects, read status
//   AGENT_TOKEN  list pending, download, confirm synced
// Neither role can delete or overwrite anything.

export interface Env {
  INBOX: R2Bucket;
  DB: D1Database;
  PHONE_TOKEN: string;
  AGENT_TOKEN: string;
  GRACE_DAYS: string;
}

type Role = "phone" | "agent";

const ID = /^[0-9a-f]{32}$/;
const MAX_PART = 90 * 1024 * 1024; // Workers request body limit is 100 MB
const now = () => Math.floor(Date.now() / 1000);

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });
const err = (status: number, message: string) => json({ error: message }, status);

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

async function record(env: Env, id: string, size: number) {
  await env.DB.prepare(
    "INSERT OR IGNORE INTO objects (id, size, uploaded_at) VALUES (?, ?, ?)",
  ).bind(id, size, now()).run();
}

async function exists(env: Env, id: string) {
  return (await env.DB.prepare("SELECT 1 FROM objects WHERE id = ?").bind(id).first()) !== null;
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname;
    if (path === "/" || path === "/health") return json({ ok: true, service: "cloudwind" });

    const role = authenticate(req, env);
    if (!role) return err(401, "unauthorized");
    const need = (r: Role) => (role === r ? null : err(403, `requires ${r} token`));

    let m: RegExpExecArray | null;

    // ---- phone: upload -------------------------------------------------
    if ((m = /^\/v1\/objects\/([^/]+)$/.exec(path)) && req.method === "PUT") {
      const bad = need("phone"); if (bad) return bad;
      const id = m[1];
      if (!ID.test(id)) return err(400, "bad id");
      const len = Number(req.headers.get("content-length") ?? "");
      if (!Number.isFinite(len) || len <= 0) return err(411, "content-length required");
      if (len > MAX_PART) return err(413, "too large for a single PUT; use multipart");
      if (await exists(env, id)) return err(409, "already uploaded");
      await env.INBOX.put(key(id), req.body);
      await record(env, id, len);
      return json({ id, size: len }, 201);
    }

    if ((m = /^\/v1\/objects\/([^/]+)\/multipart$/.exec(path)) && req.method === "POST") {
      const bad = need("phone"); if (bad) return bad;
      const id = m[1];
      if (!ID.test(id)) return err(400, "bad id");
      if (await exists(env, id)) return err(409, "already uploaded");
      const up = await env.INBOX.createMultipartUpload(key(id));
      return json({ id, uploadId: up.uploadId }, 201);
    }

    if ((m = /^\/v1\/objects\/([^/]+)\/multipart\/([^/]+)\/(\d+)$/.exec(path)) && req.method === "PUT") {
      const bad = need("phone"); if (bad) return bad;
      const [, id, uploadId, n] = m;
      const part = Number(n);
      if (!ID.test(id) || part < 1 || part > 10000) return err(400, "bad id or part");
      const len = Number(req.headers.get("content-length") ?? "");
      if (!Number.isFinite(len) || len <= 0) return err(411, "content-length required");
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
      const body = (await req.json().catch(() => null)) as { parts?: R2UploadedPart[] } | null;
      if (!body?.parts?.length) return err(400, "parts required");
      const up = env.INBOX.resumeMultipartUpload(key(id), uploadId);
      const obj = await up.complete(body.parts);
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
      const pending = await env.DB.prepare(
        "SELECT COALESCE(SUM(size), 0) AS bytes FROM objects WHERE purged = 0",
      ).first<{ bytes: number }>();
      return json({
        objects: rows.results.map((r: any) => ({
          id: r.id,
          state: r.synced_at ? "synced" : "uploaded",
          size: r.size,
          uploaded_at: r.uploaded_at,
          synced_at: r.synced_at,
        })),
        inbox_bytes: pending?.bytes ?? 0,
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
      if (!ID.test(m[1])) return err(400, "bad id");
      const obj = await env.INBOX.get(key(m[1]));
      if (!obj) return err(404, "not found");
      return new Response(obj.body, {
        headers: { "content-type": "application/octet-stream", "content-length": String(obj.size) },
      });
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

  // Remove inbox copies once they have been synced for GRACE_DAYS.
  async scheduled(_c: ScheduledController, env: Env): Promise<void> {
    const cutoff = now() - Number(env.GRACE_DAYS || "7") * 86400;
    const rows = await env.DB.prepare(
      "SELECT id FROM objects WHERE synced_at IS NOT NULL AND synced_at < ? AND purged = 0 LIMIT 500",
    ).bind(cutoff).all<{ id: string }>();
    for (const { id } of rows.results) {
      await env.INBOX.delete(key(id));
      await env.DB.prepare("UPDATE objects SET purged = 1 WHERE id = ?").bind(id).run();
    }
  },
};
