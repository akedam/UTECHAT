"use strict";

const crypto = require("node:crypto");
const http = require("node:http");
const { initializeApp, applicationDefault } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { RtcTokenBuilder, Role } = require("agora-token");

const port = Number(process.env.PORT || 8787);
const projectId = process.env.FIREBASE_PROJECT_ID;
const appId = process.env.AGORA_APP_ID;
const appCertificate = process.env.AGORA_APP_CERTIFICATE;
if (!projectId || !/^[a-fA-F0-9]{32}$/.test(appId || "") || !/^[a-fA-F0-9]{32}$/.test(appCertificate || "")) {
  throw new Error("Configure real FIREBASE_PROJECT_ID, AGORA_APP_ID, and AGORA_APP_CERTIFICATE. Run configure.ps1 first; never use placeholder credentials.");
}

initializeApp({ credential: applicationDefault(), projectId });
const auth = getAuth();
const db = getFirestore();

function send(res, status, body) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}

function rtcUidFor(uid) {
  // Stable positive 31-bit UID. The call transaction checks and resolves the
  // (very unlikely) collision before saving IDs in that call document.
  return crypto.createHash("sha256").update(uid).digest().readUInt32BE(0) % 2147483646 + 1;
}

async function readJson(req) {
  let body = "";
  for await (const chunk of req) {
    body += chunk;
    if (body.length > 4096) throw new Error("too-large");
  }
  return JSON.parse(body || "{}");
}

async function issueToken(req, res) {
  if (req.method === "GET" && req.url === "/healthz") return send(res, 200, { status: "configured" });
  if (req.method !== "POST" || req.url !== "/api/rtc-token") return send(res, 404, { error: "not-found" });
  const authorization = req.headers.authorization || "";
  if (!authorization.startsWith("Bearer ")) return send(res, 401, { error: "unauthorized" });
  let decoded;
  try { decoded = await auth.verifyIdToken(authorization.slice(7), true); }
  catch (_) { return send(res, 401, { error: "unauthorized" }); }

  let body;
  try { body = await readJson(req); }
  catch (_) { return send(res, 400, { error: "invalid-request" }); }
  if (typeof body.callId !== "string" || !/^[A-Za-z0-9_-]{10,128}$/.test(body.callId))
    return send(res, 400, { error: "invalid-request" });

  try {
    const ref = db.collection("calls").doc(body.callId);
    const call = await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (!snap.exists) throw new Error("not-found");
      const data = snap.data();
      if (!Array.isArray(data.participants) || !data.participants.includes(decoded.uid)
          || ![data.callerUid, data.calleeUid].includes(decoded.uid)) throw new Error("forbidden");
      if (data.status !== "accepted") throw new Error("not-ready");
      if (data.channelName !== body.callId) throw new Error("invalid-call");
      const ids = data.rtcUidByUser || {};
      if (!ids[data.callerUid] || !ids[data.calleeUid]) {
        const callerRtcUid = rtcUidFor(data.callerUid);
        let calleeRtcUid = rtcUidFor(data.calleeUid);
        if (calleeRtcUid === callerRtcUid) calleeRtcUid = calleeRtcUid === 2147483646 ? 1 : calleeRtcUid + 1;
        const assigned = { [data.callerUid]: callerRtcUid, [data.calleeUid]: calleeRtcUid };
        tx.update(ref, { rtcUidByUser: assigned });
        return { ...data, rtcUidByUser: assigned };
      }
      return data;
    });
    const uid = call.rtcUidByUser[decoded.uid];
    const expiresSeconds = 30 * 60;
    const token = RtcTokenBuilder.buildTokenWithUid(
      appId, appCertificate, call.channelName, uid,
      Role.PUBLISHER, expiresSeconds, expiresSeconds);
    return send(res, 200, { appId, token, channelName: call.channelName, uid });
  } catch (error) {
    const code = error.message === "forbidden" ? 403 : error.message === "not-found" ? 404 : 409;
    return send(res, code, { error: code === 403 ? "forbidden" : "call-not-ready" });
  }
}

http.createServer((req, res) => {
  issueToken(req, res).catch(() => send(res, 500, { error: "internal-error" }));
}).listen(port, "0.0.0.0", () => {
  process.stdout.write(`UTEChat token endpoint listening on ${port}\n`);
});
