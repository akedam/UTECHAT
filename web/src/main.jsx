import { useCallback, useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { onAuthStateChanged, sendPasswordResetEmail, signOut } from 'firebase/auth'
import {
  ArrowRight, BellOff, Check, ChevronRight, CircleAlert, ContactRound, Image as ImageIcon,
  MessageCircle, MessageSquare, Pin, Plus, QrCode, Search, Star, UsersRound, UserRound, X
} from 'lucide-react'
import { auth, backend } from './firebase'
import { useCall, useIncomingTone } from './calls'
import {
  downloadMedia, ensureProfile, historyPreview, openDirect, profile, reactions, saveProfile, startPresence,
  unread, watchBlocked, watchConversations, watchFriendships, watchHidden, watchPresence, watchProfile
} from './chat-data'
import { configureNotifications, disableNotifications, installWorker, notificationsEnabled } from './notifications'
import { AuthScreen } from './auth-screen'
import { BlockedList, Community, CreateGroup, Friends, GroupSettings, Profile, QrDialog, SearchDialog } from './people'
import { CallOverlay, Chat, ChatOptions, ForwardDialog, Gallery, MessageActions, PeerProfile, PollDialog, ReportDialog } from './chat'
import { Avatar, Brand, Confirm, Empty, errorText, IconButton, listTime, Modal, useLocal } from './ui'
import './styles.css'

const tabs = [
  { key: 'messages', label: 'Tin nhắn', icon: MessageCircle },
  { key: 'friends', label: 'Bạn bè', icon: ContactRound },
  { key: 'community', label: 'Cộng đồng', icon: UsersRound },
  { key: 'profile', label: 'Hồ sơ', icon: UserRound }
]
const readChat = () => new URLSearchParams(location.search).get('chat') || ''
const hiddenSet = list => new Set(list.map(row => row.id))
const onIos = () => /iPhone|iPad|iPod/.test(navigator.userAgent) || navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1
installWorker()

function App() {
  const [identity, setIdentity] = useState(undefined)
  const refresh = useCallback(() => {
    const user = auth.currentUser
    setIdentity(user && { user, verified: user.emailVerified })
  }, [])
  useEffect(() => onAuthStateChanged(auth, refresh), [refresh])
  if (identity === undefined) return <div className="splash" role="status"><Brand /><span className="spinner" /><span className="sr-only">Đang mở UTEChat</span></div>
  return identity?.verified ? <Workspace key={identity.user.uid} user={identity.user} /> :
    <AuthScreen user={identity?.user} verify={refresh} />
}

function ChatList({ sorted, selected, choose, titleOf, uid, hidden, drafts, preferences, openSearch }) {
  if (!sorted.length) return <Empty icon={MessageCircle} title="Chưa có cuộc trò chuyện"
    description="Tìm bạn bè để gửi tin nhắn đầu tiên."
    action={<button className="secondary" onClick={openSearch}><Search size={17} /> Tìm bạn bè</button>} />
  return <div className="conversation-list">{sorted.map(row => {
    const { name, photo } = titleOf(row)
    const isNew = unread(row, uid, hidden)
    const draft = drafts[row.id]?.trim()
    const preview = hidden.has(`${row.id}:${row.lastMessageId}`) ? 'Tin nhắn đã xóa ở phía bạn' : historyPreview(row)
    return <button className={`conversation-row ${selected === row.id ? 'selected' : ''} ${isNew ? 'unread' : ''}`}
      data-unread={isNew} key={row.id} onClick={() => choose(row.id)} aria-current={selected === row.id ? 'true' : undefined}>
      <Avatar name={name} photo={photo} size={48} group={row.type === 'group'} />
      <span className="row-text"><strong>{name}</strong><small>{draft ? <><b>Bản nháp</b> <span>{draft}</span></> : preview}</small></span>
      <span className="row-meta"><time>{row.lastMessage ? listTime(row.lastMessageAt) : ''}</time>
        <span>{preferences[row.id]?.pinned ? <Pin size={13} /> : preferences[row.id]?.favorite && <Star size={13} />}
          {preferences[row.id]?.muted && <BellOff size={13} />}
          {isNew && <i className="unread-dot" aria-label="Chưa đọc" />}</span></span>
    </button>
  })}</div>
}

function Toast({ toast, clear }) {
  const element = useRef(null)
  useEffect(() => {
    if (!toast) return
    const popup = element.current
    if (popup?.showPopover) {
      if (popup.matches(':popover-open')) popup.hidePopover()
      popup.showPopover()
    }
    const delay = setTimeout(clear, 5000)
    return () => clearTimeout(delay)
  }, [toast, clear])
  if (!toast) return null
  return <div className={`toast ${toast.type}`} ref={element} {...('showPopover' in HTMLElement.prototype ? { popover: 'manual' } : {})}
    role={toast.type === 'error' ? 'alert' : 'status'}>
    {toast.type === 'error' ? <CircleAlert size={19} /> : <Check size={19} />} <span>{toast.message}</span>
    <IconButton icon={X} label="Đóng thông báo" onClick={clear} />
  </div>
}

function Workspace({ user }) {
  const [tab, setTab] = useState('messages')
  const [selected, setSelected] = useState(readChat)
  const [dialogs, setDialog] = useState(null)
  const [confirmation, setConfirmation] = useState(null)
  const [toast, setToast] = useState(null)
  const [own, setOwn] = useState(null)
  const [loadError, setLoadError] = useState(false)
  const [loadAttempt, setLoadAttempt] = useState(0)
  const [conversations, setConversations] = useState([])
  const [loadedChats, setLoadedChats] = useState(false)
  const [friendships, setFriendships] = useState([])
  const [people, setPeople] = useState({})
  const [blocked, setBlocked] = useState([])
  const [hidden, setHidden] = useState(new Set())
  const [presence, setPresence] = useState(null)
  const [drafts, setDrafts] = useLocal(`utechat.web.draft.${user.uid}`, {})
  const [preferences, setPreferences] = useLocal(`utechat.web.conversation.${user.uid}`, {})
  const releasePresence = useRef(null)
  const seenMessages = useRef(null)
  const notify = useCallback(message => setToast({ message, type: 'success' }), [])
  const error = useCallback(failure => setToast({ message: errorText(failure), type: 'error' }), [])
  const closeToast = useCallback(() => setToast(null), [])
  const close = () => setDialog(null)
  const setChat = useCallback((id, replace = false) => {
    const next = new URL(location.href)
    if (id) next.searchParams.set('chat', id)
    else next.searchParams.delete('chat')
    if (next.toString() !== location.href) history[replace ? 'replaceState' : 'pushState'](null, '', next)
    setSelected(id)
    if (id) setTab('messages')
  }, [])
  useEffect(() => {
    const onPop = () => { setSelected(readChat()); if (readChat()) setTab('messages'); setDialog(null) }
    const onWorker = event => {
      if (event.data?.type === 'conversation' && event.data.uid === user.uid) setChat(event.data.id)
    }
    window.addEventListener('popstate', onPop)
    navigator.serviceWorker?.addEventListener('message', onWorker)
    return () => { window.removeEventListener('popstate', onPop); navigator.serviceWorker?.removeEventListener('message', onWorker) }
  }, [user.uid, setChat])

  useEffect(() => {
    let done = false
    let stop = () => {}
    ensureProfile(user).then(data => {
      if (done) return
      setOwn(data)
      stop = watchProfile(user.uid, value => value && setOwn(value), error)
    }).catch(failure => { if (!done) { setLoadError(true); error(failure) } })
    return () => { done = true; stop() }
  }, [user, error, loadAttempt])

  useEffect(() => {
    const uid = user.uid
    setLoadedChats(false)
    const stops = [
      watchConversations(uid, rows => { setConversations(rows.filter(row => row.lifecycle !== 'deleting')); setLoadedChats(true) },
        failure => { error(failure); setLoadedChats(true) }),
      watchFriendships(uid, setFriendships, error),
      watchBlocked(uid, rows => setBlocked(rows.map(row => row.id)), error),
      watchHidden(uid, rows => setHidden(hiddenSet(rows)), error),
      watchPresence(uid, setPresence)
    ]
    releasePresence.current = startPresence(uid)
    if (notificationsEnabled(uid)) configureNotifications(uid).catch(() => {})
    fetch(backend + '/healthz', { cache: 'no-store' }).catch(() => {})
    return () => { stops.forEach(stop => stop()); void releasePresence.current?.() }
  }, [user.uid, error])
  const peerIds = [...new Set([...friendships.flatMap(row => [row.fromUid, row.toUid]), ...blocked,
    ...conversations.flatMap(row => row.participantIds || [])])].filter(id => id !== user.uid).sort().join(',')
  useEffect(() => {
    let active = true
    const ids = peerIds ? peerIds.split(',') : []
    Promise.allSettled(ids.map(id => profile(id))).then(results => {
      if (!active) return
      setPeople(Object.fromEntries(results.flatMap(result => result.status === 'fulfilled' && result.value ?
        [[result.value.id, result.value]] : [])))
    })
    return () => { active = false }
  }, [peerIds])

  const titleOf = row => {
    if (row.type === 'group') return { name: row.groupName || 'Nhóm UTEChat', photo: row.groupPhotoUrl }
    const peer = row.participantIds?.find(uid => uid !== user.uid)
    const data = people[peer] || row.participantSummaries?.[peer]
    return { name: preferences[row.id]?.nickname || data?.displayName || 'Người dùng UTEChat', photo: data?.photoUrl, peer }
  }
  const rank = row => preferences[row.id]?.pinned ? 0 : preferences[row.id]?.favorite ? 1 : 2
  const sorted = [...conversations].sort((first, second) =>
    rank(first) - rank(second) || (second.lastMessageAt || 0) - (first.lastMessageAt || 0))
  const conversation = sorted.find(row => row.id === selected)
  const title = conversation ? titleOf(conversation) : null
  const accepted = friendships.filter(row => row.status === 'accepted')
    .map(row => people[row.fromUid === user.uid ? row.toUid : row.fromUid]).filter(Boolean)
    .sort((a, b) => a.displayName.localeCompare(b.displayName, 'vi'))
  const unreadCount = sorted.filter(row => unread(row, user.uid, hidden)).length
  const incoming = friendships.filter(row => row.status === 'pending' && row.toUid === user.uid).length
  useEffect(() => {
    const messages = new Set(conversations.filter(row => row.lastMessageId).map(row => row.lastMessageId))
    const previous = seenMessages.current
    seenMessages.current = messages
    if (!loadedChats || previous === null || document.visibilityState !== 'visible') return
    const fresh = conversations.find(row => row.id !== selected && !previous.has(row.lastMessageId) &&
      unread(row, user.uid, hidden) && !preferences[row.id]?.muted && row.lastMessageAt > Date.now() - 10_000)
    if (fresh) notify(`${titleOf(fresh).name} đã gửi tin nhắn mới.`)
  }, [conversations, loadedChats])

  const controller = useCall(user.uid, error)
  useIncomingTone(controller.displayed?.status === 'ringing' && controller.displayed.calleeUid === user.uid &&
    !preferences[controller.displayed.conversationId]?.muted)
  const open = async uid => {
    try {
      if (blocked.includes(uid)) throw new Error('Bạn đã chặn tài khoản này.')
      setChat(await openDirect(uid))
    } catch (failure) { error(failure) }
  }
  const changeTab = item => {
    setTab(item)
    if (selected) setChat('', true)
  }
  const doSignOut = async () => {
    if (controller.displayed) { error(new Error('Kết thúc cuộc gọi trước khi đăng xuất.')); return }
    await releasePresence.current?.()
    await disableNotifications(user.uid).catch(() => {})
    await signOut(auth)
  }
  const navigation = <nav className="navigation" aria-label="Điều hướng chính">{tabs.map(({ key, label, icon: Icon }) => {
    const count = key === 'messages' ? unreadCount : key === 'friends' ? incoming : 0
    return <button key={key} aria-current={tab === key ? 'page' : undefined} onClick={() => changeTab(key)}>
      <span className="nav-icon"><Icon size={22} strokeWidth={tab === key ? 2.25 : 1.7} />{count > 0 && <i>{count > 99 ? '99+' : count}</i>}</span>
      <span>{label}</span>
    </button>
  })}</nav>
  const dialogProps = { error, close, notify }
  const dialogCommon = { ...dialogProps, user, friendships, open }
  const account = own || { displayName: user.displayName || 'Người dùng UTEChat', photoUrl: user.photoURL }
  const install = onIos() && !navigator.standalone && !window.matchMedia('(display-mode: standalone)').matches

  return <div className={`workspace ${selected ? 'has-chat' : ''}`}>
    <aside className="rail"><Brand text={false} />{navigation}
      <button className="rail-profile" onClick={() => changeTab('profile')} aria-label="Hồ sơ của bạn"><Avatar name={account.displayName} photo={account.photoUrl} size={39} /></button></aside>
    <aside className="sidebar">
      <div className="sidebar-top">
        <div className="sidebar-heading"><div className="sidebar-title"><h1>{tabs.find(item => item.key === tab).label}</h1></div>
          <div className="header-tools">
            <IconButton icon={QrCode} label="Mã QR kết bạn" onClick={() => setDialog({ type: 'qr' })} />
            <IconButton icon={Plus} label="Tạo nhóm" onClick={() => setDialog({ type: 'create' })} />
          </div>
        </div>
        <button className="search-trigger" onClick={() => setDialog({ type: 'search' })}><Search size={19} /><span>Tìm kiếm</span><span className="search-key">Tìm bạn, nhóm</span></button>
      </div>
      <div className="sidebar-scroll">
        {!own && loadError && <div className="sidebar-page"><Empty icon={CircleAlert} title="Chưa tải được hồ sơ"
          action={<button className="secondary" onClick={() => { setLoadError(false); setLoadAttempt(loadAttempt + 1) }}>Thử lại</button>} /></div>}
        {!own && !loadError && <p className="loader" role="status">Đang tải tài khoản…</p>}
        {own && tab === 'messages' && (loadedChats ? <ChatList {...{ sorted, selected, titleOf, hidden, drafts, preferences }}
          uid={user.uid} choose={setChat} openSearch={() => setDialog({ type: 'search' })} /> : <p className="loader" role="status">Đang tải hội thoại…</p>)}
        {own && tab === 'friends' && <Friends {...{ uid: user.uid, people, friendships, open, error, setDialog }} />}
        {own && tab === 'community' && <Community groups={sorted.filter(row => row.type === 'group')} select={setChat} create={() => setDialog({ type: 'create' })} />}
        {own && tab === 'profile' && <>
          <Profile {...{ user, presence, blocked, people, setDialog, error, notify }} data={account}
            signout={() => setConfirmation({ title: 'Đăng xuất', description: 'Bạn có thể đăng nhập lại bằng tài khoản hiện tại.', label: 'Đăng xuất', action: doSignOut })} />
          {install && <p className="install-tip">Để mở như ứng dụng trên iPhone, chọn <b>Chia sẻ</b> rồi <b>Thêm vào MH chính</b> trong Safari.</p>}
        </>}
      </div>
      <div className="mobile-nav">{navigation}</div>
    </aside>
    <main className="chat-panel">
      {conversation ? <Chat key={conversation.id} {...{ conversation, user, title, people, hidden, error, notify }}
        draft={drafts[conversation.id] || ''} saveDraft={value => setDrafts(previous => {
          const next = { ...previous }
          if (value) next[conversation.id] = value
          else delete next[conversation.id]
          return next
        })}
        settings={preferences[conversation.id] || {}} setSettings={settings => {
          setPreferences(previous => ({ ...previous, [conversation.id]: settings }))
          setDialog(data => data?.type === 'options' ? { ...data, settings } : data)
        }}
        blocked={Boolean(title.peer && blocked.includes(title.peer))} call={media => controller.outgoing(conversation, media)}
        showDialog={setDialog} onBack={() => setChat('')} /> :
        <div className="workspace-empty">
          <Brand />
          {selected && !loadedChats ? <p>Đang mở cuộc trò chuyện…</p> :
            selected ? <><h2>Hội thoại không khả dụng</h2><button className="secondary" onClick={() => setChat('', true)}>Về danh sách tin nhắn</button></> :
              <><span className="welcome-icon"><MessageSquare size={54} strokeWidth={1.3} /><i /></span>
                <h2>Hôm nay bạn muốn trò chuyện với ai?</h2><p>Chọn một cuộc trò chuyện hoặc tìm bạn bè để bắt đầu.</p>
                <button className="primary" onClick={() => setDialog({ type: 'search' })}><Search size={18} /> Tìm bạn bè <ArrowRight size={17} /></button></>}
        </div>}
    </main>

    {dialogs?.type === 'search' && <SearchDialog {...dialogCommon} {...{ uid: user.uid, conversations: sorted, titleOf }} select={setChat} />}
    {dialogs?.type === 'qr' && <QrDialog {...dialogCommon} />}
    {dialogs?.type === 'create' && <CreateGroup {...dialogProps} people={accepted} selectedFirst={dialogs.selectedFirst} created={id => { close(); setChat(id) }} />}
    {dialogs?.type === 'manage' && conversation && <GroupSettings {...dialogProps} {...{ group: conversation, uid: user.uid, people }}
      friends={accepted} confirm={setConfirmation} deselect={() => setChat('')}
      search={() => { close(); dialogs.search() }} gallery={() => setDialog({ type: 'gallery', id: conversation.id })} />}
    {dialogs?.type === 'profile' && <ProfileEditor {...dialogProps} data={account} />}
    {dialogs?.type === 'nickname' && <NicknameEditor data={dialogs} close={close} />}
    {dialogs?.type === 'info' && <Modal title={dialogs.title} onClose={close}><p>{dialogs.description}</p></Modal>}
    {dialogs?.type === 'password' && <Modal title="Đặt lại mật khẩu" onClose={close}>
      <p>UTEChat sẽ gửi liên kết đặt lại mật khẩu đến {user.email}.</p><button className="primary wide" onClick={async () => {
        try { await sendPasswordResetEmail(auth, user.email); close(); notify('Đã gửi liên kết đặt lại mật khẩu.') }
        catch (failure) { error(failure) }
      }}>Gửi liên kết</button>
    </Modal>}
    {dialogs?.type === 'blocked' && <BlockedList {...dialogProps} {...{ blocked, people }} />}
    {dialogs?.type === 'options' && <ChatOptions {...dialogProps} data={dialogs} replace={setDialog} confirm={setConfirmation} />}
    {dialogs?.type === 'message' && <MessageActions {...dialogProps} data={dialogs} replace={setDialog} confirm={setConfirmation}
      allowForward={sorted.some(row => row.id !== dialogs.id)} />}
    {dialogs?.type === 'forward' && <ForwardDialog {...dialogProps} data={dialogs} conversations={sorted} titleOf={titleOf} />}
    {dialogs?.type === 'report' && <ReportDialog {...dialogProps} data={dialogs} />}
    {dialogs?.type === 'peer-profile' && <PeerProfile {...dialogProps} uid={dialogs.uid} />}
    {dialogs?.type === 'poll' && <PollDialog {...dialogProps} data={dialogs} />}
    {dialogs?.type === 'gallery' && <Gallery {...dialogProps} id={dialogs.id} hidden={hidden}
      open={({ message }) => setDialog({ type: 'image', message })} />}
    {dialogs?.type === 'image' && <ImageDialog data={dialogs} {...dialogProps} />}
    {dialogs?.type === 'emoji' && <Modal title="Biểu tượng cảm xúc" onClose={close}><div className="emoji-grid">{reactions.map(emoji =>
      <button aria-label={emoji} key={emoji} onClick={() => { close(); requestAnimationFrame(() => dialogs.pick(emoji)) }}>{emoji}</button>)}</div></Modal>}
    {confirmation && <Confirm data={confirmation} onClose={() => setConfirmation(null)} error={error} />}
    <CallOverlay {...{ controller, uid: user.uid, error }} />
    <Toast toast={toast} clear={closeToast} />
  </div>
}

function NicknameEditor({ data, close }) {
  const [name, setName] = useState(data.settings.nickname || '')
  return <Modal title="Đổi tên gợi nhớ" onClose={close}><form className="stack" onSubmit={event => {
    event.preventDefault(); data.setSettings({ ...data.settings, nickname: name.trim() }); close()
  }}>
    <label>Tên gợi nhớ<input autoFocus value={name} maxLength={80} onChange={event => setName(event.target.value)} /></label>
    <p className="muted">Tên gợi nhớ chỉ hiển thị trên trình duyệt này.</p>
    <div className="button-row">
      <button type="button" className="secondary" onClick={() => { data.setSettings({ ...data.settings, nickname: '' }); close() }}>Mặc định</button>
      <button className="primary">Lưu</button>
    </div>
  </form></Modal>
}

function ProfileEditor({ data, close, error, notify }) {
  const [form, setForm] = useState({ displayName: data.displayName, bio: data.bio || '', photoUrl: data.photoUrl || '' })
  const [busy, setBusy] = useState(false)
  const input = (key, label, maxLength, type) => <label>{label}<input type={type || 'text'} value={form[key]} maxLength={maxLength}
    required={key === 'displayName'} onChange={event => setForm({ ...form, [key]: event.target.value })} /></label>
  return <Modal title="Chỉnh sửa hồ sơ" onClose={close}><form className="stack" onSubmit={async event => {
    event.preventDefault(); setBusy(true)
    try { await saveProfile(form); close(); notify('Đã lưu hồ sơ.') } catch (failure) { error(failure); setBusy(false) }
  }}>
    {input('displayName', 'Tên hiển thị', 80)}
    <label>Giới thiệu<textarea maxLength={300} value={form.bio} onChange={event => setForm({ ...form, bio: event.target.value })} /></label>
    {input('photoUrl', 'Đường dẫn ảnh đại diện HTTPS', 2048, 'url')}
    <button className="primary wide" disabled={busy}>Lưu thay đổi</button>
  </form></Modal>
}

function ImageDialog({ data, close, error }) {
  const [url, setUrl] = useState(data.url || '')
  useEffect(() => {
    if (data.url || !data.message) return
    const controller = new AbortController()
    let object
    downloadMedia(data.message, controller.signal).then(blob => {
      if (!controller.signal.aborted) { object = URL.createObjectURL(blob); setUrl(object) }
    }).catch(failure => { if (failure.name !== 'AbortError') error(failure) })
    return () => { controller.abort(); if (object) URL.revokeObjectURL(object) }
  }, [data.url, data.message, error])
  return <Modal title="Ảnh trong cuộc trò chuyện" onClose={close} className="image-dialog">
    {url ? <img src={url} alt="Ảnh trong tin nhắn" /> : <Empty icon={ImageIcon} title="Đang tải ảnh…" />}
  </Modal>
}

createRoot(document.getElementById('root')).render(<App />)
