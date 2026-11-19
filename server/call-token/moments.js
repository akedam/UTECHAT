"use strict";

const MAX_PHOTO_BYTES = 512 * 1024;
const PAGE_SIZE = 16;
const MAX_RECIPIENTS = 100;
const reactions = new Set(["❤️", "😂", "😮", "😢", "👍"]);
const captionStyles = new Set(["accent", "rose", "mint", "rating", "music", "place", "weather", "time"]);
const momentId = (value) => typeof value === "string" && /^[a-f0-9]{64}$/.test(value);

function boundedJpeg(bytes) {
  // Read segment lengths only up to the frame header, without decoding untrusted pixels.
  for (let offset = 2; offset + 4 <= bytes.length;) {
    if (bytes[offset++] !== 0xff) return false;
    while (bytes[offset] === 0xff) offset++;
    const marker = bytes[offset++];
    if (marker === 0xda || marker === 0xd9 || offset + 2 > bytes.length) return false;
    const length = bytes.readUInt16BE(offset);
    if (length < 2 || offset + length > bytes.length) return false;
    if (marker === 0xc0 || marker === 0xc1 || marker === 0xc2) {
      if (length < 8 || bytes[offset + 2] !== 8) return false;
      const height = bytes.readUInt16BE(offset + 3);
      const width = bytes.readUInt16BE(offset + 5);
      return width === height && width > 0 && width <= 960;
    }
    offset += length;
  }
  return false;
}

function createMoments({ db, storage, now, dailyUploadBytes, hash, segment, validImage, requireValue, contactAllowed, pushOnce }) {
  const ref = id => db.doc(`backendMoments/${id}`);
  const photo = id => `moments/${id}.jpg`;
  const box = (uid, view) => db.collection(`users/${uid}/${view === "mine" ? "sentMoments" : "receivedMoments"}`);

  async function isFriend(uid, peer, tx) {
    const pair = [uid, peer].sort().join("_");
    const relation = db.doc(`friendships/${pair}`);
    const friend = (await (tx ? tx.get(relation) : relation.get())).data();
    return friend?.status === "accepted" &&
      ((friend.fromUid === uid && friend.toUid === peer) || (friend.fromUid === peer && friend.toUid === uid)) &&
      await contactAllowed(uid, peer, tx);
  }

  async function canView(record, uid, tx) {
    return record?.state === "ready" && (record.owner === uid ||
      (record.recipients.includes(uid) && await isFriend(uid, record.owner, tx)));
  }

  async function storePhoto(path, bytes, digest) {
    let existing;
    try {
      if (await storage.upload(path, bytes, "image/jpeg")) return;
      existing = await storage.download(path);
    } catch {
      requireValue(false, 503, "moment-storage-unavailable");
    }
    requireValue(Buffer.isBuffer(existing) && hash(existing) === digest, 409, "moment-conflict");
  }

  function viewOf(id, record, uid) {
    const own = record.owner === uid;
    const result = {
      id, owner: record.owner, name: record.name, caption: record.caption, createdAt: record.createdAt,
      captionStyle: record.captionStyle || null,
      own, seen: own || Boolean(record.seen?.[uid]), reaction: record.reacted?.[uid] || null
    };
    if (own) result.recipients = record.recipients.map(id => ({
      id, seen: Boolean(record.seen?.[id]), reaction: record.reacted?.[id] || null
    }));
    return result;
  }

  async function send(uid, input) {
    requireValue(storage, 503, "media-not-configured");
    requireValue(segment(input.nonce) && typeof input.caption === "string" && input.caption.trim().length <= 150 &&
      Array.isArray(input.recipients) && input.recipients.length >= 1 && input.recipients.length <= MAX_RECIPIENTS &&
      input.recipients.every(id => segment(id) && id !== uid) &&
      new Set(input.recipients).size === input.recipients.length &&
      (input.captionStyle == null || captionStyles.has(input.captionStyle)) &&
      typeof input.image === "string" && /^[A-Za-z0-9+/]+={0,2}$/.test(input.image));
    const recipients = [...input.recipients].sort();
    const caption = input.caption.trim();
    const bytes = Buffer.from(input.image, "base64");
    requireValue(bytes.length <= MAX_PHOTO_BYTES && validImage(bytes, "image/jpeg") && boundedJpeg(bytes), 400, "invalid-image");
    const id = hash(`${uid}:${input.nonce}`), digest = hash(bytes), target = ref(id), path = photo(id);
    const fingerprint = hash(JSON.stringify(input.captionStyle
      ? [recipients, caption, digest, input.captionStyle] : [recipients, caption, digest]));
    const createdAt = now();
    const quota = db.doc(`backendUsage/${hash(uid)}_${new Date(createdAt).toISOString().slice(0, 10)}`);
    const draft = await db.runTransaction(async tx => {
      const [existing, account, usage, allowed] = await Promise.all([
        tx.get(target), tx.get(db.doc(`publicProfiles/${uid}`)), tx.get(quota),
        Promise.all(recipients.map(peer => isFriend(uid, peer, tx)))
      ]);
      const previous = existing.data();
      if (previous) {
        requireValue(previous.owner === uid && previous.fingerprint === fingerprint &&
          ["draft", "ready"].includes(previous.state), 409, "moment-conflict");
        if (previous.state === "ready") return previous;
      }
      requireValue(allowed.every(Boolean), 403, "moment-friend-required");
      if (previous) return previous;
      const used = usage.data()?.bytes || 0;
      requireValue(used + bytes.length <= dailyUploadBytes, 429, "daily-upload-limit");
      const record = { owner: uid, name: account.data()?.displayName || "Người dùng UTEChat",
        caption, captionStyle: input.captionStyle || null, recipients, createdAt, state: "draft", fingerprint, digest };
      tx.set(target, record);
      tx.set(quota, { bytes: used + bytes.length });
      return record;
    });

    let published = draft;
    if (draft.state === "draft") {
      await storePhoto(path, bytes, digest);
      published = await db.runTransaction(async tx => {
        const current = (await tx.get(target)).data();
        if (current?.state === "ready") return current;
        if (current?.state !== "draft") return null;
        const allowed = await Promise.all(recipients.map(peer => isFriend(uid, peer, tx)));
        if (!allowed.every(Boolean)) {
          tx.update(target, { state: "discarded" });
          return null;
        }
        tx.update(target, { state: "ready" });
        tx.set(box(uid, "mine").doc(id), { createdAt: current.createdAt });
        for (const peer of recipients) tx.set(box(peer, "received").doc(id), { createdAt: current.createdAt });
        return { ...current, state: "ready" };
      });
      if (!published) await storage.remove([path]).catch(() => {
        process.stderr.write("Could not clean up a rejected Tin upload.\n");
      });
      requireValue(published, 403, "moment-friend-required");
    }
    // A notification failure after the publish transaction must not turn a saved Tin into an error.
    try {
      if ((await target.get()).data()?.state === "ready") await Promise.allSettled(recipients.map(async peer => {
        if (await isFriend(uid, peer)) await pushOnce(`moment:${id}`, peer,
          { eventType: "moment", momentId: id }, 24 * 60 * 60_000);
      }));
    } catch { process.stderr.write("Could not check Tin notification delivery.\n"); }
    return viewOf(id, published, uid);
  }

  async function list(uid, view, before) {
    requireValue(view === "mine" || view === "received");
    let query = box(uid, view).orderBy("createdAt", "desc").orderBy("__name__", "desc");
    if (before) {
      const cursor = /^(\d{1,16}):([a-f0-9]{64})$/.exec(before);
      requireValue(cursor);
      query = query.startAfter(Number(cursor[1]), cursor[2]);
    }
    const { docs } = await query.limit(PAGE_SIZE).get();
    const cards = await Promise.all(docs.map(async pointer => {
      const record = (await ref(pointer.id).get()).data();
      return await canView(record, uid) ? viewOf(pointer.id, record, uid) : null;
    }));
    const last = docs.at(-1);
    return { items: cards.filter(Boolean), next: docs.length === PAGE_SIZE ? `${last.data().createdAt}:${last.id}` : null };
  }

  async function reply(uid, { id, conversationId, nonce, value }) {
    requireValue(momentId(id) && segment(conversationId) && segment(nonce) &&
      typeof value === "string" && value.trim().length > 0 && value.trim().length <= 2000);
    const body = value.trim();
    const conversation = db.doc(`conversations/${conversationId}`);
    const messageId = `tin_${hash(`${uid}:${id}:${nonce}`)}`;
    const message = conversation.collection("messages").doc(messageId);
    await db.runTransaction(async tx => {
      const [story, root, sender, existing] = await Promise.all([
        tx.get(ref(id)), tx.get(conversation), tx.get(db.doc(`publicProfiles/${uid}`)), tx.get(message)
      ]);
      const original = story.data(), chat = root.data();
      if (existing.exists) {
        const previous = existing.data();
        requireValue(previous.senderId === uid && previous.momentId === id && previous.content === body, 409, "moment-conflict");
        return;
      }
      const participants = chat?.participantIds;
      requireValue(original?.owner !== uid && await canView(original, uid, tx), 404, "not-found");
      requireValue(chat?.type === "direct" && (!chat.lifecycle || chat.lifecycle === "active") &&
        Array.isArray(participants) && participants.length === 2 &&
        participants.includes(uid) && participants.includes(original.owner), 403, "forbidden");
      const time = Math.max(now(), (chat.lastMessageAt || 0) + 1);
      tx.set(message, {
        senderId: uid, senderName: sender.data()?.displayName || "UTEChat", type: "text", content: body,
        sentAt: time, createdAt: time, readBy: [uid], momentId: id
      });
      tx.update(conversation, { lastMessage: body, lastMessageType: "text", lastMessageAt: time,
        lastMessageId: messageId, lastMessageSenderId: uid, updatedAt: time });
    });
    return { conversationId, messageId };
  }

  async function get(id, uid) {
    requireValue(momentId(id));
    const record = (await ref(id).get()).data();
    requireValue(await canView(record, uid), 404, "not-found");
    return record;
  }

  async function image(id, uid) {
    requireValue(storage, 503, "media-not-configured");
    const record = await get(id, uid);
    const bytes = await storage.download(photo(id));
    requireValue(bytes && hash(bytes) === record.digest, 502, "invalid-media");
    await get(id, uid);
    return bytes;
  }

  async function respond(uid, input) {
    const { id, action, value } = input;
    requireValue(momentId(id) && ["seen", "react", "report", "hide"].includes(action));
    const target = ref(id);
    return db.runTransaction(async tx => {
      const record = (await tx.get(target)).data();
      requireValue(await canView(record, uid, tx) && record.owner !== uid, 404, "not-found");
      if (action === "hide") {
        tx.delete(box(uid, "received").doc(id));
      } else if (action === "report") {
        requireValue(["spam", "abuse", "other"].includes(value));
        const report = db.doc(`backendMomentReports/${hash(`${uid}:${id}`)}`);
        if (!(await tx.get(report)).exists) tx.set(report, { id, reporter: uid, owner: record.owner, reason: value, createdAt: now() });
      } else if (action === "react") {
        requireValue(value === null || reactions.has(value));
        record.reacted = { ...record.reacted, [uid]: value };
        record.seen = { ...record.seen, [uid]: true };
        tx.update(target, { reacted: record.reacted, seen: record.seen });
      } else if (!record.seen?.[uid]) {
        record.seen = { ...record.seen, [uid]: true };
        tx.update(target, { seen: record.seen });
      }
      return viewOf(id, record, uid);
    });
  }

  async function remove(id, uid) {
    requireValue(momentId(id));
    requireValue(storage?.remove, 503, "media-not-configured");
    const target = ref(id);
    const record = await db.runTransaction(async tx => {
      const current = (await tx.get(target)).data();
      requireValue(current?.owner === uid && ["ready", "deleting", "deleted"].includes(current.state), 404, "not-found");
      if (current.state === "deleted") return null;
      tx.update(target, { state: "deleting" });
      return current;
    });
    if (record) {
      await storage.remove([photo(id)]);
      const batch = db.batch();
      batch.set(target, { owner: uid, state: "deleted", deletedAt: now() });
      batch.delete(box(uid, "mine").doc(id));
      for (const peer of record.recipients) batch.delete(box(peer, "received").doc(id));
      await batch.commit();
      await Promise.allSettled(record.recipients.map(peer => pushOnce(`moment-revoked:${id}`, peer,
        { eventType: "moment-revoked", momentId: id }, 24 * 60 * 60_000)));
    }
    return { ok: true };
  }

  return { send, list, get: async (id, uid) => viewOf(id, await get(id, uid), uid), image, respond, reply, remove };
}

module.exports = { createMoments, MAX_PHOTO_BYTES };
