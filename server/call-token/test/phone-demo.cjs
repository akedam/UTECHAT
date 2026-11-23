const http = require("node:http");
const { generateKeyPairSync } = require("node:crypto");
const { initializeApp, cert } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { createBackend } = require("../backend");

const projectId = "demo-utechat";
process.env.FIREBASE_AUTH_EMULATOR_HOST = "127.0.0.1:9099";
process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080";
const privateKey = generateKeyPairSync("rsa", { modulusLength: 2048 })
  .privateKey.export({ format: "pem", type: "pkcs8" });
const app = initializeApp({
  projectId,
  credential: cert({ projectId, clientEmail: "demo@demo-utechat.iam.gserviceaccount.com", privateKey })
});
const backend = createBackend({
  auth: getAuth(app),
  db: getFirestore(app),
  phoneAuthEnabled: true,
  mintToken() { throw new Error("Calls are unavailable in the phone-auth demo."); },
  messaging: { async sendEachForMulticast({ tokens }) { return { responses: tokens.map(() => ({ success: true })) }; } }
});
const server = http.createServer(backend.handle);
server.listen(8879, "127.0.0.1", () => console.log("UTEChat phone demo backend on http://127.0.0.1:8879"));
function stop() {
  backend.close();
  server.close(() => process.exit(0));
}
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
