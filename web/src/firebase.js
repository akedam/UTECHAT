import { initializeApp } from 'firebase/app'
import { connectAuthEmulator, getAuth, inMemoryPersistence, initializeAuth } from 'firebase/auth'
import { connectFirestoreEmulator, getFirestore } from 'firebase/firestore'
import { connectDatabaseEmulator, getDatabase } from 'firebase/database'
import config from './firebase-config.json'

const { webPushPublicKey, ...firebaseConfig } = config
export const pushPublicKey = import.meta.env.VITE_FIREBASE_WEB_PUSH_KEY || webPushPublicKey
const emulator = import.meta.env.DEV && import.meta.env.VITE_USE_EMULATORS === '1'
const emulatorProject = import.meta.env.VITE_EMULATOR_PROJECT || 'demo-utechat'
export const app = initializeApp(emulator ? {
  apiKey: 'emulator', appId: '1:100:web:utechat',
  projectId: emulatorProject, databaseURL: `https://${emulatorProject}-default-rtdb.firebaseio.com`
} : firebaseConfig)
export const auth = getAuth(app)
export const db = getFirestore(app)
export const presenceDb = getDatabase(app)
if (emulator) {
  connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true })
  connectFirestoreEmulator(db, '127.0.0.1', 8080)
  connectDatabaseEmulator(presenceDb, '127.0.0.1', 9000)
}

let smsAuth
export function phoneVerificationAuth() {
  if (smsAuth) return smsAuth
  smsAuth = initializeAuth(initializeApp(app.options, 'utechat-sms-verification'), { persistence: inMemoryPersistence })
  if (emulator) connectAuthEmulator(smsAuth, 'http://127.0.0.1:9099', { disableWarnings: true })
  return smsAuth
}

export const backend = (import.meta.env.VITE_BACKEND_URL ||
  (import.meta.env.DEV ? '' : 'https://utechat-test-backend.onrender.com')).replace(/\/$/, '')

const backendErrors = {
  'group-delete-forbidden': 'Chỉ trưởng nhóm hoặc thành viên cuối cùng có thể xóa nhóm.',
  'not-found': 'Dữ liệu này không còn tồn tại.',
  'phone-session-required': 'Hãy đăng nhập lại bằng số điện thoại để tiếp tục.',
  'rate-limited': 'Bạn thao tác quá nhanh. Hãy thử lại sau.',
  'invalid-voice': 'Trình duyệt tạo bản ghi không đúng định dạng AAC.',
  'contact-blocked': 'Hiện không thể liên hệ tài khoản này.'
}

export async function api(path, { body, method = body ? 'POST' : 'GET', type, blob = false, signal } = {}) {
  if (!auth.currentUser) throw new Error('Bạn cần đăng nhập lại.')
  const token = await auth.currentUser.getIdToken()
  const headers = { Authorization: `Bearer ${token}` }
  if (body) headers['Content-Type'] = type || 'application/json'
  let response
  try {
    response = await fetch(backend + path, {
      method, headers, signal, cache: 'no-store',
      body: body && (type ? body : JSON.stringify(body))
    })
  } catch (error) {
    if (error.name === 'AbortError') throw error
    throw new Error('Chưa kết nối được máy chủ UTEChat. Hãy thử lại.')
  }
  if (!response.ok) {
    const data = await response.json().catch(() => ({}))
    const error = new Error(backendErrors[data.error] || 'Máy chủ chưa xử lý được yêu cầu. Hãy thử lại.')
    error.serverCode = data.error
    error.httpStatus = response.status
    throw error
  }
  return blob ? response.blob() : response.json()
}
