import { useEffect, useState } from 'react'
import {
  createUserWithEmailAndPassword, reload, sendEmailVerification, sendPasswordResetEmail,
  signInWithEmailAndPassword, signOut, updateProfile
} from 'firebase/auth'
import { ArrowRight, Eye, EyeOff, Mail, MessageCircle, Phone, ShieldCheck } from 'lucide-react'
import { auth } from './firebase'
import { Brand, errorText, IconButton, useLocal } from './ui'

const actionSettings = () => ({
  url: location.hostname === 'localhost' || location.hostname === '127.0.0.1' ? 'https://utechat-ab9d8.web.app/' : location.origin
})
const credentialErrors = ['auth/invalid-credential', 'auth/wrong-password', 'auth/user-not-found']

function verificationError(error) {
  if (['auth/too-many-requests', 'auth/quota-exceeded'].includes(error.code))
    return 'Firebase đang giới hạn số lần gửi email xác minh. Vui lòng chờ rồi thử gửi lại.'
  if (error.code === 'auth/network-request-failed')
    return 'Chưa gửi được yêu cầu xác minh vì mất kết nối. Hãy kiểm tra mạng rồi bấm Gửi lại email xác minh.'
  if (['auth/user-token-expired', 'auth/invalid-user-token', 'auth/user-disabled'].includes(error.code))
    return 'Phiên xác minh không còn hợp lệ. Hãy đăng nhập lại.'
  return 'Chưa gửi được email xác minh. Hãy bấm Gửi lại email xác minh để thử lại.'
}

export function AuthScreen({ user, verify }) {
  const [mode, setMode] = useState('login')
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [visible, showPassword] = useState(false)
  const [feedback, setFeedback] = useState(null)
  const [busy, setBusy] = useState(false)
  const [attempts, setAttempts] = useLocal('utechat.web.signin', { count: 0, waitUntil: 0, last: 0 })
  const [sent, setSent] = useLocal('utechat.web.verify.lastSend', null)
  const [now, setNow] = useState(Date.now())
  const wait = Math.max(0, Math.ceil((attempts.waitUntil - now) / 1000))
  const verifyWait = sent && sent.uid === user?.uid ? Math.max(0, Math.ceil((sent.until - now) / 1000)) : 0
  const changeMode = value => { setMode(value); setFeedback(null); setPassword(''); showPassword(false) }
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [])
  useEffect(() => {
    if (!user) return
    let active = true
    const check = async () => {
      if (document.visibilityState !== 'visible') return
      await reload(user)
      if (user.emailVerified) {
        await user.getIdToken(true)
        if (active && auth.currentUser?.uid === user.uid) verify()
      }
    }
    const refresh = () => check().catch(() => {})
    document.addEventListener('visibilitychange', refresh)
    return () => { active = false; document.removeEventListener('visibilitychange', refresh) }
  }, [user, verify])

  async function act(callback) {
    setBusy(true)
    setFeedback(null)
    try { await callback() } catch (error) {
      setFeedback({ text: errorText(error), type: 'error' })
    } finally { setBusy(false) }
  }
  async function requestVerification(account, nameSaved = true) {
    try {
      auth.languageCode = 'vi'
      await sendEmailVerification(account, actionSettings())
      setSent({ uid: account.uid, until: Date.now() + 60_000 })
      const note = nameSaved ? '' : ' Bạn có thể cập nhật tên hiển thị trong Hồ sơ sau khi xác minh.'
      setFeedback({ text: 'Đã yêu cầu gửi email xác minh. Kiểm tra Hộp thư đến và Thư rác.' + note, type: 'success' })
    } catch (error) {
      setFeedback({ text: verificationError(error), type: 'error' })
    }
  }
  const submit = event => {
    event.preventDefault()
    void act(async () => {
      if (mode === 'register') {
        if (!name.trim()) throw new Error('Nhập tên hiển thị của bạn.')
        const result = await createUserWithEmailAndPassword(auth, email.trim(), password)
        const nameSaved = await updateProfile(result.user, { displayName: name.trim() }).then(() => true, () => false)
        await requestVerification(result.user, nameSaved)
        return
      }
      if (mode === 'reset') {
        try { await sendPasswordResetEmail(auth, email.trim(), actionSettings()) }
        catch (error) { if (error.code !== 'auth/user-not-found') throw error }
        setFeedback({ text: 'Nếu email đã đăng ký, bạn sẽ nhận được liên kết đặt lại mật khẩu.', type: 'success' })
        return
      }
      if (wait) return
      try {
        await signInWithEmailAndPassword(auth, email.trim(), password)
        setAttempts({ count: 0, waitUntil: 0, last: 0 })
      } catch (error) {
        const time = Date.now()
        if (credentialErrors.includes(error.code) || error.code === 'auth/too-many-requests') {
          const count = (time - attempts.last < 15 * 60_000 ? attempts.count : 0) + 1
          const delay = error.code === 'auth/too-many-requests' ? 5 * 60_000 :
            count < 5 ? 0 : Math.min(120_000, 30_000 * 2 ** Math.min(count - 5, 2))
          setAttempts({ count, waitUntil: time + delay, last: time })
        }
        throw error
      }
    })
  }

  return <main className="auth-layout">
    <section className="auth-brand-panel">
      <Brand />
      <div className="auth-intro">
        <div className="auth-art" aria-hidden="true">
          <div className="art-orbit orbit-one" /><div className="art-orbit orbit-two" />
          <img src="/utechat-large.png" alt="" />
          <div className="art-icon art-chat"><MessageCircle size={26} /></div>
          <div className="art-icon art-phone"><Phone size={24} /></div>
        </div>
        <h1>Giữ liên lạc,<br />ở bất cứ đâu.</h1>
        <p>Nhắn tin, gọi video và trò chuyện cùng nhóm bạn trên UTEChat.</p>
      </div>
      <div className="auth-device"><ShieldCheck size={17} /> Dùng chung tài khoản với UTEChat Android</div>
    </section>
    <section className="auth-main">
      <div className="auth-card">
        <div className="mobile-brand"><Brand /></div>
        {user ? <>
          <span className="auth-symbol"><Mail size={28} /></span>
          <h2>Xác minh email</h2>
          <p>Tài khoản cần xác minh địa chỉ <strong>{user.email}</strong>. Nếu đã nhận được email, hãy mở liên kết xác minh trong thư.</p>
          {feedback && <p className={`feedback ${feedback.type}`} role="status">{feedback.text}</p>}
          <button className="primary wide" disabled={busy} onClick={() => act(async () => {
            await reload(user)
            if (!user.emailVerified) throw new Error('Email chưa được xác minh. Hãy mở liên kết trong hộp thư.')
            await user.getIdToken(true)
            verify()
          })}>Tôi đã xác minh</button>
          <button className="secondary wide" disabled={busy || verifyWait > 0}
            onClick={() => act(() => requestVerification(user))}>{verifyWait ? `Gửi lại sau ${verifyWait}s` : 'Gửi lại email xác minh'}</button>
          <button className="text-button wide" disabled={busy}
            onClick={() => signOut(auth).catch(error => setFeedback({ type: 'error', text: errorText(error) }))}>Dùng tài khoản khác</button>
        </> : <>
          <h2>{mode === 'register' ? 'Tạo tài khoản' : mode === 'reset' ? 'Quên mật khẩu' : 'Đăng nhập'}</h2>
          <p>{mode === 'register' ? 'Xác minh email để bắt đầu trò chuyện.' :
            mode === 'reset' ? 'Nhập email đã đăng ký để nhận liên kết đặt lại mật khẩu.' :
              'Chào mừng bạn trở lại với UTEChat.'}</p>
          <form onSubmit={submit} className="stack">
            {mode === 'register' && <label>Tên hiển thị<input value={name} onChange={event => setName(event.target.value)} required maxLength={80} autoComplete="name" placeholder="Tên của bạn" /></label>}
            <label>Email<input type="email" required value={email} onChange={event => setEmail(event.target.value)} autoComplete="email"
              autoCapitalize="none" placeholder="ban@example.com" maxLength={254} /></label>
            {mode !== 'reset' && <label>Mật khẩu<span className="password">
              <input type={visible ? 'text' : 'password'} value={password} onChange={event => setPassword(event.target.value)}
                autoComplete={mode === 'register' ? 'new-password' : 'current-password'} required
                minLength={mode === 'register' ? 6 : undefined} placeholder="Nhập mật khẩu" />
              <IconButton icon={visible ? EyeOff : Eye} label={visible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'} onClick={() => showPassword(!visible)} />
            </span></label>}
            {mode === 'login' && <button className="text-button reset-link" type="button" onClick={() => changeMode('reset')}>Quên mật khẩu?</button>}
            {feedback && <p className={`feedback ${feedback.type}`} role="status">{feedback.text}</p>}
            <button className="primary wide" disabled={busy || (mode === 'login' && wait > 0)} type="submit">
              {busy ? 'Đang xử lý…' : mode === 'register' ? 'Đăng ký' : mode === 'reset' ? 'Gửi liên kết' :
                wait ? `Thử lại sau ${wait}s` : 'Đăng nhập'} {!busy && !wait && <ArrowRight size={17} />}
            </button>
          </form>
          <div className="auth-switch">{mode === 'login' ? 'Bạn mới dùng UTEChat?' : 'Bạn muốn đăng nhập?'}
            <button className="text-button" onClick={() => changeMode(mode === 'login' ? 'register' : 'login')}>
              {mode === 'login' ? 'Tạo tài khoản' : 'Quay lại đăng nhập'}
            </button></div>
        </>}
      </div>
    </section>
  </main>
}
