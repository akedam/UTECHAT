import {
  collection, deleteDoc, deleteField, doc, endAt, FieldPath, getDoc, getDocs, limit,
  onSnapshot, orderBy, query, runTransaction, serverTimestamp, setDoc, startAfter,
  startAt, updateDoc, where, writeBatch
} from 'firebase/firestore'
import { onDisconnect, onValue, push, ref, remove, serverTimestamp as databaseTime, set } from 'firebase/database'
import { updateProfile } from 'firebase/auth'
import { api, auth, db, presenceDb } from './firebase'

export const reactions = ['❤️', '😆', '😮', '😢', '😡', '👍', '😂', '😍', '🔥', '👏', '🎉', '💯', '🙌', '🙏', '👎']
export const qrPrefix = 'utechat://friend/v1/'
const recallText = 'Tin nhắn đã được thu hồi'
const pageSize = 50
const me = () => auth.currentUser.uid
const root = id => doc(db, 'conversations', id)
const cards = collection(db, 'publicProfiles')
const friends = collection(db, 'friendships')
const pair = (first, second) => [first, second].sort().join('_')
const rows = snap => snap.docs.map(read)
export const read = snap => ({ ...snap.data(), id: snap.id })
const card = value => ({ displayName: value.displayName, photoUrl: value.photoUrl || null })
const member = (id, uid) => doc(root(id), 'members', uid)
export const messageRef = (id, mid) => doc(root(id), 'messages', mid)
export const watch = (path, next, fail) => onSnapshot(path, snap => next(rows(snap), snap), fail)

export async function profile(uid) {
  const snap = await getDoc(doc(cards, uid))
  return snap.exists() ? read(snap) : null
}

export async function ensureProfile(user) {
  const own = await getDoc(doc(db, 'users', user.uid))
  const pub = await profile(user.uid)
  if (own.exists() && pub) return read(own)
  const data = own.data() || pub || user
  return saveProfile({
    displayName: data.displayName || 'Người dùng UTEChat', bio: data.bio || '',
    photoUrl: data.photoUrl || data.photoURL || ''
  })
}

export async function saveProfile({ displayName, bio, photoUrl }) {
  const user = auth.currentUser
  const name = displayName.trim()
  if (!name || name.length > 80) throw new Error('Tên hiển thị phải có từ 1 đến 80 ký tự.')
  const url = photoUrl.trim()
  if (url && (url.length > 2048 || new URL(url).protocol !== 'https:')) {
    throw new Error('Ảnh đại diện cần dùng địa chỉ HTTPS.')
  }
  const now = Date.now()
  const publicData = { uid: user.uid, displayName: name, searchName: name.toLowerCase(), photoUrl: url || null, updatedAt: now }
  const { searchName, ...common } = publicData
  const ownData = { ...common, email: user.email, bio: bio.trim().slice(0, 300) }
  const batch = writeBatch(db)
  batch.set(doc(cards, user.uid), publicData)
  batch.set(doc(db, 'users', user.uid), ownData)
  await batch.commit()
  await updateProfile(user, { displayName: name, photoURL: url || null }).catch(() => {})
  return ownData
}

export const watchProfile = (uid, fn, fail) => onSnapshot(doc(db, 'users', uid), snap => fn(snap.exists() ? read(snap) : null), fail)
export const watchConversations = (uid, fn, fail) => watch(
  query(collection(db, 'conversations'), where('participantIds', 'array-contains', uid)), fn, fail
)
export const watchBlocked = (uid, fn, fail) => watch(collection(db, 'users', uid, 'blocked'), fn, fail)
export const watchHidden = (uid, fn, fail) => watch(collection(db, 'users', uid, 'hiddenMessages'), fn, fail)
export const watchFriendships = (uid, fn, fail) => {
  const parts = [[], []]
  const stops = ['fromUid', 'toUid'].map((field, i) => watch(query(friends, where(field, '==', uid)), list => {
    parts[i] = list
    fn(parts.flat())
  }, fail))
  return () => stops.forEach(stop => stop())
}
export async function searchPeople(value) {
  const start = value.trim().toLowerCase()
  if (!start) return []
  return rows(await getDocs(query(cards, orderBy('searchName'), startAt(start), endAt(start + '\uf8ff'), limit(20))))
    .filter(person => person.id !== me())
}
export function sendFriend(uid) {
  const now = Date.now()
  return setDoc(doc(friends, pair(me(), uid)), { fromUid: me(), toUid: uid, status: 'pending', createdAt: now, updatedAt: now })
}
export const answerFriend = (id, accept) => accept
  ? updateDoc(doc(friends, id), { status: 'accepted', updatedAt: Date.now() })
  : deleteDoc(doc(friends, id))

export async function directId(first, second) {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode([first, second].sort().join(':')))
  return 'direct_' + [...new Uint8Array(bytes)].map(value => value.toString(16).padStart(2, '0')).join('')
}
const initial = now => ({ lastMessage: '', lastMessageType: 'text', lastMessageAt: now,
  createdBy: me(), createdAt: now, updatedAt: now })

export async function openDirect(peer) {
  const uid = me()
  const id = await directId(uid, peer)
  const target = root(id)
  const exists = async () => {
    try {
      const snap = await getDoc(target)
      const data = snap.data()
      return data?.type === 'direct' && data.participantIds.length === 2 &&
        data.participantIds.includes(uid) && data.participantIds.includes(peer)
    } catch (error) {
      if (error.code !== 'permission-denied') throw error
      return false
    }
  }
  if (await exists()) return id
  const [own, other] = await Promise.all([profile(uid), profile(peer)])
  if (!own || !other) throw new Error('Không tìm thấy hồ sơ.')
  try {
    await setDoc(target, { ...initial(Date.now()), type: 'direct', participantIds: [uid, peer],
      participantSummaries: { [uid]: card(own), [peer]: card(other) } })
  } catch (error) {
    if (!await exists()) throw error
  }
  return id
}

const memberData = (uid, role, now) => ({ userId: uid, role, joinedAt: now, lastReadAt: now, unreadCount: 0 })
export async function createGroup(name, people) {
  const title = name.trim()
  const uid = me()
  const selected = [...new Map(people.filter(person => person.id !== uid).map(person => [person.id, person])).values()]
  if (selected.length < 2 || selected.length > 4) throw new Error('Chọn từ 2 đến 4 bạn bè để tạo nhóm.')
  if (!title || title.length > 80) throw new Error('Tên nhóm phải có từ 1 đến 80 ký tự.')
  const owner = await profile(uid)
  const all = [owner, ...selected]
  const now = Date.now()
  const target = doc(collection(db, 'conversations'))
  const batch = writeBatch(db)
  batch.set(target, { ...initial(now), type: 'group', groupName: title,
    ownerUid: uid, isCommunity: false, participantIds: all.map(person => person.id),
    participantSummaries: Object.fromEntries(all.map(person => [person.id, card(person)])) })
  all.forEach((person, i) => batch.set(member(target.id, person.id), memberData(person.id, i ? 'member' : 'owner', now)))
  await batch.commit()
  return target.id
}
export const watchMembers = (id, fn, fail) => watch(collection(root(id), 'members'), fn, fail)
export const renameGroup = (id, groupName) => updateDoc(root(id), { groupName: groupName.trim(), updatedAt: Date.now() })
export const upgradeGroup = id => updateDoc(root(id), { isCommunity: true, updatedAt: Date.now() })
export const deleteGroup = id => api('/api/group-delete', { body: { conversationId: id } })
export const setDeputy = (id, uid, isDeputy) => updateDoc(member(id, uid), { role: isDeputy ? 'admin' : 'member' })
export const transferGroup = (id, uid) => {
  const batch = writeBatch(db)
  batch.update(root(id), { ownerUid: uid, updatedAt: Date.now() })
  batch.update(member(id, me()), { role: 'admin' })
  batch.update(member(id, uid), { role: 'owner' })
  return batch.commit()
}
export function changeMembership(id, uid, addPerson) {
  return runTransaction(db, async tx => {
    const current = (await tx.get(root(id))).data()
    if (!current) throw new Error('Nhóm này không còn tồn tại.')
    const alreadyMember = current.participantIds.includes(uid)
    if (alreadyMember === Boolean(addPerson)) return
    if (addPerson && current.participantIds.length >= (current.isCommunity ? 10 : 5)) {
      throw new Error(current.isCommunity ? 'Cộng đồng đã đủ 10 thành viên.' : 'Nhóm đã đạt thành viên tối đa, hãy nâng lên cộng đồng.')
    }
    const summaries = { ...current.participantSummaries }
    const now = Date.now()
    let ids
    if (addPerson) {
      ids = [...current.participantIds, uid]
      summaries[uid] = card(addPerson)
      tx.set(member(id, uid), memberData(uid, 'member', now), { merge: true })
    } else {
      ids = current.participantIds.filter(value => value !== uid)
      delete summaries[uid]
      tx.delete(member(id, uid))
    }
    tx.update(root(id), { participantIds: ids, participantSummaries: summaries, updatedAt: now,
      membershipChangeUid: uid, membershipChangeAction: addPerson ? 'add' : 'remove' })
  })
}

export const watchMessages = (id, fn, fail) => watch(query(collection(root(id), 'messages'),
  orderBy('sentAt', 'desc'), limit(pageSize)), (list, snapshot) => fn(list, snapshot.docs.at(-1), list.length === pageSize), fail)
export async function olderMessages(id, cursor) {
  const result = await getDocs(query(collection(root(id), 'messages'), orderBy('sentAt', 'desc'), startAfter(cursor), limit(pageSize)))
  return { list: rows(result), cursor: result.docs.at(-1), more: result.size === pageSize }
}
export const watchMessage = (id, mid, next, fail) => onSnapshot(messageRef(id, mid),
  snap => next(snap.exists() ? read(snap) : null), fail)
export function markRead(conversation, message) {
  return runTransaction(db, async tx => {
    const uid = me()
    const previous = (await tx.get(root(conversation))).data()?.readStates?.[uid]
    if (previous?.messageId === message.id || previous?.readAt > message.sentAt) return
    tx.update(root(conversation), new FieldPath('readStates', uid), { messageId: message.id, readAt: message.sentAt })
  })
}

export function messagePreview(message) {
  if (!message) return 'Tin nhắn'
  if (message.type === 'image') return 'Hình ảnh'
  if (message.type === 'voice') return 'Tin nhắn thoại'
  if (message.type === 'poll') return message.pollQuestion || message.content
  return message.content || 'Tin nhắn'
}
export function historyPreview(conversation) {
  return messagePreview({ type: conversation.lastMessageType, content: conversation.lastMessage || 'Chưa có tin nhắn' })
}
export function unread(conversation, uid, hidden) {
  const mark = conversation.readStates?.[uid]
  return Boolean(conversation.lastMessageId && conversation.lastMessageId !== mark?.messageId &&
    conversation.lastMessageSenderId !== uid && !hidden.has(`${conversation.id}:${conversation.lastMessageId}`) &&
    conversation.lastMessageAt >= (mark?.readAt || 0))
}
export function writeSummary(tx, conversation, target, message, uid, now) {
  const content = message.type === 'image' ? 'image' : message.content
  tx.set(target, { ...message, senderId: uid, sentAt: now, createdAt: now, readBy: [uid] })
  tx.update(root(conversation), { lastMessage: content, lastMessageType: message.type, lastMessageAt: now,
    lastMessageId: target.id, lastMessageSenderId: uid, updatedAt: now })
}
function mediaRoute(type, path) {
  return `/api/${type === 'voice' ? 'voice' : 'media'}?path=${encodeURIComponent(path)}`
}
export const downloadMedia = (message, signal) => api(mediaRoute(message.type, message.mediaPath), { blob: true, signal })

export async function sendMessage(conversation, data, reply) {
  const uid = me()
  const target = doc(collection(root(conversation), 'messages'))
  const { blob, durationMs, ...message } = data
  if (blob) {
    const path = `conversations/${conversation}/${uid}/${target.id}`
    const media = await api(mediaRoute(message.type, path), { method: 'PUT', body: blob, type: blob.type })
    Object.assign(message, { mediaPath: media.path, fileSize: media.size, contentType: blob.type })
    if (message.type === 'voice') message.durationMs = durationMs
  }
  await runTransaction(db, async tx => {
    const current = (await tx.get(root(conversation))).data()
    const sender = (await tx.get(doc(cards, uid))).data()
    if (!current || !sender) throw new Error('Không tìm thấy hội thoại.')
    const entry = { ...message, senderName: sender.displayName }
    if (reply && ['text', 'image'].includes(message.type)) {
      const original = (await tx.get(messageRef(conversation, reply.id))).data()
      if (!original || original.type === 'recalled') throw new Error('Tin nhắn được trích dẫn đã bị thu hồi.')
      entry.reply = { id: reply.id, name: original.senderName || '', text: original.content || '', type: original.type }
    }
    writeSummary(tx, conversation, target, entry, uid, Math.max(Date.now(), (current.lastMessageAt || 0) + 1))
  })
  api('/api/message-notify', { body: { conversationId: conversation, messageId: target.id } }).catch(() => {})
  return target.id
}

export const setPin = (id, mid) => updateDoc(root(id), { pinnedMessageId: mid || deleteField() })
export const vote = (id, mid, value) => updateDoc(messageRef(id, mid), new FieldPath('pollVotes', me()), value)
export const react = (id, mid, emoji) => runTransaction(db, async tx => {
  const target = messageRef(id, mid)
  const uid = me()
  const message = (await tx.get(target)).data()
  if (!message || message.type === 'recalled') throw new Error('Tin nhắn đã bị thu hồi.')
  tx.update(target, new FieldPath('reactions', uid), message.reactions?.[uid] === emoji ? deleteField() : emoji)
})
export const hide = (id, mid) => setDoc(doc(db, 'users', me(), 'hiddenMessages', `${id}:${mid}`), { conversationId: id, messageId: mid })
export const recall = (id, mid) => runTransaction(db, async tx => {
  const target = messageRef(id, mid)
  const parent = (await tx.get(root(id))).data()
  const message = (await tx.get(target)).data()
  if (!message || message.type === 'recalled') return
  const { senderId, senderName, sentAt, createdAt, readBy } = message
  const keep = Object.fromEntries(Object.entries({ senderId, senderName, sentAt, createdAt, readBy }).filter(([, value]) => value !== undefined))
  tx.set(target, { ...keep, type: 'recalled', content: recallText })
  const change = {}
  if (parent.lastMessageId === mid) Object.assign(change, { lastMessage: recallText, lastMessageType: 'recalled' })
  if (parent.pinnedMessageId === mid) change.pinnedMessageId = deleteField()
  if (Object.keys(change).length) tx.update(root(id), change)
})
export const blockUser = (uid, block) => {
  const target = doc(db, 'users', me(), 'blocked', uid)
  return block ? setDoc(target, { createdAt: serverTimestamp() }) : deleteDoc(target)
}
export async function reportUser(conversationId, uid, reason, messageId) {
  const target = doc(db, 'userReports', me(), 'targets', uid)
  await runTransaction(db, async tx => {
    if ((await tx.get(target)).exists()) throw new Error('Bạn đã báo cáo tài khoản này.')
    tx.set(target, { conversationId, reason, createdAt: serverTimestamp(), ...(messageId ? { messageId } : {}) })
  })
}

export function watchPresence(uid, callback) {
  return onValue(ref(presenceDb, `presence/${uid}`), snap => callback(snap.val()), () => callback(null))
}
export function setVisibility(uid, visible) {
  return set(ref(presenceDb, `presence/${uid}/visible`), visible)
}
export function startPresence(uid) {
  const connection = push(ref(presenceDb, `presence/${uid}/connections`))
  const lastSeen = ref(presenceDb, `presence/${uid}/lastSeen`)
  let stopped = false
  const stop = onValue(ref(presenceDb, '.info/connected'), async snap => {
    if (!snap.val() || stopped) return
    try {
      await onDisconnect(connection).remove()
      await onDisconnect(lastSeen).set(databaseTime())
      if (!stopped) await set(connection, true)
    } catch (error) {
      console.warn('Trạng thái hoạt động chưa kết nối', error.code)
    }
  })
  return async () => {
    stopped = true
    stop()
    await Promise.allSettled([remove(connection), set(lastSeen, databaseTime())])
  }
}
