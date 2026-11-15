import { useEffect, useId, useRef, useState } from 'react'
import { ArrowLeft, X } from 'lucide-react'

export const formatTime = value => value ? new Intl.DateTimeFormat('vi-VN', { hour: '2-digit', minute: '2-digit' }).format(value) : ''
export const formatDate = value => value ? new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium' }).format(value) : ''
export const sameDay = (a, b) => a && b && new Date(a).toDateString() === new Date(b).toDateString()
export const listTime = value => sameDay(value, Date.now()) ? formatTime(value) : formatDate(value)

const errors = {
  'permission-denied': 'Bạn chưa có quyền thực hiện thao tác này.',
  unavailable: 'Kết nối bị gián đoạn. Hãy thử lại.',
  'auth/network-request-failed': 'Kết nối bị gián đoạn. Hãy thử lại.',
  'auth/invalid-credential': 'Email hoặc mật khẩu chưa đúng.',
  'auth/user-not-found': 'Email hoặc mật khẩu chưa đúng.',
  'auth/wrong-password': 'Email hoặc mật khẩu chưa đúng.',
  'auth/invalid-email': 'Địa chỉ email chưa đúng định dạng.',
  'auth/email-already-in-use': 'Không thể đăng ký bằng email này. Bạn có thể thử đặt lại mật khẩu.',
  'auth/weak-password': 'Mật khẩu cần ít nhất 6 ký tự.',
  'auth/too-many-requests': 'Firebase yêu cầu chờ vì có quá nhiều lần thử. Vui lòng thử lại sau.',
  NotAllowedError: 'Hãy cho phép dùng micro hoặc camera trong cài đặt trình duyệt.',
  NotFoundError: 'Không tìm thấy micro hoặc camera khả dụng.',
  NotReadableError: 'Micro hoặc camera đang bận. Hãy đóng ứng dụng đang sử dụng thiết bị.'
}
export function errorText(error) {
  return errors[error?.code] || errors[error?.name] ||
    (!error?.code && error?.message && !/^[A-Z]+[A-Z_]*:/.test(error.message) ? error.message : 'Chưa thực hiện được. Hãy thử lại.')
}

export function Brand({ text = true }) {
  return <span className="brand"><img src="/utechat-icon.webp" alt="" />{text && <strong>UTE<span>Chat</span></strong>}</span>
}
export function IconButton({ icon: Icon, label, className = '', ...props }) {
  return <button type="button" className={`icon-button ${className}`} aria-label={label} title={label} {...props}><Icon size={21} strokeWidth={1.8} /></button>
}
export function Avatar({ name, photo, size = 44, group = false, online = false }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => setFailed(false), [photo])
  const initials = (name || 'U').trim().split(/\s+/).slice(-2).map(part => Array.from(part)[0]).join('').toUpperCase()
  const tint = ((name || '').codePointAt(0) || 0) % 4
  return <span className={`avatar tint-${tint}${group ? ' group-avatar' : ''}`} style={{ '--avatar': size + 'px' }} aria-hidden="true">
    {/^https:\/\//i.test(photo || '') && !failed
      ? <img src={photo} referrerPolicy="no-referrer" alt="" onError={() => setFailed(true)} /> : initials}
    {online && <span className="online-dot" />}
  </span>
}
export function Empty({ icon: Icon, title, description, action }) {
  return <div className="empty">
    {Icon && <span className="empty-icon"><Icon size={30} strokeWidth={1.55} /></span>}
    <h3>{title}</h3>{description && <p>{description}</p>}{action}
  </div>
}

export function Modal({ title, children, onClose, className = '', onBack }) {
  const element = useRef(null)
  const id = useId()
  useEffect(() => {
    const dialog = element.current
    if (!dialog.open) dialog.showModal()
    return () => dialog.close()
  }, [])
  return <dialog className={`modal ${className}`} ref={element} aria-labelledby={id}
    onCancel={event => { event.preventDefault(); onClose() }}
    onClick={event => { if (event.target === event.currentTarget) onClose() }}>
    <section className="modal-inner">
      <header className="modal-header">
        {onBack && <IconButton icon={ArrowLeft} label="Quay lại" onClick={onBack} />}
        <h2 id={id}>{title}</h2>
        <IconButton icon={X} label="Đóng" onClick={onClose} />
      </header>
      <div className="modal-content">{children}</div>
    </section>
  </dialog>
}

export function Confirm({ data, onClose, error }) {
  const [busy, setBusy] = useState(false)
  return <Modal title={data.title} onClose={() => !busy && onClose()}>
    <p>{data.description}</p>
    <div className="button-row"><button className="secondary" onClick={onClose} disabled={busy}>Hủy</button>
      <button className={data.danger ? 'danger' : 'primary'} disabled={busy} onClick={async () => {
        setBusy(true)
        try { await data.action(); onClose() } catch (failure) { error(failure); setBusy(false) }
      }}>{data.label || 'Xác nhận'}</button></div>
  </Modal>
}

export function useLocal(key, fallback) {
  const get = () => {
    try { return JSON.parse(localStorage.getItem(key)) ?? fallback } catch { return fallback }
  }
  const [entry, setEntry] = useState(() => ({ key, value: get() }))
  const value = entry.key === key ? entry.value : get()
  return [value, next => {
    const result = typeof next === 'function' ? next(value) : next
    try { localStorage.setItem(key, JSON.stringify(result)) } catch { /* Storage may be disabled in private mode. */ }
    setEntry({ key, value: result })
  }]
}
