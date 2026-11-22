"use strict";

const { createHash, randomBytes, scrypt, timingSafeEqual } = require("node:crypto");
const { promisify } = require("node:util");
const derive = promisify(scrypt);
const scryptOptions = { N: 32768, r: 8, p: 3, maxmem: 48 * 1024 * 1024 };
const dummySalt = Buffer.from("24bff3253c31580db9c04a9d996bb7b0", "hex");
const attemptWindow = 15 * 60_000;
const maxAttempts = 6;
const proofLifetime = 5 * 60;
const validPhone = value => typeof value === "string" && /^\+[1-9]\d{7,14}$/.test(value);

function isEmailAccount(account) {
  return Boolean(account?.email || account?.providerData?.some(
    provider => provider.email || ["password", "google.com"].includes(provider.providerId)));
}

function verifiedAccount(token) {
  return token.firebase?.sign_in_provider === "custom" && token.utechatPhone === true
    && validPhone(token.phone_number) && !token.email;
}

function validPassword(password, minimum = 8) {
  return typeof password === "string" && Array.from(password).length >= minimum && Buffer.byteLength(password) <= 256;
}

async function passwordMatches(password, credential) {
  const salt = credential ? Buffer.from(credential.salt, "hex") : dummySalt;
  const expected = credential ? Buffer.from(credential.hash, "hex") : Buffer.alloc(64);
  const hash = await derive(password, salt, 64, scryptOptions);
  return timingSafeEqual(hash, expected) && !!credential;
}

async function passwordHash(password) {
  const salt = randomBytes(16);
  return { salt: salt.toString("hex"), hash: (await derive(password, salt, 64, scryptOptions)).toString("hex") };
}

function createPhoneAuth({ auth, db, now, requireValue }) {
  const credentials = uid => db.doc(`backendPhoneCredentials/${uid}`);
  const attempts = phone => db.doc(`backendPhoneAttempts/${createHash("sha256").update(phone).digest("hex")}`);

  async function mint(account) {
    if (account.customClaims?.utechatPhone !== true) {
      await auth.setCustomUserClaims(account.uid, { ...account.customClaims, utechatPhone: true });
    }
    return { token: await auth.createCustomToken(account.uid, { utechatPhone: true }) };
  }

  async function login({ phone, password }) {
    requireValue(validPhone(phone) && validPassword(password, 1), 400, "invalid-phone-credentials");
    // Count attempts before the password check, including concurrent and unknown-account requests.
    const attempt = attempts(phone);
    const window = await db.runTransaction(async tx => {
      const saved = (await tx.get(attempt)).data();
      const time = now();
      const current = saved?.until > time ? saved : { count: 0, until: time + attemptWindow };
      requireValue(current.count < maxAttempts, 429, "phone-login-rate-limited");
      tx.set(attempt, { ...current, count: current.count + 1 });
      return current.until;
    });
    const account = await auth.getUserByPhoneNumber(phone).catch(error => {
      if (error.code === "auth/user-not-found") return null;
      throw error;
    });
    const record = account && (await credentials(account.uid).get()).data();
    const stored = record?.phone === phone ? record : null;
    requireValue(await passwordMatches(password, stored) && !account.disabled && !isEmailAccount(account),
      401, "invalid-phone-credentials");
    const result = await mint(account);
    await db.runTransaction(async tx => {
      const credential = (await tx.get(credentials(account.uid))).data();
      const currentAttempt = (await tx.get(attempt)).data();
      requireValue(credential?.hash === record.hash, 401, "invalid-phone-credentials");
      if (currentAttempt?.until === window) tx.delete(attempt);
    });
    return result;
  }

  function isRecent(token) {
    const time = Math.floor(now() / 1000);
    return Number.isInteger(token.auth_time) && token.auth_time <= time + 30 && token.auth_time >= time - proofLifetime;
  }

  async function proof(token) {
    const phone = token.phone_number;
    requireValue(token.firebase?.sign_in_provider === "phone" && validPhone(phone) && isRecent(token),
      403, "fresh-phone-proof-required");
    const account = await auth.getUser(token.uid);
    requireValue(!account.disabled && account.phoneNumber === phone, 403, "fresh-phone-proof-required");
    return account;
  }

  async function update(mode, token, { password, displayName }) {
    requireValue(validPassword(password), 400, "phone-password-policy");
    const account = await proof(token);
    requireValue(!isEmailAccount(account), 409, "email-account-unavailable");
    const ref = credentials(account.uid);
    if (mode === "reset") {
      const record = (await ref.get()).data();
      requireValue(record?.phone === account.phoneNumber, 404, "phone-account-unavailable");
      requireValue(token.auth_time > record.otpAt, 409, "fresh-phone-proof-required");
      for (const recent of [record, ...(record.previous || [])]) {
        requireValue(!await passwordMatches(password, recent), 400, "phone-password-reused");
      }
      const next = await passwordHash(password);
      await db.runTransaction(async tx => {
        const current = (await tx.get(ref)).data();
        requireValue(current?.hash === record.hash && current.phone === account.phoneNumber
          && token.auth_time > current.otpAt, 409, "fresh-phone-proof-required");
        const previous = [{ salt: record.salt, hash: record.hash }, ...(record.previous || [])].slice(0, 4);
        tx.set(ref, { ...next, previous, phone: account.phoneNumber, otpAt: token.auth_time });
        tx.delete(attempts(account.phoneNumber));
      });
      await auth.revokeRefreshTokens(account.uid);
      return mint(account);
    }

    const name = typeof displayName === "string" ? displayName.trim() : "";
    requireValue(name.length > 0 && name.length <= 80 && !/\p{Cc}/u.test(name), 400, "invalid-display-name");
    const next = await passwordHash(password);
    const record = await db.runTransaction(async tx => {
      const previous = (await tx.get(ref)).data();
      if (previous) return previous;
      const data = { ...next, previous: [], phone: account.phoneNumber, otpAt: token.auth_time };
      const card = { uid: account.uid, displayName: name, photoUrl: null, updatedAt: now() };
      tx.set(db.doc(`users/${account.uid}`), { ...card, email: "", bio: "" });
      tx.set(db.doc(`publicProfiles/${account.uid}`), { ...card, searchName: name.toLowerCase() });
      tx.set(ref, data);
      return data;
    });
    // An identical retry after a network error can obtain a session without overwriting the account.
    const created = record.hash === next.hash;
    requireValue(record.phone === account.phoneNumber && (created || await passwordMatches(password, record)),
      409, "phone-account-exists");
    if (!account.displayName) {
      const profile = await db.doc(`users/${account.uid}`).get();
      await auth.updateUser(account.uid, { displayName: profile.data().displayName });
    }
    return mint(account);
  }

  return { login, update };
}

module.exports = { createPhoneAuth, isEmailAccount, verifiedAccount };
