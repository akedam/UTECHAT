import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { ArrowLeft, Camera, ChevronDown, Clock, CloudSun, Download, Flag, Grid2X2, Images, MapPin, Music, Palette, RotateCw, Send, Share, Shield, SmilePlus, Star, Trash2, Type, UsersRound, X } from 'lucide-react'
import { api } from './firebase'
import { blockUser, openDirect } from './chat-data'
import { Avatar, Empty, IconButton, Modal, useOnline } from './ui'
import { TinCamera } from './tin-camera'

const valid = id => /^[a-f0-9]{64}$/.test(id || '')
export const momentFromUrl = () => {
  const id = new URLSearchParams(location.search).get('moment')
  return valid(id) ? id : null
}
const route = id => '/api/moment?id=' + encodeURIComponent(id)
const photo = (id, signal) => api('/api/moment-photo?id=' + encodeURIComponent(id), { blob: true, signal })
const respond = (id, action, value, rest) => api('/api/moment', { body: { id, action, value, ...rest } })
const reasons = [['spam', 'Tin rác'], ['abuse', 'Nội dung không phù hợp'], ['other', 'Lý do khác']]
const reactions = ['❤️', '😂', '😮', '😢', '👍']
const captions = [
  ['accent', 'Văn bản', '', 'Nhập chú thích'],
  ['rose', 'Màu hồng', '', 'Nhập chú thích'],
  ['mint', 'Màu xanh', '', 'Nhập chú thích'],
  ['rating', 'Đánh giá', '⭐ ', 'Ví dụ 5/5'],
  ['music', 'Đang nghe', '🎵 ', 'Tên bài hát'],
  ['place', 'Vị trí', '📍 ', 'Tên địa điểm'],
  ['weather', 'Thời tiết', '☀️ ', 'Ví dụ Trời nắng'],
  ['time', 'Giờ hiện tại'],
  ['rose', '🤳 Hôm nay', '🤳 Hôm nay'],
  ['mint', '🌿 Một chút bình yên', '🌿 Một chút bình yên'],
  ['accent', '☀️ Chào ngày mới', '☀️ Chào ngày mới']
]
const captionIcons = [Type, Palette, Palette, Star, Music, MapPin, CloudSun, Clock]
const publishErrors = {
  'moment-friend-required': 'Một người nhận không còn có thể xem tin. Hãy chọn lại.',
  'media-not-configured': 'Máy chủ chưa bật kho ảnh cho Tin. Ảnh vẫn ở màn hình này.',
  'moment-storage-unavailable': 'Kho ảnh đang gián đoạn. Hãy thử đăng lại.',
  'invalid-image': 'Ảnh không đúng định dạng. Hãy chọn hoặc chụp ảnh khác.',
  'too-large': 'Ảnh quá lớn. Hãy chọn hoặc chụp ảnh khác.',
  'daily-upload-limit': 'Bạn đã dùng hết dung lượng đăng ảnh trong ngày.',
  'moment-conflict': 'Lần đăng trước đã thay đổi. Hãy bỏ ảnh và chọn lại.',
  'unauthorized': 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.',
  'not-found': 'Máy chủ đang chạy phiên bản chưa hỗ trợ đăng Tin.'
}

function elapsed(time) {
  const minutes = Math.max(0, Math.floor((Date.now() - time) / 60_000))
  if (!minutes) return 'Vừa xong'
  if (minutes < 60) return minutes + ' phút trước'
  if (minutes < 1440) return Math.floor(minutes / 60) + ' giờ trước'
  if (minutes < 10_080) return Math.floor(minutes / 1440) + ' ngày trước'
  return new Intl.DateTimeFormat('vi-VN', { day: 'numeric', month: 'numeric', year: 'numeric' }).format(time)
}

function TinPhoto({ id, eager = false, opened, zoom, label = 'Xem ảnh toàn màn hình', caption, style }) {
  const element = useRef(null)
  const pictureElement = useRef(null)
  const [visible, setVisible] = useState(eager)
  const [result, setResult] = useState(null)
  const [attempt, retry] = useState(0)
  useEffect(() => {
    if (visible) return
    if (!window.IntersectionObserver) { setVisible(true); return }
    const observer = new IntersectionObserver(([entry]) => { if (entry.isIntersecting) setVisible(true) })
    observer.observe(element.current)
    return () => observer.disconnect()
  }, [visible])
  useEffect(() => {
    if (!visible) return
    const controller = new AbortController()
    let url
    photo(id, controller.signal).then(blob => {
      if (controller.signal.aborted) return
      url = URL.createObjectURL(blob)
      setResult({ id, attempt, url })
    }).catch(error => {
      if (!controller.signal.aborted) setResult({ id, attempt, error })
    })
    return () => { controller.abort(); if (url) URL.revokeObjectURL(url) }
  }, [id, visible, attempt])
  const image = result?.id === id && result.attempt === attempt ? result : null
  useEffect(() => {
    if (pictureElement.current?.naturalWidth) opened?.()
  }, [opened])
  const content = image?.url && <><img ref={pictureElement} src={image.url} onLoad={opened}
    onError={() => setResult({ id, attempt, error: new Error('Không đọc được ảnh.') })} alt="Ảnh tin" />
    {caption && <span className={`tin-caption ${style || ''}`}>{caption}</span>}</>
  return <div className="tin-photo" ref={element}>
    {image?.url ? zoom ? <button className="tin-photo-link" onClick={zoom} aria-label={label}>{content}</button> : content :
      image?.error?.httpStatus === 404 ? <span>Tin không còn khả dụng</span> :
        image?.error ? <button className="text-button" onClick={() => retry(value => value + 1)}>Thử tải lại ảnh</button> :
          <span>Đang tải ảnh…</span>}
  </div>
}

function download(blob) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'utechat-tin.jpg'
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}

function ReplyTin({ item, enterChat, error, online, onReact }) {
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [showReactions, setShowReactions] = useState(false)
  const nonce = useRef(null)
  const reacting = emoji => {
    setShowReactions(false)
    onReact(item, emoji)
  }
  return <div className="tin-reply-area"><form className="tin-reply" onSubmit={async event => {
    event.preventDefault()
    if (!online || !value.trim() || busy) return
    setBusy(true)
    try {
      const conversationId = await openDirect(item.owner)
      await respond(item.id, 'reply', value, { conversationId, nonce: nonce.current ||= crypto.randomUUID() })
      enterChat(conversationId)
    } catch (failure) { error(failure) }
    finally { setBusy(false) }
  }}>
    <input value={value} aria-label={`Trả lời tin của ${item.name}`} placeholder="Trả lời bằng tin nhắn…" maxLength={2000}
      disabled={busy} onChange={event => { setValue(event.target.value); setShowReactions(false); nonce.current = null }} />
    {value.trim() ? <IconButton icon={Send} label="Gửi trả lời" type="submit" disabled={!online || busy} /> :
      <div className="tin-quick-reactions">
        {['❤️', '😂', '👍'].map(emoji => <button key={emoji} type="button" aria-pressed={item.reaction === emoji} disabled={!online}
          onClick={() => reacting(emoji)}>{emoji}</button>)}
        <IconButton icon={SmilePlus} label="Thêm cảm xúc" aria-expanded={showReactions} disabled={!online}
          onClick={() => setShowReactions(!showReactions)} />
      </div>}
  </form>
  {showReactions && !value.trim() && <div className="reaction-bar tin-reactions" aria-label="Cảm xúc về tin">
    {reactions.map(emoji => <button key={emoji} aria-pressed={item.reaction === emoji} onClick={() => reacting(emoji)}>{emoji}</button>)}
  </div>}</div>
}

export function Moments({ initialId, initialHistory, friends, people, account, clearLink, openLink, enterChat, openProfile, error, notify }) {
  const [page, setPage] = useState(initialId ? 'detail' : 'feed')
  const [id, setId] = useState(initialId)
  const [filter, setFilter] = useState(initialHistory ? 'mine' : 'all')
  const [chooseFilter, showFilter] = useState(false)
  const [grid, setGrid] = useState(Boolean(initialHistory))
  const [storyIndex, setStoryIndex] = useState(-1)
  const [feed, setFeed] = useState({ items: [], next: null })
  const [item, setItem] = useState(null)
  const [revision, refresh] = useState(0)
  const [loading, setLoading] = useState(false)
  const [problem, setProblem] = useState(null)
  const [sheet, setSheet] = useState('')
  const [reason, setReason] = useState('spam')
  const [picture, setPicture] = useState(null)
  const [preview, setPreview] = useState('')
  const [caption, setCaption] = useState('')
  const [captionChoice, setCaptionChoice] = useState(null)
  const [captionStyle, setCaptionStyle] = useState('')
  const [audience, setAudience] = useState(null)
  const [busy, setBusy] = useState(false)
  const [feedback, setFeedback] = useState('')
  const [clear, setClear] = useState(0)
  const [link, setLink] = useState('')
  const online = useOnline()
  const stream = useRef(null)
  const jumpTo = useRef(0)
  const requestedCursor = useRef(null)
  const seenRequests = useRef(new Set())
  const nonce = useRef(null)
  const loadMore = useRef(null)
  const sendRequest = useRef(null)
  const pageTop = useRef(null)
  const view = filter === 'mine' ? 'mine' : filter === 'all' ? 'all' : 'received'
  const choices = audience === null ? friends.map(friend => friend.id) : audience.filter(id => friends.some(friend => friend.id === id))
  const activeName = friends.find(friend => friend.id === filter)?.displayName || (filter === 'mine' ? 'Bạn' : 'Mọi người')
  const eligibleCards = feed.items.filter(card => filter === 'all' || filter === 'mine' || card.owner === filter)
  const inHistory = grid && view === 'mine'
  const personalBack = inHistory && initialHistory

  useEffect(() => {
    if (!picture) { setPreview(''); return }
    const url = URL.createObjectURL(picture)
    setPreview(url)
    return () => URL.revokeObjectURL(url)
  }, [picture])
  useEffect(() => () => sendRequest.current?.abort(), [])
  useEffect(() => {
    if (page !== 'feed') return
    const controller = new AbortController()
    loadMore.current = controller
    requestedCursor.current = null
    setLoading(true); setProblem(null); setFeed({ items: [], next: null })
    api('/api/moments?view=' + view, { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setFeed(result) })
      .catch(failure => { if (!controller.signal.aborted) setProblem(failure) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => { controller.abort(); loadMore.current?.abort() }
  }, [view, revision, online, page])
  useEffect(() => {
    if (page !== 'detail' || !id) return
    const controller = new AbortController()
    setLoading(true); setProblem(null); setItem(null)
    api(route(id), { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setItem(result) })
      .catch(failure => { if (!controller.signal.aborted) setProblem(failure) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [id, page, revision, online])
  useEffect(() => {
    if (page === 'feed' && !grid && !loading && storyIndex >= eligibleCards.length) {
      setStoryIndex(eligibleCards.length - 1)
    }
  }, [page, grid, loading, storyIndex, eligibleCards.length])
  useLayoutEffect(() => {
    const node = stream.current
    if (node && jumpTo.current !== null) {
      node.scrollTop = node.clientHeight * jumpTo.current
      jumpTo.current = null
    }
  }, [grid, page])
  useEffect(() => {
    if (page !== 'feed' || grid || loading || !feed.next || requestedCursor.current === feed.next ||
        storyIndex < eligibleCards.length - 2) return
    requestedCursor.current = feed.next
    void more()
  }, [page, grid, loading, feed.next, storyIndex, eligibleCards.length])

  function showDetail(card) {
    setId(card.id); setItem(card); setProblem(null); setLoading(true); setPage('detail')
    openLink(card.id)
    pageTop.current?.scrollIntoView({ block: 'start' })
  }
  function showFeed() {
    clearLink(); setId(null); setItem(null); setProblem(null); setPage('feed')
  }
  function restart() {
    setPicture(null); setCaption(''); setCaptionStyle(''); setCaptionChoice(null)
    setAudience(null); setFeedback(''); nonce.current = null
    jumpTo.current = 0
    setClear(value => value + 1); setGrid(false); setStoryIndex(-1)
    showFeed()
    pageTop.current?.scrollIntoView({ block: 'start' })
  }
  async function more() {
    if (loading || !feed.next) return
    const controller = new AbortController()
    loadMore.current = controller
    setLoading(true); setProblem(null)
    try {
      const result = await api('/api/moments?view=' + view + '&before=' + encodeURIComponent(feed.next), { signal: controller.signal })
      if (!controller.signal.aborted) setFeed(previous => ({ items: [...previous.items, ...result.items], next: result.next }))
    } catch (failure) { if (!controller.signal.aborted) setProblem(failure) }
    finally { if (!controller.signal.aborted) setLoading(false) }
  }
  function updateStory(event) {
    const { scrollTop, clientHeight } = event.currentTarget
    if (clientHeight) setStoryIndex(Math.max(-1, Math.min(eligibleCards.length - 1, Math.round(scrollTop / clientHeight) - 1)))
  }
  function navigateByKey(event) {
    if (event.target !== event.currentTarget) return
    const direction = { ArrowUp: -1, PageUp: -1, ArrowDown: 1, PageDown: 1 }[event.key]
    if (!direction) return
    event.preventDefault()
    const node = event.currentTarget
    const next = Math.max(0, Math.min(eligibleCards.length, storyIndex + 1 + direction))
    node.scrollTo({ top: next * node.clientHeight, behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
  }
  async function action(work) {
    try { await work() } catch (failure) { error(failure) }
  }
  async function publish() {
    if (busy || !online || !picture || choices.length < 1 || choices.length > 100) return
    const controller = new AbortController()
    sendRequest.current = controller
    setBusy(true); setFeedback('')
    try {
      const buffer = new Uint8Array(await picture.arrayBuffer())
      let binary = ''
      for (let offset = 0; offset < buffer.length; offset += 8192) {
        binary += String.fromCharCode(...buffer.subarray(offset, offset + 8192))
      }
      const content = { caption: caption.trim(), captionStyle: caption.trim() ? captionStyle || 'accent' : null,
        recipients: choices, image: btoa(binary) }
      const fingerprint = JSON.stringify([[...choices].sort(), content.caption, content.captionStyle])
      if (nonce.current?.fingerprint !== fingerprint) nonce.current = { fingerprint, id: crypto.randomUUID() }
      await api('/api/moments', { method: 'PUT', signal: controller.signal, body: { ...content, nonce: nonce.current.id } })
      restart()
      setFilter('all')
      notify('Đã đăng tin.')
    } catch (failure) {
      if (!controller.signal.aborted) setFeedback(publishErrors[failure.serverCode] ||
        (failure.httpStatus === 404 ? publishErrors['not-found'] : failure.message) || 'Chưa đăng được tin. Bạn có thể thử lại.')
    } finally { setBusy(false); sendRequest.current = null }
  }
  function share(card) {
    setId(card.id); setItem(card); setSheet('share'); setLink('')
  }
  function shareLink() {
    const target = new URL(location.origin)
    target.searchParams.set('moment', item.id)
    const url = target.toString()
    return action(async () => {
      if (navigator.share) {
        try { await navigator.share({ title: 'Tin trên UTEChat', url }); return }
        catch (failure) { if (failure.name === 'AbortError') return }
      }
      try { await navigator.clipboard.writeText(url); notify('Đã sao chép liên kết tin.') }
      catch { setLink(url) }
    })
  }
  function markSeen(card) {
    if (card.own || card.seen || seenRequests.current.has(card.id)) return
    seenRequests.current.add(card.id)
    respond(card.id, 'seen').then(updated => {
      setItem(current => current?.id === updated.id ? updated : current)
      setFeed(current => ({ ...current, items: current.items.map(row => row.id === updated.id ? { ...row, seen: true } : row) }))
    }).catch(() => {}).finally(() => seenRequests.current.delete(card.id))
  }
  function reactToTin(card, emoji) {
    return action(async () => {
      const updated = await respond(card.id, 'react', card.reaction === emoji ? null : emoji)
      setItem(current => current?.id === updated.id ? updated : current)
      setFeed(current => ({ ...current, items: current.items.map(row => row.id === updated.id ? updated : row) }))
    })
  }
  function showGrid() {
    if (page !== 'feed') showFeed()
    setGrid(true); showFilter(false)
    pageTop.current?.scrollIntoView({ block: 'start' })
  }
  function showCamera() {
    jumpTo.current = 0
    setGrid(false); setFilter('all'); setStoryIndex(-1); showFilter(false)
    pageTop.current?.scrollIntoView({ block: 'start' })
  }
  function showCaption() { setCaptionChoice(null); setSheet('caption') }
  const byline = card => <div className="tin-byline">
    <Avatar name={card.name} photo={people[card.owner]?.photoUrl || (card.own ? account.photoUrl : undefined)} size={38} />
    <strong>{card.own ? 'Bạn' : card.name}</strong><time dateTime={new Date(card.createdAt).toISOString()}>{elapsed(card.createdAt)}</time>
    {!card.seen && <i className="tin-unseen" aria-label="Tin chưa xem" />}
  </div>
  const storyTools = (card, label = 'Chia sẻ tin') => <div className="tin-story-tools">
    <IconButton icon={Grid2X2} label="Xem tất cả tin" onClick={showGrid} />
    <IconButton icon={Share} label={label} onClick={() => share(card)} />
  </div>

  return <section className={`tin-page${page === 'feed' && !grid ? ' tin-is-paging' : ''}`} aria-label="Tin" ref={pageTop}>
    <header className="tin-header">
      {page === 'preview' ? <span aria-hidden="true" /> : page === 'feed' ?
        storyIndex >= 0 && !grid ? <span aria-hidden="true" /> :
          <IconButton icon={personalBack ? ArrowLeft : grid ? Camera : RotateCw}
            label={personalBack ? 'Về hồ sơ' : grid ? 'Về máy ảnh' : 'Làm mới tin'}
            onClick={personalBack ? openProfile : grid ? showCamera : () => refresh(value => value + 1)} /> : <IconButton icon={ArrowLeft} label="Quay lại Tin" onClick={() => {
        if (page === 'full') setPage('detail')
        else showFeed()
      }} disabled={busy} />}
      {page === 'feed' && inHistory ? <h1>Lịch sử cá nhân</h1> : page === 'feed' || page === 'detail' ? <div className="tin-filter">
        <button aria-expanded={chooseFilter} onClick={() => showFilter(!chooseFilter)}>{activeName}<ChevronDown size={17} /></button>
        {chooseFilter && <div className="tin-filter-menu" aria-label="Lọc người đăng">
          {[['all', 'Mọi người'], ['mine', 'Bạn'], ...friends.map(friend => [friend.id, friend.displayName])].map(([key, name]) =>
            <button key={key} aria-pressed={filter === key} onClick={() => { showGrid(); setStoryIndex(-1); setFilter(key) }}>
              {key !== 'all' && <Avatar name={name} photo={key === 'mine' ? account.photoUrl : people[key]?.photoUrl} size={28} />}{name}
            </button>)}
        </div>}
      </div> : page === 'preview' ? <div className="tin-filter">
        <button disabled={busy} onClick={() => setSheet('audience')}>Gửi đến <ChevronDown size={17} /></button>
      </div> : <h1>Ảnh tin</h1>}
      {page === 'preview' ? <IconButton icon={Download} label="Lưu ảnh về máy" onClick={() => download(picture)} disabled={busy} />
        : <button className="tin-profile" aria-label="Mở hồ sơ của bạn" onClick={openProfile}>
          <Avatar name={account.displayName} photo={account.photoUrl} size={36} /></button>}
    </header>
    {!online && <p className="feedback error" role="status">Mất kết nối. Bạn vẫn có thể chuẩn bị tin.</p>}
    {page === 'feed' && <>
      {grid ? <div className="tin-grid">
        {eligibleCards.map((card, index) => <article key={card.id}>
          <TinPhoto id={card.id} zoom={() => {
            if (card.own) showDetail(card)
            else { jumpTo.current = index + 1; setGrid(false); setStoryIndex(index) }
          }} label={`Mở tin của ${card.name}`} />
        </article>)}
      </div> : <div className="tin-stream" ref={stream} tabIndex={0} aria-label="Luồng Tin"
        onScroll={updateStory} onKeyDown={navigateByKey}>
        <section className="tin-stream-page tin-camera-page" aria-label="Trang máy ảnh"
          inert={storyIndex >= 0} aria-hidden={storyIndex >= 0 || undefined}>
          <TinCamera active={storyIndex < 0} clear={clear} error={error} sendPhoto={blob => {
            setPicture(blob); setPage('preview'); setFeedback('')
          }} />
          <div className="tin-stream-tools">
            {(eligibleCards.length || feed.next) ? <span><ChevronDown size={18} aria-hidden="true" />Vuốt lên để xem Tin</span> :
              <span role="status">{loading ? 'Đang tải tin…' : problem ? 'Chưa tải được tin' : 'Chưa có Tin'}</span>}
            <IconButton icon={Grid2X2} label="Xem tất cả tin" onClick={showGrid} />
          </div>
        </section>
        {eligibleCards.map((card, index) => <section key={card.id} className="tin-stream-page"
          inert={index !== storyIndex} aria-hidden={index !== storyIndex || undefined}>
          {Math.abs(index - storyIndex) <= 1 && <article className="tin-detail tin-story-page">
            <TinPhoto id={card.id} eager opened={index === storyIndex ? () => markSeen(card) : undefined}
              caption={card.caption} style={card.captionStyle} />
            {byline(card)}
            {!card.own && <ReplyTin item={card} {...{ enterChat, error, online }} onReact={reactToTin} />}
            {storyTools(card)}
          </article>}
        </section>)}
      </div>}
      {loading && grid ? <p className="loader" role="status">Đang tải tin…</p> : problem ?
        <button className="secondary" onClick={() => feed.next ? more() : refresh(value => value + 1)}>Thử tải Tin lại</button>
        : !eligibleCards.length && !feed.next && grid ? <Empty icon={Images} title={inHistory ? 'Bạn chưa đăng tin nào' : 'Chưa có tin ở đây'} /> : null}
      {grid && feed.next && !loading && <button className="secondary wide" onClick={() => more()}>Xem thêm tin</button>}
    </>}
    {page === 'preview' && <div className="tin-preview">
      <div className="tin-photo">{preview && <img src={preview} alt="Ảnh xem trước" />}
        <input className={`tin-caption tin-caption-input ${caption ? captionStyle : 'tin-caption-placeholder'}`}
          aria-label="Thêm chú thích" placeholder="Thêm chú thích" maxLength={150} value={caption} disabled={busy}
          onChange={event => { setCaption(event.target.value); setCaptionStyle(captionStyle || 'accent') }}
          onKeyDown={event => { if (event.key === 'Enter' && !event.nativeEvent.isComposing) event.currentTarget.blur() }} /></div>
      <div className="tin-capture-controls">
        <IconButton icon={X} label="Bỏ ảnh" onClick={restart} disabled={busy} />
        <IconButton className="tin-publish" icon={Send} label={busy ? 'Đang đăng…' : feedback ? 'Đăng lại' : 'Đăng'}
          disabled={busy || !online || choices.length < 1 || choices.length > 100} onClick={publish} />
        <IconButton icon={Type} label="Chọn chú thích" disabled={busy} onClick={showCaption} />
      </div>
      <div className="tin-audience" aria-label="Người xem tin">
        <button disabled={busy} aria-pressed={audience === null} onClick={() => setAudience(null)}>
          <span><UsersRound size={22} /></span><small>Tất cả</small></button>
        {friends.map(friend => <button key={friend.id} disabled={busy} aria-pressed={audience !== null && choices.includes(friend.id)}
          onClick={() => setAudience(audience === null ? [friend.id] : choices.includes(friend.id)
            ? choices.filter(id => id !== friend.id) : [...choices, friend.id])}>
          <span><Avatar name={friend.displayName} photo={friend.photoUrl} size={38} /></span><small>{friend.displayName}</small>
        </button>)}
      </div>
      {!choices.length && <p className="muted">Chọn ít nhất một bạn để đăng tin.</p>}
      {choices.length > 100 && <p className="muted">Chọn tối đa 100 bạn cho một tin.</p>}
      {feedback && <p className="feedback error" role="alert">{feedback}</p>}
    </div>}
    {page === 'detail' && (item ? <article className="tin-detail">
      <TinPhoto id={item.id} eager zoom={() => setPage('full')} opened={() => markSeen(item)} caption={item.caption} style={item.captionStyle} />
      {byline(item)}
      {!item.own && <ReplyTin key={item.id} {...{ item, enterChat, error, online }} onReact={reactToTin} />}
      {item.own && <details className="tin-viewers"><summary>Người xem · {item.recipients.filter(peer => peer.seen).length}/{item.recipients.length}</summary>
        {item.recipients.map(peer => <p key={peer.id}>{people[peer.id]?.displayName || 'Bạn bè'} · {peer.seen ? 'Đã xem' : 'Chưa xem'} {peer.reaction || ''}</p>)}</details>}
      {storyTools(item, 'Chia sẻ')}
    </article> : loading ? <p className="loader">Đang mở tin…</p> : <Empty icon={Images} title="Không mở được tin"
      description={problem?.httpStatus === 404 ? 'Tin đã bị xóa hoặc bạn không còn quyền xem.' : undefined}
      action={problem?.httpStatus !== 404 && <button className="secondary" onClick={() => refresh(value => value + 1)}>Thử lại</button>} />)}
    {page === 'full' && <div className="tin-full"><TinPhoto id={id} eager /></div>}
    {sheet && <Modal title={{ caption: 'Chú thích', audience: 'Ai có thể xem tin?', share: 'Chia sẻ tin', report: 'Báo cáo tin',
      block: 'Chặn người dùng', remove: 'Xóa tin' }[sheet]} onClose={() => setSheet('')} className="tin-sheet">
      {sheet === 'caption' && <>
        <div className="tin-caption-options">{captions.map((option, index) => {
          const Symbol = captionIcons[index]
          return <button key={index} className={option[0]} onClick={() => {
            const [style, , value, hint] = option
            setCaptionStyle(style)
            if (hint) { setCaption(value || (captionStyle === style ? caption : '')); setCaptionChoice(option) }
            else { setCaption(style === 'time' ? new Intl.DateTimeFormat('vi-VN', { timeStyle: 'short' }).format() : value); setSheet('') }
          }}>{Symbol && <Symbol size={17} aria-hidden="true" />}{option[1]}</button>
        })}</div>
        {captionChoice && <label className="tin-caption-field">{captionChoice[3]}<textarea autoFocus maxLength={150} rows={2}
          value={caption} onChange={event => setCaption(event.target.value)} /></label>}
        <div className="button-row"><button className="secondary" onClick={() => { setCaption(''); setCaptionStyle(''); setSheet('') }}>Xóa chú thích</button>
          <button className="primary" onClick={() => setSheet('')}>Xong</button></div>
      </>}
      {sheet === 'audience' && <div className="stack">
        <label className="tin-check"><input type="checkbox" checked={audience === null} onChange={event => setAudience(event.target.checked ? null : [])} />Tất cả bạn bè</label>
        <div className="picker">{friends.map(friend => <label key={friend.id}>
          <Avatar name={friend.displayName} photo={friend.photoUrl} size={34} />{friend.displayName}
          <input type="checkbox" checked={choices.includes(friend.id)} onChange={event => {
            setAudience(event.target.checked ? [...choices, friend.id] : choices.filter(id => id !== friend.id))
          }} />
        </label>)}</div>
        <button className="primary" onClick={() => setSheet('')}>Xong</button>
      </div>}
      {sheet === 'share' && item && <>
        <div className="tin-share-photo"><TinPhoto id={item.id} eager caption={item.caption} style={item.captionStyle} /></div>
        <div className="tin-share-actions">
          <button onClick={() => action(async () => download(await photo(item.id)))}><Download size={21} />Lưu</button>
          <button onClick={shareLink}><Share size={21} />Chia sẻ</button>
          {!item.own && <><button onClick={() => setSheet('report')}><Flag size={21} />Báo cáo</button>
            <button onClick={() => setSheet('block')}><Shield size={21} />Chặn</button></>}
          <button className="danger" onClick={() => setSheet('remove')}><Trash2 size={21} />Xóa</button>
        </div>
        {link && <input aria-label="Liên kết tin" value={link} readOnly onFocus={event => event.target.select()} />}
      </>}
      {sheet === 'report' && <div className="stack"><label>Lý do<select value={reason} onChange={event => setReason(event.target.value)}>
        {reasons.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <button className="primary" onClick={() => action(async () => { await respond(id, 'report', reason); setSheet(''); notify('Đã gửi báo cáo.') })}>Gửi báo cáo</button></div>}
      {sheet === 'block' && <div className="stack"><p>Chặn {item?.name} và ẩn tin của người này?</p><button className="danger" onClick={() => action(async () => {
        await blockUser(item.owner, true); setSheet(''); showFeed(); refresh(value => value + 1); notify('Đã chặn người dùng.')
      })}>Chặn người dùng</button></div>}
      {sheet === 'remove' && <div className="stack"><p>{item.own ? 'Ảnh sẽ biến mất khỏi Tin của bạn và của người nhận.'
        : 'Tin sẽ được ẩn khỏi bảng Tin của riêng bạn.'}</p><button className="danger" onClick={() => action(async () => {
        if (item.own) await api(route(id), { method: 'DELETE' })
        else await respond(id, 'hide')
        setSheet(''); showFeed(); refresh(value => value + 1); notify(item.own ? 'Đã xóa tin.' : 'Đã ẩn tin.')
      })}>Xóa tin</button></div>}
    </Modal>}
  </section>
}
