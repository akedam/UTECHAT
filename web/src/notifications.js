import { deleteDoc, doc, setDoc } from 'firebase/firestore'
import { app, db, pushPublicKey } from './firebase'

const registration = () => navigator.serviceWorker.register('/sw.js').then(() => navigator.serviceWorker.ready)
const tokenKey = uid => `utechat.web.fcm.${uid}`
const tokenDoc = (uid, id) => doc(db, 'users', uid, 'deviceTokens', id)
const hash = async value => {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('')
}
async function setWorkerAccount(uid) {
  if (!navigator.serviceWorker) return
  const worker = (await registration()).active
  if (!worker) return
  await new Promise(resolve => {
    const channel = new MessageChannel()
    const timer = setTimeout(resolve, 1500)
    channel.port1.onmessage = () => { clearTimeout(timer); resolve() }
    worker.postMessage({ type: 'account', uid }, [channel.port2])
  })
}
export async function configureNotifications(uid, request = false) {
  if (!navigator.serviceWorker || !('Notification' in window) || !('PushManager' in window))
    throw new Error('Hãy thêm UTEChat vào màn hình chính và mở từ biểu tượng để bật thông báo trên iPhone.')
  if (request && await Notification.requestPermission() !== 'granted') throw new Error('Hãy cấp quyền thông báo trong cài đặt trình duyệt.')
  const { getMessaging, getToken, isSupported } = await import('firebase/messaging')
  if (!await isSupported()) throw new Error('Trình duyệt này chưa hỗ trợ thông báo UTEChat.')
  const serviceWorkerRegistration = await registration()
  let token
  try {
    token = await getToken(getMessaging(app), { serviceWorkerRegistration, vapidKey: pushPublicKey })
  } catch (error) {
    if (error.code === 'messaging/token-subscribe-failed' || error.name === 'AbortError')
      throw new Error('Chưa đăng ký được thông báo trên thiết bị này. Hãy mở UTEChat từ màn hình chính rồi thử lại.')
    throw error
  }
  const id = await hash(token)
  const previous = localStorage.getItem(tokenKey(uid))
  await setDoc(tokenDoc(uid, id), { token, platform: 'web', updatedAt: Date.now() })
  if (previous && previous !== id) await deleteDoc(tokenDoc(uid, previous))
  localStorage.setItem(tokenKey(uid), id)
  await setWorkerAccount(uid)
}
export function notificationsEnabled(uid) {
  return Boolean(localStorage.getItem(tokenKey(uid)) && typeof Notification !== 'undefined' && Notification.permission === 'granted')
}
export async function disableNotifications(uid) {
  const id = localStorage.getItem(tokenKey(uid))
  await setWorkerAccount(null)
  if (id) {
    localStorage.removeItem(tokenKey(uid))
    await deleteDoc(tokenDoc(uid, id))
  }
}
export function installWorker() {
  if (import.meta.env.PROD && navigator.serviceWorker) registration().catch(() => {})
}
