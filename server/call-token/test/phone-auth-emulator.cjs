const assert = require("node:assert/strict");
const { generateKeyPairSync } = require("node:crypto");
const { initializeApp, cert, deleteApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { createPhoneAuth, isEmailAccount, verifiedAccount } = require("../phone-auth");

const projectId = "demo-utechat";
const authHost = process.env.FIREBASE_AUTH_EMULATOR_HOST;
assert.match(authHost, /^127\.0\.0\.1:\d+$/);
assert.match(process.env.FIRESTORE_EMULATOR_HOST, /^127\.0\.0\.1:\d+$/);
const origin = "http://" + authHost;
const privateKey = generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey.export({ format: "pem", type: "pkcs8" });
const app = initializeApp({ projectId, credential: cert({ projectId, clientEmail: "local@demo-utechat.iam.gserviceaccount.com", privateKey }) });
const auth = getAuth(app), db = getFirestore(app);
const service = createPhoneAuth({ auth, db, now: Date.now, requireValue: (ok, status, error) => {
  if (!ok) throw Object.assign(new Error(error), { status });
} });

async function request(action, fields) {
  const endpoint = origin + "/identitytoolkit.googleapis.com/v1/accounts:" + action + "?key=emulator";
  const response = await fetch(endpoint, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(fields) });
  const payload = await response.json();
  if (!response.ok) throw new Error(action + ": " + payload.error?.message);
  return payload;
}

async function otp(phoneNumber, idToken) {
  const { sessionInfo } = await request("sendVerificationCode", { phoneNumber });
  const response = await fetch(origin + "/emulator/v1/projects/" + projectId + "/verificationCodes");
  const code = (await response.json()).verificationCodes.find(entry => entry.sessionInfo === sessionInfo).code;
  const session = await request("signInWithPhoneNumber", { sessionInfo, code, idToken });
  return auth.verifyIdToken(session.idToken, true);
}

async function session(result) {
  const token = (await request("signInWithCustomToken", { token: result.token, returnSecureToken: true })).idToken;
  const claims = await auth.verifyIdToken(token, true);
  assert.equal(verifiedAccount(claims), true);
  return claims;
}

async function main() {
  try {
    const phone = "+16505550101", oldPassword = "first-password-33", newPassword = "second-password-66";
    const signupProof = await otp(phone);
    assert.equal(verifiedAccount(signupProof), false);
    assert.equal((await session(await service.update("register", signupProof, { password: oldPassword, displayName: "Phone Tester" }))).uid, signupProof.uid);
    assert.equal((await session(await service.login({ phone, password: oldPassword }))).uid, signupProof.uid);
    await new Promise(resolve => setTimeout(resolve, 1100));
    const resetProof = await otp(phone);
    assert.equal((await session(await service.update("reset", resetProof, { password: newPassword }))).uid, signupProof.uid);
    await assert.rejects(service.login({ phone, password: oldPassword }), /invalid-phone-credentials/);
    const credentials = { email: "legacy@example.test", password: "legacy-password-88", returnSecureToken: true };
    const legacy = await request("signUp", credentials);
    await auth.updateUser(legacy.localId, { emailVerified: true });
    const reauth = await request("signInWithPassword", credentials);
    assert.equal(verifiedAccount(await auth.verifyIdToken(reauth.idToken, true)), false);
    const linkProof = await otp("+16505550102", reauth.idToken);
    assert.equal(linkProof.uid, legacy.localId);
    assert.equal(linkProof.firebase.sign_in_provider, "phone");
    await assert.rejects(service.update("register", linkProof, { password: newPassword, displayName: "Legacy" }),
      /email-account-unavailable/);
    await auth.createUser({ email: "email-only@example.test", password: "email-only-password" });
    const emailAccounts = (await auth.listUsers()).users.filter(isEmailAccount);
    assert.equal(emailAccounts.length, 2);
    assert.ok(emailAccounts.some(account => account.uid === legacy.localId));
    for (const account of emailAccounts) await auth.deleteUser(account.uid);
    assert.equal((await session(await service.login({ phone, password: newPassword }))).uid, signupProof.uid);
    assert.equal((await auth.listUsers()).users.length, 1);
    console.log("Firebase Auth emulator passed phone-only signup, login, reset and email-account removal.");
  } finally {
    await db.terminate();
    await deleteApp(app);
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
