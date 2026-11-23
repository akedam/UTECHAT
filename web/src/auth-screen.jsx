import { useState } from 'react'
import { MessageCircle, Phone, ShieldCheck } from 'lucide-react'
import { PhoneAccountForm } from './phone-account'
import { Brand } from './ui'

const titles = { login: 'Đăng nhập', register: 'Tạo tài khoản', reset: 'Quên mật khẩu' }

export function AuthScreen({ phoneEnabled }) {
  const [mode, setMode] = useState('login')

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
        <h2>{titles[mode]}</h2>
        {phoneEnabled === false && <p className="feedback error" role="status">Xác thực số điện thoại chưa khả dụng trên máy chủ này.</p>}
        <PhoneAccountForm key={mode} mode={mode} />
        {mode === 'login' && <button className="text-button wide" onClick={() => setMode('reset')}>Quên mật khẩu?</button>}
        <div className="auth-switch">
          <button className="text-button" onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
            {mode === 'login' ? 'Tạo tài khoản' : 'Quay lại đăng nhập'}
          </button>
        </div>
      </div>
    </section>
  </main>
}
