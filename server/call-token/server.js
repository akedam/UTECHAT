"use strict";
const http = require("node:http");
const { initializeApp, applicationDefault } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { RtcTokenBuilder, Role } = require("agora-token");
const { createBackend } = require("./backend");
const { createStorage } = require("./supabase-storage");
const port = Number(process.env.PORT || 8787), projectId = process.env.FIREBASE_PROJECT_ID;
const appId = process.env.AGORA_APP_ID, appCertificate = process.env.AGORA_APP_CERTIFICATE;
if (!projectId || !/^[a-fA-F0-9]{32}$/.test(appId || "") || !/^[a-fA-F0-9]{32}$/.test(appCertificate || ""))
  throw new Error("Configure FIREBASE_PROJECT_ID, AGORA_APP_ID and AGORA_APP_CERTIFICATE on the backend.");
initializeApp({ credential: applicationDefault(), projectId });
const storageKey = process.env.SUPABASE_SECRET_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY;
const storage = process.env.SUPABASE_URL && storageKey
  ? createStorage({ url: process.env.SUPABASE_URL, key: storageKey,
      bucket: process.env.SUPABASE_STORAGE_BUCKET || "utechat-media" }) : null;
const backend = createBackend({ auth: getAuth(), db: getFirestore(), messaging: getMessaging(), storage,
  mintToken(call, uid) {
    const rtcUid = call.rtcUidByUser[uid], seconds = 30 * 60;
    return { appId, token: RtcTokenBuilder.buildTokenWithUid(appId, appCertificate, call.channelName,
      rtcUid, Role.PUBLISHER, seconds, seconds), channelName: call.channelName, uid: rtcUid };
  },
});
const server = http.createServer(backend.handle);
server.requestTimeout = 120_000; server.headersTimeout = 15_000; server.maxHeadersCount = 50;
server.listen(port, "0.0.0.0", () => {
  process.stdout.write(`UTEChat backend listening on ${port}; private media ${storage ? "enabled" : "not configured"}\n`);
  backend.sweep().catch(() => process.stderr.write("Call recovery will retry on authenticated traffic.\n"));
});
process.on("SIGTERM", () => { backend.close(); server.close(() => process.exit(0)); });
