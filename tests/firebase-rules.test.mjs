import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc } from 'firebase/firestore';
import { deleteObject, getBytes, ref, uploadBytes } from 'firebase/storage';
import { get as getDatabaseValue, ref as databaseRef, set as setDatabaseValue } from 'firebase/database';

const projectId = 'demo-utechat';
const conversationId = 'direct-alice-bob';
const maxImageBytes = 8 * 1024 * 1024;
const firestorePort = Number(process.env.FIRESTORE_EMULATOR_PORT ?? 18080);
const storagePort = Number(process.env.STORAGE_EMULATOR_PORT ?? 19199);
const databasePort = Number(process.env.FIREBASE_DATABASE_EMULATOR_PORT ?? 19000);
let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId,
    firestore: {
      host: '127.0.0.1',
      port: firestorePort,
      rules: await readFile(new URL('../firestore.rules', import.meta.url), 'utf8'),
    },
    database: {
      host: '127.0.0.1',
      port: databasePort,
      rules: await readFile(new URL('../database.rules.json', import.meta.url), 'utf8'),
    },
    storage: {
      host: '127.0.0.1',
      port: storagePort,
      rules: await readFile(new URL('../storage.rules', import.meta.url), 'utf8'),
    },
  });

  await testEnv.clearFirestore();
  await testEnv.clearStorage();
  await testEnv.clearDatabase();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, 'conversations', conversationId), {
      type: 'direct', participantIds: ['alice', 'bob'],
    });
    await setDoc(doc(db, 'conversations', 'group-alice'), {
      type: 'group', participantIds: ['alice'],
    });
    await setDoc(doc(db, 'conversations', 'group-alice', 'members', 'alice'), {
      userId: 'alice', role: 'owner', joinedAt: 1, lastReadAt: 1, unreadCount: 0,
    });
    await setDoc(doc(db, 'publicProfiles', 'alice'), { uid: 'alice', displayName: 'Alice' });
  });
});

test('Realtime Database presence is owner-writable and opt-in readable', async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDatabaseValue(databaseRef(context.database(), 'presence/alice'), {
      visible: false,
      connections: { phone: true },
      lastSeen: 1234,
    });
  });

  const alice = testEnv.authenticatedContext('alice').database();
  const bob = testEnv.authenticatedContext('bob').database();
  await assertFails(getDatabaseValue(databaseRef(bob, 'presence/alice')));
  await assertFails(setDatabaseValue(databaseRef(bob, 'presence/alice/lastSeen'), 9999));
  await assertSucceeds(setDatabaseValue(databaseRef(alice, 'presence/alice/visible'), true));
  const visiblePresence = await assertSucceeds(getDatabaseValue(databaseRef(bob, 'presence/alice')));
  assert.equal(visiblePresence.child('connections/phone').val(), true);
  await assertSucceeds(setDatabaseValue(databaseRef(alice, 'presence/alice/connections/phone'), null));
  await assertSucceeds(setDatabaseValue(databaseRef(alice, 'presence/alice/lastSeen'), 5678));
});

test('FCM routing tokens are owner-writable but never client-readable', async () => {
  const alice = testEnv.authenticatedContext('alice').firestore();
  const bob = testEnv.authenticatedContext('bob').firestore();
  const token = doc(alice, 'users/alice/deviceTokens/device-hash');
  await assertSucceeds(setDoc(token, {
    token: 'a-valid-length-fcm-registration-token-value', platform: 'android', updatedAt: 1234,
  }));
  await assertFails(getDoc(token));
  await assertFails(getDoc(doc(bob, 'users/alice/deviceTokens/device-hash')));
  await assertFails(setDoc(doc(bob, 'users/alice/deviceTokens/attacker'), {
    token: 'another-valid-length-fcm-registration-token-value', platform: 'android', updatedAt: 1234,
  }));
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

function imageRef(uid, messageId, id = conversationId, ownerUid = uid) {
  return ref(testEnv.authenticatedContext(uid).storage(), `conversations/${id}/${ownerUid}/${messageId}`);
}

function imageMetadata(uid, messageId, id = conversationId, contentType = 'image/jpeg') {
  return {
    contentType,
    customMetadata: { ownerUid: uid, messageId, conversationId: id },
  };
}

test('a conversation participant can upload and download an image; an outsider cannot', async () => {
  const bytes = new Uint8Array([0xff, 0xd8, 0xff, 0x01]);
  const object = imageRef('alice', 'allowed-image');
  await assertSucceeds(uploadBytes(object, bytes, imageMetadata('alice', 'allowed-image')));
  await assertSucceeds(getBytes(imageRef('bob', 'allowed-image', conversationId, 'alice')));
  await assertFails(getBytes(imageRef('mallory', 'allowed-image', conversationId, 'alice')));
  await assertFails(uploadBytes(
    imageRef('mallory', 'outsider-image'), bytes, imageMetadata('mallory', 'outsider-image'),
  ));
  await assertFails(deleteObject(object));
});

test('group image access requires a current member document', async () => {
  const bytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47]);
  await assertSucceeds(uploadBytes(
    imageRef('alice', 'group-image', 'group-alice'),
    bytes,
    imageMetadata('alice', 'group-image', 'group-alice', 'image/png'),
  ));
  await assertFails(getBytes(imageRef('bob', 'group-image', 'group-alice', 'alice')));
});

test('Storage Rules accept exactly 8 MiB and reject one byte over', async () => {
  const exactLimit = new Uint8Array(maxImageBytes);
  await assertSucceeds(uploadBytes(
    imageRef('alice', 'at-limit'), exactLimit, imageMetadata('alice', 'at-limit'),
  ));
  const overLimit = new Uint8Array(maxImageBytes + 1);
  await assertFails(uploadBytes(
    imageRef('alice', 'over-limit'), overLimit, imageMetadata('alice', 'over-limit'),
  ));
});

test('Storage Rules reject unsupported MIME types and incorrect path metadata', async () => {
  const bytes = new Uint8Array([0xff, 0xd8, 0xff]);
  await assertFails(uploadBytes(
    imageRef('alice', 'gif-image'), bytes, imageMetadata('alice', 'gif-image', conversationId, 'image/gif'),
  ));
  await assertFails(uploadBytes(
    imageRef('alice', 'wrong-owner'), bytes, imageMetadata('bob', 'wrong-owner'),
  ));
});

test('Firestore Rules accept an image at 8 MiB and reject invalid type, size, or path', async () => {
  const db = testEnv.authenticatedContext('alice').firestore();
  const base = {
    senderId: 'alice', senderName: 'Alice', type: 'image', content: '', sentAt: 1, createdAt: 1,
    readBy: ['alice'], mediaPath: `conversations/${conversationId}/alice/image-at-limit`,
    contentType: 'image/jpeg', fileSize: maxImageBytes,
  };
  await assertSucceeds(setDoc(doc(db, 'conversations', conversationId, 'messages', 'image-at-limit'), base));
  await assertFails(setDoc(doc(db, 'conversations', conversationId, 'messages', 'image-over-limit'), {
    ...base, mediaPath: `conversations/${conversationId}/alice/image-over-limit`, fileSize: maxImageBytes + 1,
  }));
  await assertFails(setDoc(doc(db, 'conversations', conversationId, 'messages', 'wrong-type'), {
    ...base, mediaPath: `conversations/${conversationId}/alice/wrong-type`, contentType: 'image/gif',
  }));
  await assertFails(setDoc(doc(db, 'conversations', conversationId, 'messages', 'wrong-path'), {
    ...base, mediaPath: `conversations/another-conversation/alice/wrong-path`,
  }));
});
