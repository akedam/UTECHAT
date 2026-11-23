import { useEffect, useRef, useState } from 'react'
import { RecaptchaVerifier, signInWithCustomToken, signInWithPhoneNumber, signOut } from 'firebase/auth'
import { auth, backend, phoneVerificationAuth } from './firebase'
import { normalizePhoneNumber } from './phone-number'

const failures = {
  'invalid-phone-credentials': 'Số điện thoại hoặc mật khẩu chưa đúng.',
  'phone-login-rate-limited': 'Bạn đã thử quá nhiều lần. Hãy chờ rồi đăng nhập lại.',
  'phone-password-policy': 'Dùng mật khẩu từ 8 ký tự, tối đa 256 byte.',
  'phone-password-reused': 'Mật khẩu mới trùng với một mật khẩu gần đây.',
  'phone-account-exists': 'Số này đã có tài khoản. Hãy đăng nhập hoặc khôi phục mật khẩu.',
  'email-account-unavailable': 'Tài khoản đăng ký bằng email không dùng được với đăng nhập số điện thoại.',
  'phone-account-unavailable': 'Số này chưa được thiết lập để đăng nhập UTEChat.',
  'fresh-phone-proof-required': 'Phiên xác minh đã hết hạn. Hãy gửi mã OTP mới.'
}

export async function phoneAuthAvailable(signal) {
  const response = await fetch(backend + '/api/phone-auth/status', { cache: 'no-store', signal })
  return response.ok && (await response.json()).enabled === true
}

function phoneNumber(input) {
  const number = normalizePhoneNumber(input)
  if (!number) throw new Error('Nhập số di động hợp lệ, ví dụ 0912345678 hoặc +84912345678.')
  return number
}

async function session(action, body, proof) {
  const headers = { 'Content-Type': 'application/json' }
  if (proof) headers.Authorization = `Bearer ${await proof.getIdToken(true)}`
  const response = await fetch(backend + '/api/phone-auth/' + action, {
    method: 'POST', headers, body: JSON.stringify(body), cache: 'no-store'
  })
  const data = await response.json().catch(() => ({}))
  if (!response.ok || typeof data.token !== 'string') {
    const error = new Error(failures[data.error] || 'Xác thực số điện thoại chưa khả dụng. Hãy thử lại sau.')
    error.serverCode = data.error
    throw error
  }
  return signInWithCustomToken(auth, data.token)
}

export function PhoneAccountForm({ mode, user, onDone }) {
  const [busy, setBusy] = useState(false)
  const [feedback, setFeedback] = useState(null)
  const [codeSession, setCodeSession] = useState(null)
  const [lockedPhone, setLockedPhone] = useState('')
  const [retryAt, setRetryAt] = useState(0)
  const [now, setNow] = useState(Date.now())
  const form = useRef(null)
  const captchaRegion = useRef(null)
  const captcha = useRef(null)
  const mounted = useRef(true)
  const register = mode === 'register'
  const login = mode === 'login'
  const change = mode === 'change'
  const wait = Math.max(0, Math.ceil((retryAt - now) / 1000))
  useEffect(() => {
    mounted.current = true
    const interval = setInterval(() => setNow(Date.now()), 1000)
    return () => {
      mounted.current = false
      clearInterval(interval)
      captcha.current?.clear()
    }
  }, [])

  async function run(task) {
    if (busy) return
    setBusy(true)
    setFeedback(null)
    try { await task() }
    catch (error) {
      if (!mounted.current) return
      if (error.serverCode === 'phone-login-rate-limited' || error.code === 'auth/too-many-requests') setRetryAt(Date.now() + 60_000)
      const text = error.code === 'auth/invalid-verification-code' ? 'Mã OTP chưa đúng.' :
        error.code === 'auth/code-expired' ? 'Mã OTP đã hết hạn. Hãy gửi lại mã.' :
            error.code?.startsWith('auth/') ? 'Firebase chưa xác minh được số điện thoại. Hãy thử lại.' : error.message
      setFeedback({ text, error: true })
    } finally { if (mounted.current) setBusy(false) }
  }

  async function sendCode(fields) {
    if (wait) return
    const number = change ? user.phoneNumber : phoneNumber(fields.get('phone'))
    if (!await phoneAuthAvailable()) throw new Error('Xác thực số điện thoại chưa khả dụng. Hãy thử lại sau.')
    const verifier = phoneVerificationAuth()
    if (!captcha.current) captcha.current = new RecaptchaVerifier(verifier, captchaRegion.current, { size: 'invisible' })
    try {
      setCodeSession(await signInWithPhoneNumber(verifier, number, captcha.current))
      setLockedPhone(number)
      setRetryAt(Date.now() + 60_000)
      setFeedback({ text: `Đã gửi mã OTP tới ${number}.` })
    } catch (error) {
      captcha.current.clear()
      captcha.current = null
      throw error
    }
  }

  async function submit(event) {
    event.preventDefault()
    const fields = new FormData(event.currentTarget)
    await run(async () => {
      const number = change ? user.phoneNumber : phoneNumber(fields.get('phone'))
      const password = fields.get('password')
      if (new TextEncoder().encode(password).length > 256 || (!login && [...password].length < 8)) throw new Error('Dùng mật khẩu từ 8 ký tự, tối đa 256 byte.')
      if (!login && password !== fields.get('confirm')) throw new Error('Hai mật khẩu chưa khớp.')
      if (login) { await session('login', { phone: number, password }); onDone?.(); return }
      if (!codeSession || lockedPhone !== number) throw new Error('Hãy yêu cầu mã OTP cho số điện thoại này.')
      if (register && !fields.get('name').trim()) throw new Error('Hãy nhập tên hiển thị.')
      const proof = (await codeSession.confirm(fields.get('otp'))).user
      try {
        if (change && proof.uid !== user.uid) throw new Error('Số điện thoại không khớp với tài khoản hiện tại.')
        await session(register ? 'register' : 'reset', { password, displayName: fields.get('name') }, proof)
        onDone?.()
      } finally {
        await signOut(phoneVerificationAuth()).catch(() => {})
        if (mounted.current) setCodeSession(null)
      }
    })
  }

  return <form ref={form} className="stack" onSubmit={submit}>
    {register && <label>Tên hiển thị<input name="name" required maxLength={80} autoComplete="name" disabled={busy} /></label>}
    <label>Số điện thoại<input name="phone" type="tel" autoComplete="tel" required maxLength={24}
      defaultValue={user?.phoneNumber || ''} readOnly={change || Boolean(lockedPhone)} disabled={busy} /></label>
    <label>{login || register ? 'Mật khẩu' : 'Mật khẩu mới'}<input name="password" type="password" required autoComplete={login ? 'current-password' : 'new-password'} disabled={busy} /></label>
    {!login && <>
      <label>Nhập lại mật khẩu<input name="confirm" type="password" autoComplete="new-password" required disabled={busy} /></label>
      <div ref={captchaRegion} />
      <button type="button" className="secondary wide" disabled={busy || wait > 0} onClick={() => {
        const fields = new FormData(form.current)
        run(() => sendCode(fields))
      }}>
        {wait ? `Gửi lại sau ${wait}s` : codeSession ? 'Gửi lại mã OTP' : 'Gửi mã OTP'}
      </button>
      {codeSession && <label>Mã OTP<input name="otp" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required disabled={busy} /></label>}
    </>}
    {feedback && <p className={`feedback ${feedback.error ? 'error' : 'success'}`} role="status">{feedback.text}</p>}
    <button className="primary wide" disabled={busy || (login ? wait > 0 : !codeSession)}>
      {busy ? 'Đang xử lý…' : login ? (wait ? `Thử lại sau ${wait}s` : 'Đăng nhập') : 'Xác minh và tiếp tục'}
    </button>
  </form>
}
