"use strict";
const crypto = require("node:crypto");
const MAX_IMAGE_BYTES = 8 * 1024 * 1024;
const MAX_VOICE_BYTES = 512 * 1024;
const VOICE_TYPE = "audio/mp4";
const hash = (value) => crypto.createHash("sha256").update(value).digest("hex");
const segment = (value) => typeof value === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(value);
class ApiError extends Error { constructor(status, code) { super(code); this.status = status; } }
function requireValue(condition, status = 400, code = "invalid-request") {
  if (!condition) throw new ApiError(status, code);
}
function mediaPath(value) {
  const parts = typeof value === "string" ? value.split("/") : [];
  requireValue(parts.length === 4 && parts[0] === "conversations" && parts.slice(1).every(segment));
  return { conversationId: parts[1], ownerUid: parts[2], messageId: parts[3] };
}
function validImage(bytes, type) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 12 || bytes.length > MAX_IMAGE_BYTES) return false;
  if (type === "image/jpeg") return bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
  if (type === "image/png") return bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]));
  return type === "image/webp" && bytes.toString("ascii", 0, 4) === "RIFF" && bytes.toString("ascii", 8, 12) === "WEBP";
}
function validVoice(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 24 || bytes.length > MAX_VOICE_BYTES) return false;
  let header = false, metadata = false, data = false;
  for (let offset = 0; offset < bytes.length;) {
    const remaining = bytes.length - offset;
    if (remaining < 8) return false;
    let length = bytes.readUInt32BE(offset);
    let headerSize = 8;
    if (length === 1) {
      // Android's MediaRecorder writes mdat with an extended size even for short clips.
      headerSize = 16;
      if (remaining < headerSize || bytes.readUInt32BE(offset + 8) !== 0) return false;
      length = bytes.readUInt32BE(offset + 12);
    } else if (length === 0) {
      length = remaining;
    }
    if (length < headerSize || length > remaining) return false;
    const box = bytes.toString("ascii", offset + 4, offset + 8);
    if (offset === 0) header = box === "ftyp" && length >= headerSize + 8;
    if (box === "moov" && length > headerSize) metadata = true;
    if (box === "mdat" && length > headerSize) data = true;
    offset += length;
  }
  return header && metadata && data;
}
async function readBody(req, limit) {
  const chunks = []; let size = 0;
  requireValue(!req.headers["content-length"] || Number(req.headers["content-length"]) <= limit, 413, "too-large");
  for await (const chunk of req) {
    size += chunk.length; requireValue(size <= limit, 413, "too-large"); chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}
async function readJson(req) {
  const raw = await readBody(req, 4096);
  try { return JSON.parse(raw.toString("utf8") || "{}"); }
  catch (_) { throw new ApiError(400, "invalid-json"); }
}
function send(res, status, body, type = "application/json; charset=utf-8") {
  res.writeHead(status, { "content-type": type, "cache-control": "no-store", "x-content-type-options": "nosniff" });
  res.end(Buffer.isBuffer(body) ? body : JSON.stringify(body));
}

/** Dependency injection keeps authorization tests off live Firebase. */
function createBackend({ auth, db, messaging, mintToken, storage = null, now = Date.now, dailyUploadBytes = 32 * 1024 * 1024 }) {
  const windows = new Map(), expiryTimers = new Map(); let lastSweep = 0;
  const groupDeletions = new Map();
  async function contactAllowed(first, second, tx) {
    const refs = [db.doc(`users/${first}/blocked/${second}`), db.doc(`users/${second}/blocked/${first}`)];
    const blocks = await Promise.all(refs.map(ref => tx ? tx.get(ref) : ref.get()));
    return blocks.every(block => !block.exists);
  }
  async function member(conversationId, uid) {
    const parent = await db.doc(`conversations/${conversationId}`).get(), data = parent.data();
    requireValue(parent.exists && Array.isArray(data.participantIds) && data.participantIds.includes(uid), 403, "forbidden");
    requireValue(!data.lifecycle || data.lifecycle === "active", 409, "group-deleting");
    if (data.type === "direct") {
      requireValue(data.participantIds.length === 2 && new Set(data.participantIds).size === 2, 403, "forbidden");
    } else {
      requireValue(data.type === "group", 403, "forbidden");
      const snapshot = await db.doc(`conversations/${conversationId}/members/${uid}`).get();
      requireValue(snapshot.exists && snapshot.data().userId === uid, 403, "forbidden");
    }
    return data;
  }
  async function deleteGroup(conversationId, uid) {
    requireValue(segment(conversationId));
    const root = db.doc(`conversations/${conversationId}`), receipt = db.doc(`backendGroupDeletions/${conversationId}`);
    const complete = await db.runTransaction(async (tx) => {
      const [parent, saved] = await Promise.all([tx.get(root), tx.get(receipt)]);
      if (saved.exists) {
        requireValue(saved.data().actorUid === uid, 403, "forbidden");
        if (saved.data().status === "complete") return true;
        requireValue(parent.exists && parent.data().lifecycle === "deleting", 409, "invalid-deletion");
        return false;
      }
      requireValue(parent.exists, 404, "not-found");
      const group = parent.data();
      requireValue(group.type === "group" && Array.isArray(group.participantIds) && group.participantIds.includes(uid), 403, "forbidden");
      const membership = await tx.get(root.collection("members").doc(uid));
      requireValue(membership.exists && membership.data().userId === uid, 403, "forbidden");
      requireValue(membership.data().role === "owner" || (group.participantIds.length === 1 && group.participantIds[0] === uid), 403, "group-delete-forbidden");
      requireValue(!group.lifecycle || group.lifecycle === "active", 409, "group-deleting");
      tx.set(receipt, { actorUid: uid, status: "pending", createdAt: now() });
      tx.update(root, { lifecycle: "deleting", deletingBy: uid, updatedAt: now() });
      return false;
    });
    if (complete) return;
    // A durable receipt makes interrupted cleanup retryable after a Free host restarts.
    // Keep the parent frozen until every child collection and private object is gone.
    if (!groupDeletions.has(conversationId)) {
      const cleanup = (async () => {
        const prefix = `conversations/${conversationId}/`;
        while (true) {
          const media = await db.collection("backendMedia").where("path", ">=", prefix).where("path", "<", prefix + "\uf8ff").limit(100).get();
          if (!media.docs.length) break;
          requireValue(storage?.remove, 503, "media-not-configured");
          await storage.remove(media.docs.map(row => row.data().path));
          const batch = db.batch(); media.docs.forEach(row => batch.delete(row.ref)); await batch.commit();
        }
        await db.recursiveDelete(root.collection("messages"));
        await db.recursiveDelete(root.collection("members"));
        await db.recursiveDelete(db.doc(`backendVoice/${conversationId}`).collection("media"));
        await db.runTransaction(async tx => {
          const parent = await tx.get(root), saved = await tx.get(receipt);
          if (saved.exists && saved.data().status === "complete") return;
          requireValue(parent.exists && parent.data().lifecycle === "deleting" && parent.data().deletingBy === uid, 409, "invalid-deletion");
          tx.delete(root); tx.update(receipt, { status: "complete", completedAt: now() });
        });
      })();
      groupDeletions.set(conversationId, cleanup);
      cleanup.finally(() => groupDeletions.delete(conversationId)).catch(() => {});
    }
    await groupDeletions.get(conversationId);
  }
  function rateLimit(uid, kind) {
    const key = `${uid}:${kind}`, time = now();
    if (windows.size > 5000) for (const [id, window] of windows) if (window.until < time) windows.delete(id);
    const window = windows.get(key);
    if (!window || time >= window.until) { windows.set(key, { until: time + 60_000, count: 1 }); return; }
    requireValue(++window.count <= (kind === "media" ? 30 : 120), 429, "rate-limited");
  }
  async function acceptedCall(callId, uid) {
    requireValue(segment(callId));
    return db.runTransaction(async (tx) => {
      const ref = db.doc(`calls/${callId}`), snap = await tx.get(ref);
      requireValue(snap.exists, 404, "not-found"); const call = snap.data();
      requireValue(Array.isArray(call.participants) && call.participants.includes(uid)
        && [call.callerUid, call.calleeUid].includes(uid), 403, "forbidden");
      requireValue(call.status === "accepted" && call.channelName === callId, 409, "call-not-ready");
      const parent = await tx.get(db.doc(`conversations/${call.conversationId}`)), conversation = parent.data();
      requireValue(parent.exists && conversation.type === "direct" && Array.isArray(conversation.participantIds)
        && conversation.participantIds.length === 2 && conversation.participantIds.includes(call.callerUid)
        && conversation.participantIds.includes(call.calleeUid), 403, "forbidden");
      requireValue(await contactAllowed(call.callerUid, call.calleeUid, tx), 403, "contact-unavailable");
      if (!call.rtcUidByUser?.[call.callerUid] || !call.rtcUidByUser?.[call.calleeUid]) {
        const rtcUid = (id) => crypto.createHash("sha256").update(id).digest().readUInt32BE(0) % 2147483646 + 1;
        const caller = rtcUid(call.callerUid); let callee = rtcUid(call.calleeUid);
        if (callee === caller) callee = callee === 2147483646 ? 1 : callee + 1;
        call.rtcUidByUser = { [call.callerUid]: caller, [call.calleeUid]: callee };
        tx.update(ref, { rtcUidByUser: call.rtcUidByUser });
      }
      return call;
    });
  }
  async function expireCall(callId) {
    return db.runTransaction(async (tx) => {
      const ref = db.doc(`calls/${callId}`), snap = await tx.get(ref);
      if (!snap.exists) return; const call = snap.data();
      if (call.status !== "ringing" || !Number.isFinite(call.expiresAt) || call.expiresAt > now()) return;
      const conversation = db.doc(`conversations/${call.conversationId}`);
      const history = conversation.collection("messages").doc(`call_${callId}`);
      const parent = await tx.get(conversation), existing = await tx.get(history);
      const profile = await tx.get(db.doc(`publicProfiles/${call.callerUid}`));
      if (!parent.exists) return;
      const time = Math.max(now(), call.updatedAt || 0, parent.data().lastMessageAt || 0);
      const content = `${call.mediaType === "video" ? "📹 Cuộc gọi video" : "📞 Cuộc gọi thoại"} nhỡ`;
      tx.update(ref, { status: "missed", updatedAt: time });
      if (!existing.exists) {
        tx.set(history, { senderId: call.callerUid, senderName: profile.data()?.displayName || "UTEChat",
          type: "text", content, sentAt: time, createdAt: time, readBy: [call.callerUid] });
        tx.update(conversation, { lastMessage: content, lastMessageType: "text", lastMessageAt: time,
          lastMessageId: history.id, lastMessageSenderId: call.callerUid, updatedAt: time });
      }
    });
  }
  function scheduleExpiry(id, expiresAt) {
    if (expiryTimers.has(id) || !Number.isFinite(expiresAt)) return;
    const timer = setTimeout(() => {
      expiryTimers.delete(id); expireCall(id).catch(() => process.stderr.write("Call expiry deferred until next request.\n"));
    }, Math.max(1, Math.min(60_000, expiresAt - now() + 100)));
    timer.unref?.(); expiryTimers.set(id, timer);
  }
  async function sweep() {
    if (now() - lastSweep < 60_000) return; lastSweep = now();
    const calls = await db.collection("calls").where("status", "==", "ringing").limit(100).get();
    for (const doc of calls.docs) {
      if (doc.data().expiresAt <= now()) await expireCall(doc.id); else scheduleExpiry(doc.id, doc.data().expiresAt);
    }
  }
  async function pushOnce(eventId, recipientUid, data, ttl) {
    // Device tokens from older APKs can still exist before the recipient verifies.
    try {
      if (!(await auth.getUser(recipientUid)).emailVerified) return;
    } catch (error) {
      if (error.code === "auth/user-not-found") return;
      throw error;
    }
    const ref = db.doc(`backendEvents/${hash(`${eventId}:${recipientUid}`)}`), attemptId = crypto.randomUUID();
    const claimed = await db.runTransaction(async (tx) => {
      const snapshot = await tx.get(ref), previous = snapshot.data();
      if (previous?.sentAt || previous?.leaseUntil > now()) return false;
      tx.set(ref, { attemptId, leaseUntil: now() + 60_000 }); return true;
    });
    if (!claimed) return;
    try {
      const devices = await db.collection(`users/${recipientUid}/deviceTokens`).limit(20).get();
      const valid = devices.docs.filter((doc) => typeof doc.data().token === "string");
      let acceptedDevices = 0;
      if (valid.length) {
        const result = await messaging.sendEachForMulticast({ tokens: valid.map((doc) => doc.data().token),
          data: { ...data, recipientUid }, android: { priority: "high", ttl } });
        let retry = false;
        for (let i = 0; i < result.responses.length; i++) {
          const response = result.responses[i];
          if (response.success) {
            acceptedDevices++;
            continue;
          }
          if (["messaging/registration-token-not-registered", "messaging/invalid-registration-token"].includes(response.error?.code))
            await valid[i].ref.delete(); else retry = true;
        }
        if (retry) throw new ApiError(503, "push-unavailable");
      }
      requireValue(acceptedDevices > 0, 503, "recipient-device-unavailable");
      await db.runTransaction(async (tx) => {
        const current = await tx.get(ref);
        if (current.data()?.attemptId !== attemptId) return;
        tx.set(ref, { sentAt: now(), acceptedDevices });
      });
    } catch (error) {
      await db.runTransaction(async (tx) => {
        const current = await tx.get(ref); if (current.data()?.attemptId === attemptId) tx.set(ref, { leaseUntil: 0 });
      });
      throw error;
    }
  }
  async function invite(body, uid) {
    requireValue(segment(body.callId)); const snapshot = await db.doc(`calls/${body.callId}`).get();
    requireValue(snapshot.exists, 404, "not-found"); const call = snapshot.data();
    requireValue(call.callerUid === uid, 403, "forbidden"); await member(call.conversationId, uid);
    requireValue(call.status === "ringing" && call.expiresAt > now(), 409, "call-not-ready");
    await member(call.conversationId, call.calleeUid);
    requireValue(await contactAllowed(call.callerUid, call.calleeUid), 403, "contact-unavailable");
    scheduleExpiry(body.callId, call.expiresAt);
    await pushOnce(`call:${body.callId}`, call.calleeUid,
      { eventType: "call", callId: body.callId, conversationId: call.conversationId }, Math.max(1, call.expiresAt - now()));
  }
  async function notifyMessage(body, uid) {
    requireValue(segment(body.conversationId) && segment(body.messageId)); await member(body.conversationId, uid);
    const ref = db.doc(`conversations/${body.conversationId}`);
    const [parent, message] = await Promise.all([ref.get(), ref.collection("messages").doc(body.messageId).get()]);
    requireValue(parent.exists && message.exists, 404, "not-found"); const data = message.data();
    requireValue(data.senderId === uid, 403, "forbidden");
    requireValue(Number.isFinite(data.sentAt) && data.sentAt >= now() - 24 * 60 * 60_000 && data.sentAt <= now() + 60_000, 409, "event-expired");
    const group = parent.data().type === "group";
    let recipients = parent.data().participantIds;
    if (group) {
      const members = await ref.collection("members").limit(101).get();
      requireValue(members.docs.length <= 100, 409, "group-too-large");
      recipients = members.docs.filter(doc => doc.data().userId === doc.id && recipients.includes(doc.id)).map(doc => doc.id);
    }
    let retryError;
    for (const recipient of recipients) {
      if (recipient === uid || !await contactAllowed(uid, recipient)) continue;
      try {
        await pushOnce(`message:${body.conversationId}:${body.messageId}`, recipient, {
        eventType: "message", conversationId: body.conversationId, messageId: body.messageId,
        senderName: String(data.senderName || "UTEChat").slice(0, 80), conversationType: group ? "group" : "direct",
        conversationTitle: group ? String(parent.data().groupName || "Nhóm UTEChat").slice(0, 80) : "",
        preview: data.type === "image" ? "[Hình ảnh]" : String(data.content || "Tin nhắn mới").slice(0, 300),
        }, 24 * 60 * 60_000);
      } catch (error) {
        retryError = error;
      }
    }
    if (retryError) throw retryError;
  }
  async function upload(path, uid, bytes, contentType) {
    requireValue(storage, 503, "media-not-configured"); const parts = mediaPath(path);
    requireValue(parts.ownerUid === uid, 403, "forbidden"); await member(parts.conversationId, uid);
    requireValue(validImage(bytes, contentType), 400, "invalid-image");
    const digest = hash(bytes), ref = db.doc(`backendMedia/${hash(path)}`);
    const quota = db.doc(`backendUsage/${hash(uid)}_${new Date(now()).toISOString().slice(0, 10)}`);
    const existing = await db.runTransaction(async (tx) => {
      const saved = await tx.get(ref), usage = await tx.get(quota);
      const parent = await tx.get(db.doc(`conversations/${parts.conversationId}`));
      requireValue(parent.exists && parent.data().participantIds.includes(uid)
        && (!parent.data().lifecycle || parent.data().lifecycle === "active"), 403, "forbidden");
      if (parent.data().type === "direct") {
        requireValue(await contactAllowed(...parent.data().participantIds, tx), 403, "contact-unavailable");
      }
      if (saved.exists) {
        requireValue(saved.data().sha256 === digest && saved.data().contentType === contentType, 409, "immutable-object"); return saved.data();
      }
      const used = usage.data()?.bytes || 0;
      requireValue(used + bytes.length <= dailyUploadBytes, 429, "daily-upload-limit");
      const record = { path, contentType, size: bytes.length, sha256: digest, ready: false, createdAt: now() };
      tx.set(ref, record); tx.set(quota, { bytes: used + bytes.length }); return record;
    });
    if (!existing.ready) {
      if (!await storage.upload(path, bytes, contentType)) requireValue(hash(await storage.download(path)) === digest, 409, "immutable-object");
      const published = await db.runTransaction(async tx => {
        const parent = await tx.get(db.doc(`conversations/${parts.conversationId}`)), record = await tx.get(ref);
        if (!parent.exists || parent.data().lifecycle === "deleting" || !record.exists) return false;
        tx.update(ref, { ready: true }); return true;
      });
      if (!published) { await storage.remove([path]); throw new ApiError(409, "group-deleting"); }
    }
    return { path, size: bytes.length, contentType };
  }
  async function download(path, uid) {
    requireValue(storage, 503, "media-not-configured"); const parts = mediaPath(path); await member(parts.conversationId, uid);
    const [record, message] = await Promise.all([db.doc(`backendMedia/${hash(path)}`).get(),
      db.doc(`conversations/${parts.conversationId}/messages/${parts.messageId}`).get()]);
    requireValue(record.exists && record.data().ready, 404, "not-found");
    requireValue(uid === parts.ownerUid || (message.exists && message.data().type === "image"
      && message.data().mediaPath === path && message.data().senderId === parts.ownerUid), 403, "forbidden");
    const bytes = await storage.download(path), metadata = record.data();
    requireValue(bytes.length === metadata.size && hash(bytes) === metadata.sha256 && validImage(bytes, metadata.contentType), 502, "invalid-media");
    return { bytes, contentType: metadata.contentType };
  }
  // Short voice notes fit inside one private Firestore document. The client can
  // publish a voice message only after the corresponding server record exists.
  async function uploadVoice(path, uid, bytes) {
    const parts = mediaPath(path);
    requireValue(parts.ownerUid === uid, 403, "forbidden");
    await member(parts.conversationId, uid);
    requireValue(validVoice(bytes), 400, "invalid-voice");
    const ref = db.doc(`backendVoice/${parts.conversationId}/media/${parts.messageId}`);
    const quota = db.doc(`backendUsage/${hash(uid)}_${new Date(now()).toISOString().slice(0, 10)}`);
    const digest = hash(bytes);
    return db.runTransaction(async tx => {
      const [parent, saved, usage] = await Promise.all([
        tx.get(db.doc(`conversations/${parts.conversationId}`)), tx.get(ref), tx.get(quota),
      ]);
      const group = parent.data();
      requireValue(parent.exists && group.participantIds.includes(uid)
        && (!group.lifecycle || group.lifecycle === "active"), 403, "forbidden");
      if (group.type === "direct") {
        requireValue(await contactAllowed(...group.participantIds, tx), 403, "contact-unavailable");
      } else {
        const membership = await tx.get(db.doc(`conversations/${parts.conversationId}/members/${uid}`));
        requireValue(membership.data()?.userId === uid, 403, "forbidden");
      }
      if (saved.exists) {
        requireValue(saved.data().path === path && saved.data().sha256 === digest, 409, "immutable-object");
      } else {
        const used = usage.data()?.bytes || 0;
        requireValue(used + bytes.length <= dailyUploadBytes, 429, "daily-upload-limit");
        tx.set(ref, { path, size: bytes.length, sha256: digest, bytes, createdAt: now() });
        tx.set(quota, { bytes: used + bytes.length });
      }
      return { path, size: bytes.length, contentType: VOICE_TYPE };
    });
  }

  async function downloadVoice(path, uid) {
    const parts = mediaPath(path);
    await member(parts.conversationId, uid);
    const [record, message] = await Promise.all([
      db.doc(`backendVoice/${parts.conversationId}/media/${parts.messageId}`).get(),
      db.doc(`conversations/${parts.conversationId}/messages/${parts.messageId}`).get(),
    ]);
    requireValue(record.exists && record.data().path === path, 404, "not-found");
    requireValue(uid === parts.ownerUid || (message.data()?.type === "voice"
      && message.data().mediaPath === path && message.data().senderId === parts.ownerUid), 403, "forbidden");
    const bytes = Buffer.from(record.data().bytes);
    requireValue(validVoice(bytes) && hash(bytes) === record.data().sha256, 502, "invalid-voice");
    return bytes;
  }
  async function handle(req, res) {
    try {
      const url = new URL(req.url, "http://backend.invalid");
      if (req.method === "GET" && url.pathname === "/healthz") return send(res, 200, { status: "configured", apiVersion: 1, media: !!storage, voice: true });
      const voice = url.pathname === "/api/voice";
      const allowed = (req.method === "POST" && ["/api/rtc-token", "/api/call-invite", "/api/message-notify", "/api/group-delete"].includes(url.pathname))
        || (["GET", "PUT"].includes(req.method) && (url.pathname === "/api/media" || voice));
      requireValue(allowed, 404, "not-found"); const authorization = req.headers.authorization || "";
      requireValue(authorization.startsWith("Bearer ") && authorization.length < 8192, 401, "unauthorized");
      let user;
      try { user = await auth.verifyIdToken(authorization.slice(7), true); } catch (_) { throw new ApiError(401, "unauthorized"); }
      requireValue(user.email_verified === true, 403, "email-unverified");
      rateLimit(user.uid, url.pathname === "/api/media" || voice ? "media" : "api");
      sweep().catch(() => process.stderr.write("Call reconciliation deferred.\n"));
      if (voice) {
        const path = url.searchParams.get("path");
        const parts = mediaPath(path);
        if (req.method === "GET") return send(res, 200, await downloadVoice(path, user.uid), VOICE_TYPE);
        requireValue(parts.ownerUid === user.uid, 403, "forbidden");
        requireValue(req.headers["content-type"] === VOICE_TYPE, 400, "invalid-voice");
        await member(parts.conversationId, user.uid);
        return send(res, 200, await uploadVoice(path, user.uid, await readBody(req, MAX_VOICE_BYTES)));
      }
      if (url.pathname === "/api/media") {
        const path = url.searchParams.get("path"); const parts = mediaPath(path);
        if (req.method === "GET") { const result = await download(path, user.uid); return send(res, 200, result.bytes, result.contentType); }
        requireValue(parts.ownerUid === user.uid, 403, "forbidden"); await member(parts.conversationId, user.uid);
        return send(res, 200, await upload(path, user.uid, await readBody(req, MAX_IMAGE_BYTES), req.headers["content-type"]));
      }
      const body = await readJson(req); requireValue(body && typeof body === "object" && !Array.isArray(body));
      if (url.pathname === "/api/rtc-token") return send(res, 200, mintToken(await acceptedCall(body.callId, user.uid), user.uid));
      if (url.pathname === "/api/group-delete") { await deleteGroup(body.conversationId, user.uid); return send(res, 200, { ok: true }); }
      if (url.pathname === "/api/call-invite") await invite(body, user.uid); else await notifyMessage(body, user.uid);
      return send(res, 200, { ok: true });
    } catch (error) {
      if (!res.headersSent) send(res, error instanceof ApiError ? error.status : 503,
        { error: error instanceof ApiError ? error.message : "service-unavailable" }); else res.destroy();
    }
  }
  return { handle, expireCall, sweep, close() { for (const timer of expiryTimers.values()) clearTimeout(timer); expiryTimers.clear(); } };
}
module.exports = { createBackend, ApiError, mediaPath, validImage, MAX_IMAGE_BYTES };
