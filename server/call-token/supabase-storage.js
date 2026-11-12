"use strict";
/** Private objects only; no Supabase service key or public media URL reaches Android. */
function createStorage({ url, key, bucket = "utechat-media", fetchImpl = fetch }) {
  const base = new URL(url);
  if (base.protocol !== "https:" || base.username || base.password || base.pathname !== "/"
      || !/^[a-z0-9-]+\.supabase\.co$/.test(base.hostname) || !/^[a-z0-9_-]+$/.test(bucket))
    throw new Error("Use the HTTPS Supabase project URL and a private bucket name.");
  // New sb_secret keys are not JWTs: send only apikey. Legacy service-role JWTs
  // still require Authorization for compatibility with the older gateway.
  const headers = key.startsWith("sb_secret_") ? { apikey: key } : { apikey: key, Authorization: `Bearer ${key}` };
  const endpoint = (path, authenticated = false) => `${base.origin}/storage/v1/object/${authenticated ? "authenticated/" : ""}${bucket}/${path.split("/").map(encodeURIComponent).join("/")}`;
  return {
    async upload(path, bytes, type) {
      const response = await fetchImpl(endpoint(path), { method: "POST", redirect: "error", signal: AbortSignal.timeout(30_000),
        headers: { ...headers, "Content-Type": type, "x-upsert": "false" }, body: bytes });
      if (response.ok) { await response.arrayBuffer(); return true; }
      const body = await response.json().catch(() => ({}));
      if (response.status === 409 || body.statusCode === "409" || body.error === "Duplicate") return false;
      throw new Error("Private storage upload failed.");
    },
    async download(path) {
      const response = await fetchImpl(endpoint(path, true), { redirect: "error", signal: AbortSignal.timeout(30_000), headers });
      if (!response.ok) throw new Error("Private storage download failed.");
      const chunks = []; let size = 0;
      for await (const chunk of response.body) {
        size += chunk.length; if (size > 8 * 1024 * 1024) throw new Error("Object too large."); chunks.push(chunk);
      }
      return Buffer.concat(chunks);
    },
  };
}
module.exports = { createStorage };
