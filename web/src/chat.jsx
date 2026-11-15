import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowDown, ArrowLeft, Bell, BellOff, Camera, CheckCheck, ChevronRight, CircleAlert, Copy,
  Ellipsis, Flag, Forward, HardDrive, Image, MessageSquare, Mic, MicOff, Pencil, Phone, PhoneOff, Pin,
  Plus, Reply, Search, Send, Shield, Smile, Square, Star, Trash2, UserRound, Users, Video, VideoOff, X
} from 'lucide-react'
import {
  blockUser, downloadMedia, hide, markRead, messagePreview, olderMessages, profile, react, reactions,
  recall, reportUser, sendMessage, setPin, vote, watchMessage, watchMessages, watchPresence
} from './chat-data'
import { callHistoryTitle, callPeer } from './calls'
import { Avatar, Empty, IconButton, Modal, formatDate, formatTime, sameDay, useLocal } from './ui'
import { PersonRow } from './people'

const voiceType = 'audio/mp4'
const imageTypes = ['image/jpeg', 'image/png', 'image/webp']
async function convertPhoto(file) {
  if (imageTypes.includes(file.type) && file.size <= 8 * 1024 * 1024) return file
  const url = URL.createObjectURL(file)
  try {
    const picture = new window.Image()
    picture.src = url
    await picture.decode()
    const ratio = Math.min(1, 1600 / Math.max(picture.naturalWidth, picture.naturalHeight))
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(picture.naturalWidth * ratio)
    canvas.height = Math.round(picture.naturalHeight * ratio)
    canvas.getContext('2d').drawImage(picture, 0, 0, canvas.width, canvas.height)
    const result = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', .86))
    if (!result || result.size > 8 * 1024 * 1024) throw new Error('Ảnh phải nhỏ hơn 8 MB.')
    return result
  } finally { URL.revokeObjectURL(url) }
}

function useRecorder(error) {
  const [recording, setRecording] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [clip, setClip] = useState(null)
  const [busy, setBusy] = useState(false)
  const current = useRef(null)
  const alive = useRef(true)
  useEffect(() => () => {
    alive.current = false
    if (current.current) current.current.discard()
  }, [])
  useEffect(() => {
    if (!clip?.url) return
    return () => URL.revokeObjectURL(clip.url)
  }, [clip?.url])
  const start = async () => {
    if (current.current) return
    if (!window.MediaRecorder?.isTypeSupported(voiceType)) {
      error(new Error('Trình duyệt này chưa ghi được âm thanh AAC. Hãy dùng Safari hoặc Chrome mới.'))
      return
    }
    setBusy(true)
    let local, timer
    try {
      local = await navigator.mediaDevices.getUserMedia({ audio: true })
      if (!alive.current) { local.getTracks().forEach(track => track.stop()); return }
      const selected = new MediaRecorder(local, { mimeType: voiceType, audioBitsPerSecond: 48_000 })
      const chunks = []
      const started = Date.now()
      let duration = 0
      let discard = false
      const cleanup = () => {
        clearInterval(timer)
        local.getTracks().forEach(track => track.stop())
        current.current = null
        if (alive.current) setRecording(false)
      }
      selected.ondataavailable = event => { if (event.data.size) chunks.push(event.data) }
      selected.onstop = () => {
        cleanup()
        if (!alive.current || discard) return
        const blob = new Blob(chunks, { type: voiceType })
        if (blob.size > 524288) { error(new Error('Bản ghi quá lớn. Hãy thử ghi ngắn hơn.')); return }
        if (blob.size && duration >= 350) setClip({ blob, durationMs: duration, url: URL.createObjectURL(blob) })
      }
      selected.onerror = () => { discard = true; cleanup(); error(new Error('Ghi âm bị gián đoạn.')) }
      const stop = () => {
        duration = Math.min(60_000, Date.now() - started)
        if (selected.state !== 'inactive') selected.stop()
      }
      current.current = { stop, discard() { discard = true; stop(); cleanup() } }
      selected.start()
      timer = setInterval(() => {
        const seconds = Math.floor((Date.now() - started) / 1000)
        setElapsed(seconds)
        if (seconds >= 60) stop()
      }, 250)
      setClip(null)
      setElapsed(0)
      setRecording(true)
    } catch (failure) {
      local?.getTracks().forEach(track => track.stop())
      current.current = null
      error(failure)
    } finally { if (alive.current) setBusy(false) }
  }
  return { recording, elapsed, clip, busy, start, stop: () => current.current?.stop(),
    cancel: () => { current.current?.discard(); setClip(null) } }
}
const durationLabel = seconds => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`

function Attachment({ message, open }) {
  const [url, setUrl] = useState('')
  const [failed, setFailed] = useState(false)
  const [attempt, retry] = useState(0)
  const [requested, setRequested] = useState(message.type === 'image')
  const cardRef = useRef(null)
  useEffect(() => {
    let object
    const controller = new AbortController()
    if (!requested) return
    setFailed(false)
    setUrl('')
    downloadMedia(message, controller.signal).then(blob => {
      if (controller.signal.aborted) return
      object = URL.createObjectURL(blob)
      setUrl(object)
    }).catch(error => { if (error.name !== 'AbortError') setFailed(true) })
    return () => { controller.abort(); if (object) URL.revokeObjectURL(object) }
  }, [message.mediaPath, message.type, requested, attempt])
  useEffect(() => {
    if (message.type === 'voice' && url) cardRef.current?.play().catch(() => {})
  }, [url, message.type])
  if (failed) return <button className="text-button attachment" onClick={() => retry(value => value + 1)}>Tải lại {message.type === 'voice' ? 'tin nhắn thoại' : 'ảnh'}</button>
  if (message.type === 'voice') {
    if (!url) return <button className="text-button attachment voice-play" disabled={requested} onClick={() => setRequested(true)}><Mic size={18} />
      {requested ? 'Đang tải…' : `Phát tin nhắn thoại · ${durationLabel(Math.ceil((message.durationMs || 0) / 1000))}`}</button>
    return <audio controls src={url} ref={cardRef} preload="metadata" />
  }
  return url ? <button className="image-preview" onClick={() => open({ type: 'image', url, message })}><img src={url} alt="Ảnh trong tin nhắn" /></button> :
    <span className="attachment-placeholder"><Image size={22} /> Đang tải ảnh…</span>
}

function Quote({ conversation, reply, hidden, original, jump }) {
  const [status, setStatus] = useState(null)
  useEffect(() => {
    if (original || hidden || !reply.id) return
    return watchMessage(conversation, reply.id, message => setStatus(message?.type || 'recalled'), () => setStatus('recalled'))
  }, [conversation, reply.id, original?.id, hidden])
  const suppressed = hidden || original?.type === 'recalled' || status === 'recalled'
  return <button className="quote" disabled={suppressed} onClick={() => jump(reply.id)}>
    <strong>{suppressed ? 'Tin nhắn đã được xóa' : reply.name || 'Tin nhắn'}</strong>
    {!suppressed && <span>{messagePreview({ type: reply.type, content: reply.text })}</span>}
  </button>
}

function Bubble({ message, group, own, ownUid, person, replies, hidden, action, open, onCall, id, error, jump, blocked }) {
  const timer = useRef(null)
  const point = useRef(null)
  const longPress = useRef(false)
  const history = message.id.startsWith('call_') && callHistoryTitle(message.content)
  const mine = message.reactions?.[ownUid]
  const summary = Object.entries(Object.values(message.reactions || {}).reduce((map, emoji) => ({ ...map, [emoji]: (map[emoji] || 0) + 1 }), {}))
  const cancel = () => clearTimeout(timer.current)
  useEffect(() => cancel, [])
  const start = event => {
    if (event.button !== 0 || event.target.closest('button,a,audio,video')) return
    cancel()
    longPress.current = false
    point.current = [event.clientX, event.clientY]
    timer.current = setTimeout(() => { longPress.current = true; action(message) }, 500)
  }
  const body = history ? <>
    <div className="call-record-top"><span className="call-record-icon">{history.media === 'video' ? <Video size={20} /> : <Phone size={20} />}</span>
      <span><strong>{history.title}</strong><time>{formatTime(message.sentAt)}</time></span></div>
    <button className="callback-button" disabled={blocked} onClick={() => onCall(history.media)}>Gọi lại</button>
  </> : <>
    {group && !own && <strong className="sender">{person?.displayName || message.senderName}</strong>}
    {message.reply && <Quote conversation={id} reply={message.reply} original={replies.get(message.reply.id)} jump={jump}
      hidden={hidden.has(`${id}:${message.reply.id}`)} />}
    {message.type === 'poll' ? <div className="poll"><strong>{message.pollQuestion}</strong>
      {message.pollOptions?.map((option, i) => {
        const count = Object.values(message.pollVotes || {}).filter(value => value === i).length
        const chosen = message.pollVotes?.[ownUid] === i
        return <button key={i} aria-pressed={chosen} onClick={() => vote(id, message.id, i).catch(error)}>
          <span>{option}</span><small>{count} phiếu</small>
        </button>
      })}</div>
      : message.mediaPath && ['image', 'voice'].includes(message.type) ? <Attachment {...{ message, open }} />
        : <p className={message.type === 'recalled' ? 'recalled' : ''}>{messagePreview(message)}</p>}
    <time className="bubble-time">{formatTime(message.sentAt)}</time>
  </>
  return <div className={`message-row ${own ? 'sent' : 'received'}`} id={`msg-${message.id}`}>
    {!own && <Avatar name={person?.displayName || message.senderName} photo={person?.photoUrl} size={30} />}
    <div className="bubble-wrap">
      <div className={`bubble ${history ? 'call-record' : ''}`}
        onContextMenu={event => { event.preventDefault(); cancel(); if (!longPress.current) action(message); longPress.current = false }}
        onPointerDown={start}
        onPointerMove={event => { if (point.current && Math.hypot(event.clientX - point.current[0], event.clientY - point.current[1]) > 10) cancel() }}
        onPointerUp={cancel} onPointerCancel={cancel} onPointerLeave={cancel}>{body}</div>
      {summary.length > 0 && <button className="reaction-summary" aria-label="Cảm xúc" disabled={blocked} onClick={() => mine ? react(id, message.id, mine).catch(error) : action(message)}>
        {summary.map(([emoji, count]) => <span key={emoji}>{emoji}{count > 1 ? count : ''}</span>)}</button>}
    </div>
    <IconButton icon={Ellipsis} label="Tùy chọn tin nhắn" className="bubble-action" onClick={() => action(message)} />
  </div>
}

export function Chat({ conversation, user, title, people, hidden, draft, saveDraft, blocked, call,
  showDialog, onBack, error, notify, settings, setSettings }) {
  const id = conversation.id
  const group = conversation.type === 'group'
  const ownUid = user.uid
  const [recent, setRecent] = useState([])
  const [older, setOlder] = useState([])
  const [firstLoaded, setFirstLoaded] = useState(false)
  const [oldCursor, setOldCursor] = useState(null)
  const [hasMore, setHasMore] = useState(false)
  const [loadingOld, setLoadingOld] = useState(false)
  const [reply, setReply] = useState(null)
  const [sending, setSending] = useState(false)
  const [photo, setPhoto] = useState(null)
  const [pinned, setPinned] = useState(null)
  const [presence, setPresence] = useState(null)
  const [near, setNear] = useState(true)
  const [search, setSearch] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [more, setMore] = useState(false)
  const log = useRef(null)
  const text = useRef(null)
  const imageInput = useRef(null)
  const photoInput = useRef(null)
  const uiSeen = useRef(null)
  const lastBottom = useRef(0)
  const jumping = useRef(false)
  const recorder = useRecorder(error)
  useEffect(() => watchMessages(id, (list, cursor, more) => {
    setRecent(list)
    if (!older.length) { setOldCursor(cursor); setHasMore(more) }
    setFirstLoaded(true)
  }, error), [id, error, older.length > 0])
  useEffect(() => {
    if (!conversation.pinnedMessageId) { setPinned(null); return }
    return watchMessage(id, conversation.pinnedMessageId, setPinned, () => setPinned(null))
  }, [id, conversation.pinnedMessageId])
  useEffect(() => {
    if (group || !title.peer) return
    return watchPresence(title.peer, setPresence)
  }, [group, title.peer])
  useEffect(() => {
    if (!photo?.url) return
    return () => URL.revokeObjectURL(photo.url)
  }, [photo?.url])
  const unique = useMemo(() => new Map([...older, ...recent].map(message => [message.id, message])), [older, recent])
  const messages = useMemo(() => [...unique.values()].filter(message => !hidden.has(`${id}:${message.id}`))
    .sort((a, b) => a.sentAt - b.sentAt || a.id.localeCompare(b.id)), [unique, hidden, id])
  const bottom = messages.at(-1)
  const hasOnline = presence?.visible && Object.values(presence.connections || {}).some(Boolean)
  const subtitle = group ? `${conversation.participantIds.length} thành viên` : hasOnline ? 'Đang hoạt động' : ''
  const readCurrent = () => {
    if (!bottom || document.visibilityState !== 'visible' || uiSeen.current === bottom.id) return
    uiSeen.current = bottom.id
    markRead(id, bottom).catch(() => { if (uiSeen.current === bottom.id) uiSeen.current = null })
  }
  useEffect(() => {
    readCurrent()
    document.addEventListener('visibilitychange', readCurrent)
    return () => document.removeEventListener('visibilitychange', readCurrent)
  }, [id, bottom?.id])
  useLayoutEffect(() => {
    if (!firstLoaded || jumping.current || !log.current) return
    const height = log.current.scrollHeight
    if (near || lastBottom.current === 0) log.current.scrollTop = height
    lastBottom.current = height
  }, [firstLoaded, bottom?.id, near])

  const scrollBottom = () => log.current?.scrollTo({ top: log.current.scrollHeight, behavior: 'smooth' })
  const loadOlder = async () => {
    if (!oldCursor || loadingOld) return
    setLoadingOld(true)
    jumping.current = true
    const height = log.current.scrollHeight
    try {
      const result = await olderMessages(id, oldCursor)
      setOlder(previous => [...previous, ...result.list])
      setOldCursor(result.cursor)
      setHasMore(result.more)
      requestAnimationFrame(() => { if (log.current) log.current.scrollTop += log.current.scrollHeight - height; jumping.current = false })
    } catch (failure) { jumping.current = false; error(failure) } finally { setLoadingOld(false) }
  }
  const jump = mid => {
    const element = document.getElementById(`msg-${mid}`)
    if (!element) { notify('Cuộn lên và tải thêm tin nhắn để xem nội dung cũ hơn.'); return }
    element.scrollIntoView({ block: 'center', behavior: 'smooth' })
    element.animate([{ backgroundColor: '#9fceff90' }, { backgroundColor: 'transparent' }], { duration: 1200 })
  }
  const submit = async (override) => {
    const content = draft.trim()
    if (sending || blocked || (!content && !photo && !recorder.clip && !override)) return
    setSending(true)
    try {
      const clip = recorder.clip
      const data = override || (clip ? { type: 'voice', content: 'Tin nhắn thoại', blob: clip.blob, durationMs: clip.durationMs }
        : photo ? { type: 'image', content: '', blob: photo.blob } : { type: 'text', content })
      await sendMessage(id, data, reply)
      if (data.type === 'text') saveDraft('')
      setReply(null); setPhoto(null); recorder.cancel(); setMore(false); scrollBottom()
    } catch (failure) { error(failure) } finally { setSending(false) }
  }
  const attach = async file => {
    if (!file) return
    try {
      const blob = await convertPhoto(file)
      recorder.cancel()
      setPhoto({ blob, url: URL.createObjectURL(blob) })
      setMore(false)
    } catch { error(new Error('Không mở được ảnh. Hãy chọn ảnh JPEG, PNG hoặc WebP dưới 8 MB.')) }
  }
  const openActions = message => showDialog({
    type: 'message', message, id, own: message.senderId === ownUid, isPinned: pinned?.id === message.id,
    reply: () => { setReply(message); text.current?.focus() },
    photo: () => { setReply(message); photoInput.current?.click() }, blocked
  })
  const handleScroll = event => {
    const el = event.currentTarget
    setNear(el.scrollHeight - el.clientHeight - el.scrollTop < 100)
  }
  const filtered = search && keyword.trim()
    ? messages.filter(message => messagePreview(message).toLowerCase().includes(keyword.trim().toLowerCase())) : []
  return <section className={`chat ${settings.wallpaper || 'cloud'}`} aria-label={title.name}>
    <header className="chat-head">
      <IconButton icon={ArrowLeft} label="Quay lại danh sách" className="chat-back" onClick={onBack} />
      <Avatar name={title.name} photo={title.photo} size={42} group={group} online={hasOnline} />
      <div className="chat-title"><h2>{title.name}</h2>{subtitle && <p>{subtitle}</p>}</div>
      <div className="chat-tools">
        {!group && <><IconButton icon={Phone} label="Gọi thoại" onClick={() => call('audio')} disabled={blocked} />
          <IconButton icon={Video} label="Gọi video" onClick={() => call('video')} disabled={blocked} /></>}
        <IconButton icon={Ellipsis} label="Tùy chọn hội thoại" onClick={() => showDialog(group ? { type: 'manage', search: () => setSearch(true) } : {
          type: 'options', title, id, blocked, search: () => setSearch(true), settings, setSettings,
          stats: {
            count: messages.length, images: messages.filter(message => message.type === 'image').length,
            bytes: messages.reduce((sum, message) => sum + (Number(message.fileSize) || 0), 0)
          }
        })} />
      </div>
    </header>
    {search && <div className="message-search">
      <Search size={18} /><input autoFocus placeholder="Tìm trong tin nhắn đã tải" aria-label="Tìm tin nhắn" value={keyword} onChange={event => setKeyword(event.target.value)} />
      <IconButton icon={X} label="Đóng tìm tin nhắn" onClick={() => { setSearch(false); setKeyword('') }} />
      {!!keyword.trim() && <div className="message-results">{filtered.length ? filtered.slice(-20).reverse().map(message =>
        <button key={message.id} onClick={() => jump(message.id)}>{messagePreview(message)}<time>{formatTime(message.sentAt)}</time></button>)
        : <p>Không tìm thấy trong các tin nhắn đã tải.</p>}</div>}
    </div>}
    {pinned && !hidden.has(`${id}:${pinned.id}`) && <div className="pinned">
      <Pin size={17} /><button onClick={() => jump(pinned.id)}>{messagePreview(pinned)}</button>
      <IconButton icon={X} label="Bỏ ghim tin nhắn" onClick={() => setPin(id, null).catch(error)} />
    </div>}
    <div className="chat-content">
      <div className="messages" ref={log} onScroll={handleScroll} role="log" aria-label="Tin nhắn">
        {hasMore && <button className="secondary load-older" disabled={loadingOld} onClick={loadOlder}>{loadingOld ? 'Đang tải…' : 'Xem tin nhắn trước'}</button>}
        {firstLoaded && !messages.length && <Empty icon={MessageSquare} title="Bắt đầu trò chuyện" description="Tin nhắn sẽ xuất hiện ở đây." />}
        {!firstLoaded && <p className="loader" role="status">Đang tải tin nhắn…</p>}
        {messages.map((message, index) => {
          const own = message.senderId === ownUid
          const unreadPeer = !group && own && bottom?.id === message.id &&
            (conversation.readStates?.[title.peer]?.readAt || 0) >= message.sentAt
          const person = people[message.senderId] || conversation.participantSummaries?.[message.senderId]
          return <div key={message.id}>
            {!sameDay(messages[index - 1]?.sentAt, message.sentAt) && <div className="day">{formatDate(message.sentAt)}</div>}
            <Bubble {...{ message, group, own, ownUid, person, replies: unique, hidden, id, error, jump, blocked }}
              action={openActions} open={showDialog} onCall={call} />
            {unreadPeer && <div className="receipt"><CheckCheck size={13} /> Đã xem</div>}
          </div>
        })}
      </div>
      {!near && <IconButton icon={ArrowDown} className="to-latest" label="Tới tin nhắn mới nhất" onClick={scrollBottom} />}
    </div>
    {blocked ? <div className="blocked-banner"><Shield size={18} /> Bạn đã chặn tài khoản này.</div> : <div className="composer-area">
      {reply && <div className="compose-preview"><Reply size={20} /><div><strong>Trả lời {reply.senderName || 'tin nhắn'}</strong><small>{messagePreview(reply)}</small></div>
        <IconButton icon={X} label="Hủy trả lời" onClick={() => setReply(null)} /></div>}
      {photo && <div className="compose-preview"><img src={photo.url} alt="" /><div><strong>Ảnh đính kèm</strong><small>Bấm gửi để chia sẻ ảnh</small></div>
        <IconButton icon={X} label="Bỏ ảnh" onClick={() => setPhoto(null)} /></div>}
      {recorder.clip && <div className="compose-preview"><Mic size={20} /><audio src={recorder.clip.url} controls preload="metadata" />
        <IconButton icon={X} label="Bỏ bản ghi" onClick={recorder.cancel} /></div>}
      {more && <div className="composer-tools">
        <button className="secondary compact" onClick={() => showDialog({ type: 'emoji', pick: emoji => { saveDraft(draft + emoji); text.current?.focus() } })}><Smile size={17} /> Biểu tượng cảm xúc</button>
        {group && <button className="secondary compact" onClick={() => showDialog({ type: 'poll', submit: data => sendMessage(id, data) })}>Tạo bình chọn</button>}
      </div>}
      <form className="composer" onSubmit={event => { event.preventDefault(); submit() }}>
        <IconButton icon={Plus} label="Thêm tiện ích" onClick={() => setMore(!more)} aria-expanded={more} />
        {recorder.recording ? <div className="recording" role="status"><span /> Đang ghi âm {durationLabel(recorder.elapsed)}
          <IconButton icon={X} label="Hủy ghi âm" onClick={recorder.cancel} /></div>
          : <textarea ref={text} aria-label="Tin nhắn" rows="1" maxLength={5000} value={draft} placeholder="Nhập tin nhắn"
            onChange={event => saveDraft(event.target.value)} onKeyDown={event => {
              if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing && window.matchMedia('(pointer: fine)').matches) {
                event.preventDefault(); submit()
              }
            }} />}
        <input type="file" hidden accept="image/*" ref={imageInput} onChange={event => { attach(event.target.files[0]); event.target.value = '' }} />
        <input type="file" hidden accept="image/*" capture="environment" ref={photoInput}
          onChange={event => { attach(event.target.files[0]); event.target.value = '' }} />
        {!recorder.recording && <IconButton icon={Image} label="Chọn ảnh" disabled={sending} onClick={() => imageInput.current?.click()} />}
        <IconButton icon={recorder.recording ? Square : Mic} label={recorder.recording ? 'Dừng ghi âm' : 'Ghi âm'}
          disabled={recorder.busy || sending} onClick={() => {
            if (recorder.recording) recorder.stop()
            else { setPhoto(null); recorder.start() }
          }} />
        <button className="send-button" type="submit" aria-label="Gửi tin nhắn" disabled={sending || recorder.recording || (!draft.trim() && !photo && !recorder.clip)}><Send size={20} /></button>
      </form>
    </div>}
  </section>
}

const optionRow = (Icon, label, onClick, danger = false) =>
  <button className={`option-row ${danger ? 'danger-text' : ''}`} onClick={onClick}><Icon size={19} />{label}<ChevronRight size={16} /></button>
export function MessageActions({ data, close, replace, confirm, error, notify, allowForward }) {
  const { message, id } = data
  const [page, setPage] = useState('main')
  const call = message.id.startsWith('call_') && callHistoryTitle(message.content)
  const editable = message.type !== 'recalled' && !call
  const clip = () => {
    close()
    navigator.clipboard.writeText(message.content).then(() => notify('Đã sao chép tin nhắn.')).catch(error)
  }
  const perform = fn => { close(); fn().catch(error) }
  return <Modal title={page === 'delete' ? 'Xóa tin nhắn' : page === 'more' ? 'Tùy chọn khác' : 'Tùy chọn tin nhắn'} onClose={close}
    onBack={page !== 'main' ? () => setPage('main') : undefined} className="message-menu">
    {page === 'main' && <>
      {editable && !data.blocked && <div className="reaction-bar">{reactions.slice(0, 6).map(emoji =>
        <button key={emoji} aria-label={`Thả cảm xúc ${emoji}`} onClick={() => perform(() => react(id, message.id, emoji))}>{emoji}</button>)}
        <IconButton icon={Camera} label="Trả lời bằng ảnh" onClick={() => { data.photo(); close() }} />
        <IconButton icon={Plus} label="Thêm cảm xúc" onClick={() => replace({ type: 'emoji', pick: emoji => react(id, message.id, emoji).catch(error) })} /></div>}
      <div className="menu-preview">{messagePreview(message)}</div>
      {editable && !data.blocked && optionRow(Reply, 'Trả lời', () => { close(); requestAnimationFrame(data.reply) })}
      {editable && ['text', 'poll'].includes(message.type) && optionRow(Copy, 'Sao chép', clip)}
      {optionRow(Ellipsis, 'Khác', () => setPage('more'))}
    </>}
    {page === 'more' && <>
      {editable && allowForward && ['text', 'image', 'voice', 'poll'].includes(message.type) &&
        optionRow(Forward, 'Chuyển tiếp', () => replace({ type: 'forward', sourceId: id, message }))}
      {!data.own && editable && optionRow(Flag, 'Báo cáo', () => replace({ type: 'report', id, uid: message.senderId, messageId: message.id }))}
      {editable && !data.blocked && optionRow(Pin, data.isPinned ? 'Bỏ ghim' : 'Ghim', () => perform(() => setPin(id, data.isPinned ? null : message.id)))}
      {optionRow(Trash2, 'Xóa', () => setPage('delete'), true)}
    </>}
    {page === 'delete' && <>
      {optionRow(Trash2, 'Xóa ở phía bạn', () => perform(() => hide(id, message.id)), true)}
      {data.own && editable && optionRow(Trash2, 'Xóa với mọi người', () => confirm({
        title: 'Xóa với mọi người', description: 'Tin nhắn sẽ hiện là đã thu hồi trong cuộc trò chuyện.',
        label: 'Xóa với mọi người', danger: true, action: async () => { await recall(id, message.id); close() }
      }), true)}
    </>}
  </Modal>
}

export function ForwardDialog({ data, conversations, titleOf, close, error, notify }) {
  const [sending, setSending] = useState('')
  const send = async id => {
    setSending(id)
    const original = data.message
    try {
      let entry = { type: original.type, content: original.content || '' }
      if (original.mediaPath && ['image', 'voice'].includes(original.type)) {
        entry.blob = await downloadMedia(original)
        if (original.type === 'voice') entry.durationMs = original.durationMs
      }
      if (original.type === 'poll') Object.assign(entry, { pollQuestion: original.pollQuestion,
        pollOptions: original.pollOptions, pollVotes: {} })
      await sendMessage(id, entry)
      notify('Đã chuyển tiếp tin nhắn.')
      close()
    } catch (failure) { error(failure); setSending('') }
  }
  return <Modal title="Chuyển tiếp tin nhắn" onClose={close}>
    {conversations.filter(row => row.id !== data.sourceId).map(row => {
      const name = titleOf(row)
      return <PersonRow key={row.id} person={{ displayName: name.name, photoUrl: name.photo }}>
        <button className="primary compact" disabled={Boolean(sending)} onClick={() => send(row.id)}>{sending === row.id ? 'Đang gửi…' : 'Gửi'}</button></PersonRow>
    })}
  </Modal>
}

export function ReportDialog({ data, close, error, notify }) {
  const [reason, setReason] = useState('spam')
  const [busy, setBusy] = useState(false)
  return <Modal title="Báo cáo người dùng" onClose={close}><form className="stack" onSubmit={async event => {
    event.preventDefault(); setBusy(true)
    try { await reportUser(data.id, data.uid, reason, data.messageId); notify('Đã gửi báo cáo.'); close() }
    catch (failure) { error(failure); setBusy(false) }
  }}>
    <label>Lý do<select value={reason} onChange={event => setReason(event.target.value)}>
      <option value="spam">Tin nhắn rác</option><option value="abuse">Quấy rối hoặc lạm dụng</option><option value="other">Lý do khác</option>
    </select></label><button className="primary wide" disabled={busy}>Gửi báo cáo</button>
  </form></Modal>
}

export function PollDialog({ data, close, error }) {
  const [question, setQuestion] = useState('')
  const [options, setOptions] = useState(['', ''])
  const [busy, setBusy] = useState(false)
  return <Modal title="Tạo bình chọn" onClose={close}><form className="stack" onSubmit={async event => {
    event.preventDefault()
    const choices = options.map(value => value.trim()).filter(Boolean)
    if (choices.length < 2 || new Set(choices).size !== choices.length) { error(new Error('Nhập ít nhất hai lựa chọn khác nhau.')); return }
    setBusy(true)
    try {
      await data.submit({ type: 'poll', content: question.trim(), pollQuestion: question.trim(), pollOptions: choices, pollVotes: {} })
      close()
    } catch (failure) { error(failure); setBusy(false) }
  }}>
    <label>Câu hỏi<input autoFocus required maxLength={200} value={question} onChange={event => setQuestion(event.target.value)} /></label>
    {options.map((option, i) => <label key={i}>Lựa chọn {i + 1}<input required maxLength={80} value={option}
      onChange={event => setOptions(options.map((value, j) => i === j ? event.target.value : value))} /></label>)}
    {options.length < 6 && <button type="button" className="secondary" onClick={() => setOptions([...options, ''])}><Plus size={17} /> Thêm lựa chọn</button>}
    <button className="primary wide" disabled={busy || !question.trim()}>Gửi bình chọn</button>
  </form></Modal>
}

export function ChatOptions({ data, close, replace, confirm, error }) {
  const { id, title, settings, setSettings, blocked, stats } = data
  return <Modal title="Tùy chọn hội thoại" onClose={close}>
    <div className="settings-hero"><Avatar name={title.name} photo={title.photo} size={68} /><strong>{title.name}</strong></div>
    {optionRow(Search, 'Tìm tin nhắn', () => { close(); data.search() })}
    {optionRow(UserRound, 'Xem hồ sơ', () => replace({ type: 'peer-profile', uid: title.peer }))}
    {optionRow(Pencil, 'Đổi tên gợi nhớ', () => replace({ type: 'nickname', settings, setSettings }))}
    {optionRow(Star, settings.favorite ? 'Bỏ đánh dấu liên hệ nhanh' : 'Đánh dấu liên hệ nhanh',
      () => { setSettings({ ...settings, favorite: !settings.favorite }); close() })}
    {optionRow(Pin, settings.pinned ? 'Bỏ ghim hội thoại' : 'Ghim hội thoại', () => { setSettings({ ...settings, pinned: !settings.pinned }); close() })}
    {optionRow(settings.muted ? BellOff : Bell, settings.muted ? 'Bật thông báo trong trang' : 'Tắt thông báo trong trang', () => { setSettings({ ...settings, muted: !settings.muted }); close() })}
    {optionRow(Users, `Tạo nhóm với ${title.name}`, () => replace({ type: 'create', selectedFirst: title.peer }))}
    {optionRow(Image, 'Ảnh và liên kết đã chia sẻ', () => replace({ type: 'gallery', id }))}
    <label className="labeled-row">Hình nền<select value={settings.wallpaper || 'cloud'} onChange={event => setSettings({ ...settings, wallpaper: event.target.value })}>
      <option value="cloud">Xám xanh</option><option value="sky">Xanh da trời</option><option value="sand">Kem</option><option value="paper">Trắng</option>
    </select></label>
    {optionRow(Shield, 'Bảo vệ trò chuyện', () => replace({ type: 'info', title: 'Bảo vệ trò chuyện',
      description: 'Quyền truy cập tin nhắn do Firebase kiểm soát theo tài khoản và thành viên hội thoại. UTEChat chưa hỗ trợ mã hóa đầu cuối.' }))}
    {optionRow(HardDrive, 'Dung lượng trò chuyện', () => replace({ type: 'info', title: 'Dung lượng trò chuyện',
      description: `Đã tải ${stats.count} tin nhắn, gồm ${stats.images} ảnh. Tệp đính kèm có tổng dung lượng ${(stats.bytes / 1024).toLocaleString('vi-VN', { maximumFractionDigits: 1 })} KB.` }))}
    {optionRow(Flag, 'Báo cáo', () => replace({ type: 'report', id, uid: title.peer }))}
    {optionRow(Shield, blocked ? 'Bỏ chặn' : 'Chặn người dùng', () => {
      if (blocked) { blockUser(title.peer, false).then(close).catch(error); return }
      confirm({ title: 'Chặn người dùng', description: 'Hai bạn sẽ không thể nhắn tin hoặc gọi điện cho nhau.', label: 'Chặn', danger: true,
        action: async () => { await blockUser(title.peer, true); close() } })
    }, true)}
  </Modal>
}

export function PeerProfile({ uid, close, error }) {
  const [person, setPerson] = useState(null)
  useEffect(() => {
    let done = false
    profile(uid).then(value => { if (!done) setPerson(value) }).catch(error)
    return () => { done = true }
  }, [uid, error])
  return <Modal title="Trang cá nhân" onClose={close}>
    <div className="settings-hero"><Avatar name={person?.displayName} photo={person?.photoUrl} size={80} />
      <strong>{person?.displayName || 'Người dùng UTEChat'}</strong></div>
  </Modal>
}

export function Gallery({ id, hidden, close, open, error }) {
  const [messages, setMessages] = useState([])
  useEffect(() => watchMessages(id, list => setMessages(list), error), [id, error])
  const visible = messages.filter(row => !hidden.has(`${id}:${row.id}`))
  const images = visible.filter(row => row.type === 'image')
  const links = [...new Set(visible.filter(row => row.type === 'text' && !row.id.startsWith('call_'))
    .flatMap(row => row.content?.match(/https?:\/\/[^\s<>"']+/gi) || []))]
  return <Modal title="Ảnh và liên kết gần đây" onClose={close}>
    {images.length ? <div className="gallery">{images.map(message => <Attachment key={message.id} {...{ message, open }} />)}</div>
      : !links.length && <Empty icon={Image} title="Chưa có ảnh hoặc liên kết gần đây" />}
    {!!links.length && <><h3 className="section-label">Liên kết</h3>{links.map(link =>
      <a className="shared-link" key={link} href={link} target="_blank" rel="noopener noreferrer">{link}</a>)}</>}
  </Modal>
}

export function CallOverlay({ controller, uid, error }) {
  const call = controller.displayed
  const [peer, setPeer] = useState(null)
  const remote = useRef(null)
  const local = useRef(null)
  const preview = useRef(null)
  const modal = useRef(null)
  const incoming = call?.calleeUid === uid && call.status === 'ringing'
  const video = call?.mediaType === 'video'
  useEffect(() => {
    if (!call) return
    setPeer(null)
    let done = false
    callPeer(call, uid).then(value => { if (!done) setPeer(value) }).catch(error)
    return () => { done = true }
  }, [call?.id, uid, error])
  useEffect(() => {
    if (!call || !modal.current) return
    const dialog = modal.current
    dialog.showModal()
    return () => dialog.close()
  }, [call?.id])
  useEffect(() => {
    const track = controller.media.video
    if (track && remote.current) track.play(remote.current, { fit: 'cover' })
    return () => track?.stop()
  }, [controller.media.video])
  useEffect(() => {
    const track = controller.media.localTrack
    if (track && local.current && !controller.videoOff) track.play(local.current, { fit: 'cover', mirror: true })
    return () => track?.stop()
  }, [controller.media.localTrack, controller.videoOff])
  useEffect(() => {
    if (preview.current && controller.preview && video) preview.current.srcObject = controller.preview
  }, [controller.preview, controller.media.localTrack, controller.videoOff, video])
  if (!call) return null
  const connected = controller.media.ready && Boolean(controller.media.audio || controller.media.video)
  const showPeer = !(video && controller.media.video)
  return <dialog ref={modal} className="call-overlay" aria-label={video ? 'Cuộc gọi video' : 'Cuộc gọi thoại'} onCancel={event => event.preventDefault()}>
    <div className="call-surface">
      <header><span className="call-type">{video ? <Video size={20} /> : <Phone size={20} />}{video ? 'Cuộc gọi video' : 'Cuộc gọi thoại'}</span></header>
      {video && <div className="remote-video" ref={remote} />}
      {showPeer && <div className="call-person">
        <Avatar name={peer?.displayName} photo={peer?.photoUrl} size={100} />
        <h2>{peer?.displayName || 'Người dùng UTEChat'}</h2>
        <p>{incoming ? 'Cuộc gọi đến' : connected ? durationLabel(controller.seconds) :
          call.status === 'accepted' ? 'Đang kết nối…' : 'Đang gọi…'}</p>
      </div>}
      {!incoming && video && <div className="self-video">
        {controller.videoOff ? <VideoOff size={24} /> : controller.media.localTrack ? <div ref={local} /> :
          <video ref={preview} autoPlay muted playsInline />}
      </div>}
      <div className="call-buttons">
        {incoming ? <button className="call-button accept" disabled={controller.busy} onClick={controller.answer}>
          {video ? <Video size={23} /> : <Phone size={23} />}<span>Trả lời</span></button> : <>
          <button className="call-button" disabled={controller.busy} onClick={controller.toggleMute}>
            {controller.muted ? <MicOff size={23} /> : <Mic size={23} />}<span>{controller.muted ? 'Bật mic' : 'Tắt mic'}</span></button>
          {video && <button className="call-button" disabled={controller.busy} onClick={controller.toggleVideo}>
            {controller.videoOff ? <VideoOff size={23} /> : <Video size={23} />}<span>{controller.videoOff ? 'Bật camera' : 'Tắt camera'}</span></button>}
          {connected && <button className="call-button" onClick={controller.playAudio}><Bell size={23} /><span>Phát âm thanh</span></button>}
        </>}
        <button className="call-button hangup" disabled={controller.busy} onClick={controller.finish}><PhoneOff size={23} />
          <span>{incoming ? 'Từ chối' : 'Kết thúc'}</span></button>
      </div>
      {controller.busy && <p className="permission-status" role="status">Đang xử lý…</p>}
    </div>
  </dialog>
}
