import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { collection, doc, getDoc, getDocs, setDoc, updateDoc, writeBatch, query, where, runTransaction } from 'firebase/firestore';
import { deleteObject, getBytes, ref, uploadBytes } from 'firebase/storage';
import { get as getDatabaseValue, ref as databaseRef, set as setDatabaseValue } from 'firebase/database';

const projectId = 'demo-utechat';
const conversationId = 'direct-alice-bob';
const maxImageBytes = 8 * 1024 * 1024;
const firestorePort = Number(process.env.FIRESTORE_EMULATOR_PORT ?? 18080);
const storagePort = Number(process.env.STORAGE_EMULATOR_PORT ?? 19199);
const databasePort = Number(process.env.FIREBASE_DATABASE_EMULATOR_PORT ?? 19000);
let testEnv;

test('read cursors are owner-only, message-backed, monotonic and preserve other members', async () => {
  const alice=testEnv.authenticatedContext('alice').firestore();
  const bob=testEnv.authenticatedContext('bob').firestore();
  const outsider=testEnv.authenticatedContext('mallory').firestore();
  for(const [id,time] of [['read-cursor-new',1000],['read-cursor-old',900]]) {
    await assertSucceeds(setDoc(doc(alice,'conversations',conversationId,'messages',id),{
      senderId:'alice',senderName:'Alice',type:'text',content:'read cursor fixture',sentAt:time,createdAt:time,readBy:['alice'],
    }));
  }
  await assertSucceeds(updateDoc(doc(bob,'conversations',conversationId),{
    'readStates.bob':{messageId:'read-cursor-new',readAt:1000},
  }));
  await assertFails(updateDoc(doc(alice,'conversations',conversationId),{
    'readStates.bob':{messageId:'read-cursor-old',readAt:900},
  }));
  await assertFails(updateDoc(doc(bob,'conversations',conversationId),{
    'readStates.bob':{messageId:'read-cursor-old',readAt:900},
  }));
  await assertFails(updateDoc(doc(bob,'conversations',conversationId),{
    'readStates.bob':{messageId:'read-cursor-new',readAt:999999},
  }));
  await assertFails(updateDoc(doc(bob,'conversations',conversationId),{
    'readStates.bob':{messageId:'missing-message',readAt:1000},
  }));
  await assertFails(updateDoc(doc(outsider,'conversations',conversationId),{
    'readStates.mallory':{messageId:'read-cursor-new',readAt:1000},
  }));
  await assertSucceeds(updateDoc(doc(alice,'conversations',conversationId),{
    'readStates.alice':{messageId:'read-cursor-new',readAt:1000},
  }));
  const states=(await getDoc(doc(bob,'conversations',conversationId))).data().readStates;
  assert.equal(states.bob.messageId,'read-cursor-new');
  assert.equal(states.alice.messageId,'read-cursor-new');
});

test('incoming call fallback remains restricted to participants', async () => {
  const alice = testEnv.authenticatedContext('alice').firestore();
  const bob = testEnv.authenticatedContext('bob').firestore();
  const outsider = testEnv.authenticatedContext('mallory').firestore();
  const now = Date.now();
  await assertSucceeds(setDoc(doc(alice, 'calls/rules-incoming'), {
    conversationId, callerUid: 'alice', calleeUid: 'bob', participants: ['alice', 'bob'],
    channelName: 'rules-incoming', mediaType: 'audio', status: 'ringing',
    createdAt: now, updatedAt: now, expiresAt: now + 45000,
  }));
  const inbox = await assertSucceeds(getDocs(query(collection(bob, 'calls'), where('participants', 'array-contains', 'bob'))));
  assert.ok(inbox.docs.some(row => row.id === 'rules-incoming'));
  await assertFails(getDocs(query(collection(outsider, 'calls'), where('participants', 'array-contains', 'bob'))));
  await assertFails(updateDoc(doc(alice, 'calls/rules-incoming'), { status: 'accepted', updatedAt: now + 1 }));
  await assertSucceeds(updateDoc(doc(bob, 'calls/rules-incoming'), { status: 'accepted', updatedAt: now + 1 }));
});

test('ending a call and its chat history commit atomically once even when both peers finish', async () => {
  const alice = testEnv.authenticatedContext('alice').firestore();
  const bob = testEnv.authenticatedContext('bob').firestore();
  const now = Date.now();
  const callId = 'rules-call-history';
  const call = doc(alice, 'calls', callId);
  await assertSucceeds(setDoc(call, {
    conversationId, callerUid: 'alice', calleeUid: 'bob', participants: ['alice', 'bob'],
    channelName: callId, mediaType: 'audio', status: 'ringing',
    createdAt: now, updatedAt: now, expiresAt: now + 45000,
  }));
  await assertSucceeds(updateDoc(doc(bob, 'calls', callId), { status: 'accepted', updatedAt: now + 1 }));
  const messageId = 'call_' + callId;
  const content = 'Cuộc gọi thoại đã kết thúc';
  const endCall = (db, uid, name) => runTransaction(db, async tx => {
    const ref = doc(db, 'calls', callId);
    const snapshot = await tx.get(ref);
    if (['ended', 'rejected', 'missed', 'failed', 'busy'].includes(snapshot.data().status)) return;
    const timestamp = now + 2;
    tx.set(doc(db, 'conversations', conversationId, 'messages', messageId), {
      senderId: uid, senderName: name, type: 'text', content,
      sentAt: timestamp, createdAt: timestamp, readBy: [uid],
    });
    tx.update(doc(db, 'conversations', conversationId), {
      lastMessage: content, lastMessageType: 'text', lastMessageAt: timestamp,
      lastMessageSenderId: uid, lastMessageId: messageId, updatedAt: timestamp,
    });
    tx.update(ref, { status: 'ended', updatedAt: timestamp });
  }).catch(async error => {
    // Same recovery as the Android repository after a conflicting peer wins.
    const ended = await getDoc(doc(db, 'calls', callId));
    const history = await getDoc(doc(db, 'conversations', conversationId, 'messages', messageId));
    if (ended.data()?.status !== 'ended' || !history.exists()) throw error;
  });
  await assertSucceeds(Promise.all([endCall(alice, 'alice', 'Alice'), endCall(bob, 'bob', 'Bob')]));
  await assertSucceeds(endCall(alice, 'alice', 'Alice'));
  assert.equal((await getDoc(call)).data().status, 'ended');
  assert.equal((await getDoc(doc(bob, 'conversations', conversationId, 'messages', messageId))).data().content, content);
  assert.equal((await getDoc(doc(bob, 'conversations', conversationId))).data().lastMessageId, messageId);
});

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
      type: 'group', participantIds: ['alice', 'bob'],
    });
    await setDoc(doc(db, 'conversations', 'group-alice', 'members', 'alice'), {
      userId: 'alice', role: 'owner', joinedAt: 1, lastReadAt: 1, unreadCount: 0,
    });
    await setDoc(doc(db, 'publicProfiles', 'alice'), { uid: 'alice', displayName: 'Alice' });
    await setDoc(doc(db, 'publicProfiles', 'bob'), { uid: 'bob', displayName: 'Bob' });
    await setDoc(doc(db, 'publicProfiles', 'charlie'), { uid: 'charlie', displayName: 'Charlie' });
    await setDoc(doc(db, 'friendships/alice_charlie'), {
      fromUid: 'alice', toUid: 'charlie', status: 'accepted', createdAt: 1, updatedAt: 1,
    });
    await setDoc(doc(db, 'conversations', 'group-alice', 'members', 'bob'), {
      userId: 'bob', role: 'member', joinedAt: 2, lastReadAt: 2, unreadCount: 0,
    });
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

test('an authenticated user can create a canonical friendship request', async () => {
  const alice = testEnv.authenticatedContext('alice').firestore();
  const request = doc(alice, 'friendships/alice_bob');
  await assertSucceeds(setDoc(request, {
    fromUid: 'alice', toUid: 'bob', status: 'pending', createdAt: 1000, updatedAt: 1000,
  }));
  await assertFails(getDoc(doc(testEnv.authenticatedContext('mallory').firestore(), 'friendships/alice_bob')));
});

test('accepted friends can create a direct conversation without reading a missing document first', async () => {
  const alice = testEnv.authenticatedContext('alice').firestore();
  await assertSucceeds(setDoc(doc(alice, 'conversations/direct-alice-charlie'), {
    type: 'direct', participantIds: ['alice', 'charlie'], createdBy: 'alice',
    createdAt: 1000, updatedAt: 1000, lastMessageAt: 1000,
  }));
});

test('a current group member can list the roster but an outsider cannot', async () => {
  const alice = testEnv.authenticatedContext('alice').firestore();
  const mallory = testEnv.authenticatedContext('mallory').firestore();
  const roster = await assertSucceeds(getDocs(collection(alice, 'conversations/group-alice/members')));
  assert.equal(roster.size, 2);
  await assertFails(getDocs(collection(mallory, 'conversations/group-alice/members')));
});

test('poll votes are limited to the authenticated member’s own choice', async () => {
  const alice = testEnv.authenticatedContext('alice').firestore();
  const bob = testEnv.authenticatedContext('bob').firestore();
  const poll = doc(alice, 'conversations/direct-alice-bob/messages/poll-one');
  const batch = writeBatch(alice);
  batch.set(poll, {
    senderId: 'alice', senderName: 'Alice', type: 'poll', content: 'Lunch?', sentAt: 2000,
    createdAt: 2000, readBy: ['alice'], pollQuestion: 'Lunch?',
    pollOptions: ['Pizza', 'Noodles'], pollVotes: {},
  });
  batch.update(doc(alice, 'conversations/direct-alice-bob'), {
    lastMessage: 'Lunch?', lastMessageType: 'poll', lastMessageAt: 2000,
    lastMessageSenderId: 'alice', lastMessageId: 'poll-one', updatedAt: 2000,
  });
  await assertSucceeds(batch.commit());
  await assertSucceeds(updateDoc(doc(bob, 'conversations/direct-alice-bob/messages/poll-one'), {
    'pollVotes.bob': 1,
  }));
  await assertFails(updateDoc(doc(bob, 'conversations/direct-alice-bob/messages/poll-one'), {
    'pollVotes.alice': 0,
  }));
  await assertFails(updateDoc(doc(testEnv.authenticatedContext('mallory').firestore(),
    'conversations/direct-alice-bob/messages/poll-one'), { 'pollVotes.mallory': 0 }));
  await assertFails(setDoc(doc(alice, 'conversations/direct-alice-bob/messages/poll-bad'), {
    senderId: 'alice', senderName: 'Alice', type: 'poll', content: 'Lunch?', sentAt: 3000,
    createdAt: 3000, readBy: ['alice'], pollQuestion: 'Lunch?',
    pollOptions: ['Pizza'], pollVotes: {},
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
