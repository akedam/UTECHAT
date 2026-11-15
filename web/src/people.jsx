import { useEffect, useRef, useState } from 'react'
import {
  ArrowDownToLine, Bell, Camera, ChevronRight, CircleUserRound, Copy, ImagePlus,
  Eye, LockKeyhole, LogOut, MessageCircle, Phone, Plus, QrCode, Search, Shield, Users, UsersRound, Video
} from 'lucide-react'
import {
  answerFriend, blockUser, changeMembership, createGroup, deleteGroup, isOnline, profile, qrPrefix, renameGroup,
  searchPeople, sendFriend, setDeputy, setVisibility, transferGroup, upgradeGroup, watchMembers, watchPresence
} from './chat-data'
import { configureNotifications, disableNotifications, notificationsEnabled, notificationPreferences, saveNotificationPreferences } from './notifications'
import { Avatar, Empty, IconButton, Modal } from './ui'

export function PersonRow({ person, note, onClick, online = false, children }) {
  return <div className="person-row">
    <button className="person-main" onClick={onClick} disabled={!onClick}>
      <Avatar name={person.displayName} photo={person.photoUrl} online={online} />
      <span className="row-text"><strong>{person.displayName}</strong>{note && <small>{note}</small>}</span>
    </button>
    {children && <div className="person-actions">{children}</div>}
  </div>
}

export function Friends({ uid, people, friendships, open, call, blocked, error, setDialog }) {
  const [section, setSection] = useState('friends')
  const [online, setOnline] = useState({})
  const accepted = friendships.filter(row => row.status === 'accepted')
  const pending = friendships.filter(row => row.status === 'pending')
  const incoming = pending.filter(row => row.toUid === uid).length
  const other = row => row.fromUid === uid ? row.toUid : row.fromUid
  const peerIds = accepted.map(other).filter(id => !blocked.includes(id)).sort().join(',')
  useEffect(() => {
    const stops = (peerIds ? peerIds.split(',') : []).map(id => watchPresence(id, presence =>
      setOnline(previous => ({ ...previous, [id]: isOnline(presence) }))))
    return () => stops.forEach(stop => stop())
  }, [peerIds])
  const list = (section === 'requests' ? pending : accepted.filter(row => section !== 'online' ||
    (online[other(row)] && !blocked.includes(other(row))))).slice().sort((a, b) =>
    (people[other(a)]?.displayName || '').localeCompare(people[other(b)]?.displayName || '', 'vi'))
  return <div className="tab-page">
    <div className="segmented" aria-label="Danh sách bạn bè">
      <button aria-pressed={section === 'friends'} onClick={() => setSection('friends')}>Bạn bè</button>
      <button aria-pressed={section === 'online'} onClick={() => setSection('online')}>Trực tuyến</button>
      <button aria-pressed={section === 'requests'} onClick={() => setSection('requests')}>Lời mời{incoming > 0 && <span className="mini-badge">{incoming}</span>}</button>
    </div>
    {list.length ? list.map(row => {
      const id = other(row)
      const person = people[id] || { displayName: 'Người dùng UTEChat' }
      if (row.status === 'accepted') return <PersonRow key={id} person={person}
        online={!blocked.includes(id) && online[id]} onClick={() => open(id)}>
        <IconButton icon={Phone} label={`Gọi thoại với ${person.displayName}`} onClick={() => call(id, 'audio')} disabled={blocked.includes(id)} />
        <IconButton icon={Video} label={`Gọi video với ${person.displayName}`} onClick={() => call(id, 'video')} disabled={blocked.includes(id)} />
      </PersonRow>
      const received = row.toUid === uid
      return <PersonRow key={id} person={person} note={received ? 'Đã gửi lời mời kết bạn' : 'Đang chờ phản hồi'}>
        {received && <button className="primary compact" onClick={() => answerFriend(row.id, true).catch(error)}>Chấp nhận</button>}
        <button className="text-button" onClick={() => answerFriend(row.id, false).catch(error)}>{received ? 'Từ chối' : 'Thu hồi'}</button>
      </PersonRow>
    }) : <Empty icon={UsersRound} title={section === 'online' ? 'Chưa có bạn trực tuyến' : section === 'friends' ? 'Chưa có bạn bè' : 'Chưa có lời mời'}
      description={section === 'friends' ? 'Tìm bạn theo tên hoặc quét mã QR để kết bạn.' : undefined}
      action={section === 'friends' && <button className="secondary" onClick={() => setDialog({ type: 'search' })}><Plus size={17} />Tìm bạn</button>} />}
  </div>
}

function PersonResult({ person, friendships, uid, open, error, close }) {
  const [sending, setSending] = useState(false)
  const friendship = friendships.find(row => [row.fromUid, row.toUid].includes(person.id))
  return <PersonRow person={person} onClick={friendship?.status === 'accepted' ? () => { close(); open(person.id) } : undefined}>
    {friendship?.status === 'accepted' ? <button className="secondary compact" onClick={() => { close(); open(person.id) }}>Nhắn tin</button>
      : friendship?.status === 'pending'
        ? friendship.toUid === uid
          ? <button className="primary compact" onClick={() => answerFriend(friendship.id, true).catch(error)}>Chấp nhận</button>
          : <span className="muted">Đã gửi lời mời</span>
        : <button className="primary compact" disabled={sending} onClick={async () => {
          setSending(true)
          try { await sendFriend(person.id) } catch (failure) { error(failure) } finally { setSending(false) }
        }}><Plus size={16} /> Kết bạn</button>}
  </PersonRow>
}

export function SearchDialog({ uid, conversations, titleOf, friendships, friends, open, select, close, error }) {
  const [value, setValue] = useState('')
  const [found, setFound] = useState([])
  const [loading, setLoading] = useState(false)
  const entered = value.trim()
  useEffect(() => {
    let cancelled = false
    if (!entered) { setFound([]); setLoading(false); return }
    setLoading(true)
    const timer = setTimeout(() => {
      searchPeople(entered).then(list => { if (!cancelled) setFound(list) })
        .catch(failure => { if (!cancelled) error(failure) })
        .finally(() => { if (!cancelled) setLoading(false) })
    }, 250)
    return () => { cancelled = true; clearTimeout(timer) }
  }, [entered, error])
  const key = entered.toLocaleLowerCase('vi')
  const matches = key ? conversations.filter(item => titleOf(item).name.toLocaleLowerCase('vi').includes(key)) : []
  const matchingFriends = key ? friends.filter(person => person.displayName.toLocaleLowerCase('vi').includes(key)) : []
  const friendIds = new Set(matchingFriends.map(person => person.id))
  const results = found.filter(person => !friendIds.has(person.id))
  return <Modal title="Tìm kiếm" onClose={close} onBack={close} className="search-dialog app-screen">
    <div className="search-box"><Search size={20} /><input autoFocus aria-label="Tìm bạn hoặc hội thoại" placeholder="Nhập tên bạn hoặc cuộc trò chuyện"
      value={value} onChange={event => setValue(event.target.value)} /></div>
    {!entered && <Empty icon={Search} title="Tìm bạn và hội thoại" description="Nhập tên để tìm kiếm." />}
    {matchingFriends.length > 0 && <><h3 className="section-label">Bạn bè</h3>{matchingFriends.map(person =>
      <PersonResult key={person.id} {...{ person, friendships, uid, open, error, close }} />)}</>}
    {matches.length > 0 && <><h3 className="section-label">Hội thoại</h3>{matches.map(item => {
      const { name, photo } = titleOf(item)
      return <PersonRow key={item.id} person={{ displayName: name, photoUrl: photo }} onClick={() => { close(); select(item.id) }}><ChevronRight size={17} /></PersonRow>
    })}</>}
    {results.length > 0 && <><h3 className="section-label">Người dùng</h3>{results.map(person =>
      <PersonResult key={person.id} {...{ person, friendships, uid, open, error, close }} />)}</>}
    {loading && <p className="loader" role="status">Đang tìm…</p>}
    {entered && !loading && !results.length && !matches.length && !matchingFriends.length && <Empty icon={Search} title="Không tìm thấy kết quả" description="Thử tên hiển thị khác." />}
  </Modal>
}

export function QrDialog({ user, friendships, open, error, close }) {
  const [mode, setMode] = useState('show')
  const [url, setUrl] = useState('')
  const [qrFile, setQrFile] = useState(null)
  const [person, setPerson] = useState(null)
  const [input, setInput] = useState('')
  const [scanning, setScanning] = useState(false)
  const video = useRef(null)
  const media = useRef(null)
  const generation = useRef(0)
  const handling = useRef(false)
  const code = qrPrefix + user.uid
  useEffect(() => {
    let cancelled = false
    let objectUrl
    import('qrcode').then(async ({ default: qr }) => {
      const canvas = document.createElement('canvas')
      await qr.toCanvas(canvas, code, { width: 640, margin: 3, color: { dark: '#102448', light: '#ffffff' } })
      const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'))
      if (cancelled || !blob) return
      const file = new File([blob], 'utechat-qr.png', { type: 'image/png' })
      objectUrl = URL.createObjectURL(file)
      setQrFile(file)
      setUrl(objectUrl)
    }).catch(error)
    return () => { cancelled = true; if (objectUrl) URL.revokeObjectURL(objectUrl); stopCamera() }
  }, [code, error])
  async function saveQr(event) {
    const appleMobile = /iPhone|iPad|iPod/.test(navigator.userAgent) ||
      (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
    if (!appleMobile || !qrFile || !navigator.canShare?.({ files: [qrFile] })) return
    event.preventDefault()
    try { await navigator.share({ files: [qrFile], title: 'Mã QR UTEChat' }) }
    catch (failure) {
      if (failure.name !== 'AbortError') {
        const download = document.createElement('a')
        download.href = url
        download.download = qrFile.name
        download.click()
      }
    }
  }
  function stopCamera() {
    generation.current++
    media.current?.getTracks().forEach(track => track.stop())
    media.current = null
  }
  async function identify(raw) {
    if (handling.current) return
    const id = raw.trim().match(/^utechat:\/\/friend\/v1\/([A-Za-z0-9_-]{1,128})$/)?.[1]
    if (!id || id === user.uid) { error(new Error(id ? 'Đây là mã QR của bạn.' : 'Mã QR UTEChat không hợp lệ.')); return }
    handling.current = true
    try {
      const result = await profile(id)
      if (!result) throw new Error('Không tìm thấy hồ sơ tương ứng với mã QR.')
      setPerson(result)
    } catch (failure) { error(failure) } finally { handling.current = false }
  }
  async function startCamera() {
    stopCamera()
    setPerson(null)
    const version = generation.current
    try {
      const local = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false })
      if (generation.current !== version) { local.getTracks().forEach(track => track.stop()); return }
      media.current = local
      const { default: decode } = await import('jsqr')
      if (generation.current !== version) return
      video.current.srcObject = local
      await video.current.play()
      setScanning(true)
      const canvas = document.createElement('canvas')
      const ctx = canvas.getContext('2d', { willReadFrequently: true })
      const step = () => {
        if (generation.current !== version) return
        const target = video.current
        if (target?.videoWidth) {
          canvas.width = Math.min(target.videoWidth, 640)
          canvas.height = Math.round(target.videoHeight * canvas.width / target.videoWidth)
          ctx.drawImage(target, 0, 0, canvas.width, canvas.height)
          const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height)
          const found = decode(pixels.data, pixels.width, pixels.height)
          if (found) { stopCamera(); setScanning(false); void identify(found.data); return }
        }
        setTimeout(step, 150)
      }
      step()
    } catch (failure) {
      if (version !== generation.current) return
      stopCamera(); setScanning(false); error(failure)
    }
  }
  async function scanFile(file) {
    if (!file) return
    stopCamera()
    setScanning(false)
    setPerson(null)
    const object = URL.createObjectURL(file)
    try {
      const pic = new Image()
      pic.src = object
      await pic.decode()
      const { default: decode } = await import('jsqr')
      const canvas = document.createElement('canvas')
      canvas.width = Math.min(1600, pic.naturalWidth)
      canvas.height = Math.round(pic.naturalHeight * canvas.width / pic.naturalWidth)
      const ctx = canvas.getContext('2d')
      ctx.drawImage(pic, 0, 0, canvas.width, canvas.height)
      const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height)
      const found = decode(pixels.data, pixels.width, pixels.height)
      if (!found) throw new Error('Không tìm thấy mã QR trong ảnh.')
      await identify(found.data)
    } catch (failure) { error(failure) } finally { URL.revokeObjectURL(object) }
  }
  return <Modal title="Mã QR kết bạn" onClose={close}>
    <div className="segmented"><button aria-pressed={mode === 'show'} onClick={() => { setMode('show'); stopCamera(); setScanning(false) }}>Mã của tôi</button>
      <button aria-pressed={mode === 'scan'} onClick={() => setMode('scan')}>Quét mã</button></div>
    {mode === 'show' ? <div className="qr-block">
      {url && <img src={url} width="224" height="224" alt="Mã QR kết bạn của bạn" />}
      <p>Cho bạn bè quét mã này bằng UTEChat.</p>
      <div className="button-row">
        <button className="secondary" onClick={() => navigator.clipboard.writeText(code).catch(error)}><Copy size={18} /> Sao chép mã</button>
        {url && <a className="secondary" href={url} download={qrFile?.name} onClick={saveQr}><ArrowDownToLine size={18} /> Lưu ảnh</a>}
      </div>
    </div> : <div className="stack">
      <video ref={video} autoPlay muted playsInline className={scanning ? 'qr-camera' : 'qr-camera waiting'} />
      <div className="button-row">
        <button className="primary" onClick={() => scanning ? (stopCamera(), setScanning(false)) : startCamera()}><Camera size={18} />{scanning ? 'Tắt camera' : 'Mở camera'}</button>
        <label className="file-button secondary"><ImagePlus size={18} /> Chọn ảnh QR<input type="file" accept="image/*" onChange={event => { scanFile(event.target.files[0]); event.target.value = '' }} /></label>
      </div>
      <form className="inline-form" onSubmit={event => { event.preventDefault(); void identify(input) }}>
        <input aria-label="Mã QR dạng văn bản" placeholder="Hoặc dán mã UTEChat" value={input} onChange={event => setInput(event.target.value)} maxLength={147} />
        <button className="secondary" disabled={!input.trim()}>Tìm</button>
      </form>
      {person && <PersonResult {...{ person, friendships, uid: user.uid, open, error, close }} />}
    </div>}
  </Modal>
}

export function Community({ groups, select, create, manage }) {
  const [section, setSection] = useState('groups')
  const list = groups.filter(group => Boolean(group.isCommunity) === (section === 'communities'))
  return <div className="tab-page">
    <div className="community-banner">
      <span className="community-symbol"><UsersRound size={24} /></span>
      <h3>Trò chuyện cùng nhóm</h3>
      <p>Tạo nhóm với 3–5 thành viên. Khi nhóm đủ 5 người, trưởng nhóm có thể nâng cấp lên cộng đồng.</p>
      <button className="primary" onClick={create}><Plus size={19} /> Tạo nhóm</button>
    </div>
    <div className="segmented"><button aria-pressed={section === 'groups'} onClick={() => setSection('groups')}>Nhóm</button>
      <button aria-pressed={section === 'communities'} onClick={() => setSection('communities')}>Cộng đồng</button></div>
    {list.length ? list.map(group => <article className="community-card" key={group.id}>
      <PersonRow person={{ displayName: group.groupName || 'Nhóm UTEChat', photoUrl: group.groupPhotoUrl }}
        note={`${group.participantIds.length}/${group.isCommunity ? 10 : 5} thành viên`}
        onClick={() => select(group.id)} />
      <div className="community-actions">
        <button className="secondary compact" onClick={() => select(group.id)}><MessageCircle size={17} /> Mở chat</button>
        <button className="secondary compact" onClick={() => manage(group.id)}><UsersRound size={17} /> Thành viên</button>
      </div>
    </article>)
      : <Empty icon={Users} title={section === 'groups' ? 'Chưa có nhóm' : 'Chưa có cộng đồng'}
        description={section === 'groups' ? 'Nhóm bạn tham gia sẽ xuất hiện ở đây.' : 'Cộng đồng có thể có tối đa 10 thành viên.'} />}
  </div>
}

export function CreateGroup({ people, selectedFirst, created, error, close }) {
  const [name, setName] = useState('')
  const [uids, setUids] = useState(selectedFirst ? [selectedFirst] : [])
  const [busy, setBusy] = useState(false)
  return <Modal title="Tạo nhóm" onClose={close}>
    <form className="stack" onSubmit={async event => {
      event.preventDefault()
      setBusy(true)
      try { created(await createGroup(name, people.filter(person => uids.includes(person.id)))) }
      catch (failure) { error(failure); setBusy(false) }
    }}>
      <label>Tên nhóm<input value={name} onChange={event => setName(event.target.value)} required maxLength={80} placeholder="Nhập tên nhóm" autoFocus /></label>
      <p className="muted">Chọn từ 2 đến 4 bạn bè. Đã chọn {uids.length}/4.</p>
      <div className="picker">
        {people.map(person => <label key={person.id}><Avatar name={person.displayName} photo={person.photoUrl} size={35} />
          <span>{person.displayName}</span>
          <input type="checkbox" checked={uids.includes(person.id)} disabled={!uids.includes(person.id) && uids.length >= 4}
            onChange={event => setUids(event.target.checked ? [...uids, person.id] : uids.filter(id => id !== person.id))} /></label>)}
        {!people.length && <p>Bạn cần kết bạn trước để tạo nhóm.</p>}
      </div>
      <button className="primary wide" disabled={busy || !name.trim() || uids.length < 2}>Tạo nhóm</button>
    </form>
  </Modal>
}

const roles = { owner: 'Trưởng nhóm', admin: 'Phó nhóm', member: 'Thành viên' }
export function GroupSettings({ group, uid, people, friends, error, close, confirm, deselect, search, gallery }) {
  const [members, setMembers] = useState([])
  const [name, setName] = useState(group.groupName || '')
  const [selected, setSelected] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => watchMembers(group.id, setMembers, error), [group.id, error])
  const role = members.find(person => person.id === uid)?.role
  const manager = role === 'owner' || role === 'admin'
  const available = friends.filter(person => !group.participantIds.includes(person.id))
  const title = group.isCommunity ? 'cộng đồng' : 'nhóm'
  const act = async fn => {
    setBusy(true)
    try { await fn() } catch (failure) { error(failure) } finally { setBusy(false) }
  }
  const ask = (heading, description, label, action, danger = false) => confirm({ title: heading, description, label, action, danger })
  return <Modal title={`Quản lý ${title}`} onClose={close} onBack={close} className="app-screen">
    <div className="settings-hero"><Avatar name={group.groupName} photo={group.groupPhotoUrl} size={62} group />
      <strong>{group.groupName}</strong><small>{group.participantIds.length} / {group.isCommunity ? 10 : 5} thành viên</small></div>
    <div className="button-row group-tools">
      {search && <button className="secondary compact" onClick={search}><Search size={16} /> Tìm tin nhắn</button>}
      <button className="secondary compact" onClick={gallery}><ImagePlus size={16} /> Ảnh đã chia sẻ</button>
    </div>
    {manager && <>
      <form className="inline-form" onSubmit={event => { event.preventDefault(); act(() => renameGroup(group.id, name)) }}>
        <input aria-label="Tên nhóm" value={name} onChange={event => setName(event.target.value)} maxLength={80} />
        <button className="secondary compact" type="submit" disabled={busy || !name.trim() || name === group.groupName}>Đổi tên</button>
      </form>
      <form className="inline-form" onSubmit={event => {
        event.preventDefault()
        act(async () => {
          const person = available.find(candidate => candidate.id === selected)
          if (!person) throw new Error('Người này đã có trong nhóm.')
          await changeMembership(group.id, selected, person)
          setSelected('')
        })
      }}>
        <select aria-label="Bạn bè để thêm" value={selected} onChange={event => setSelected(event.target.value)}>
          <option value="">Thêm bạn bè vào nhóm</option>
          {available.map(person => <option value={person.id} key={person.id}>{person.displayName}</option>)}
        </select>
        <button className="secondary compact" type="submit" disabled={busy || !selected}>Thêm</button>
      </form>
    </>}
    {role === 'owner' && !group.isCommunity && <div className="info-strip">
      <span>{group.participantIds.length < 5 ? 'Cần đủ 5 thành viên để nâng cấp.' : 'Cộng đồng có thể có tối đa 10 thành viên.'}</span>
      <button className="secondary compact" disabled={busy || group.participantIds.length < 5} onClick={() =>
        ask('Nâng lên cộng đồng', 'Nhóm sẽ trở thành cộng đồng với sức chứa tối đa 10 thành viên.', 'Nâng cấp', () => upgradeGroup(group.id))}>Nâng cấp</button>
    </div>}
    <h3 className="section-label">Thành viên</h3>
    {members.filter(person => group.participantIds.includes(person.id)).map(person => {
      const data = people[person.id] || group.participantSummaries?.[person.id] || {}
      const canEdit = person.id !== uid && person.role !== 'owner' && (role === 'owner' || (role === 'admin' && person.role === 'member'))
      return <PersonRow key={person.id} person={{ displayName: data.displayName || 'Người dùng UTEChat', photoUrl: data.photoUrl }} note={roles[person.role]}>
        {canEdit && <select aria-label={`Quản lý ${data.displayName || 'thành viên'}`} value="" disabled={busy} onChange={event => {
          const action = event.target.value
          if (action === 'remove') ask('Xóa thành viên', 'Thành viên sẽ mất quyền truy cập nhóm.', 'Xóa thành viên', () => changeMembership(group.id, person.id), true)
          if (action === 'transfer') ask('Chuyển quyền trưởng nhóm', 'Bạn sẽ trở thành phó nhóm.', 'Chuyển quyền', () => transferGroup(group.id, person.id))
          if (action === 'deputy') void act(() => setDeputy(group.id, person.id, person.role !== 'admin'))
        }}>
          <option value="">Quản lý</option>
          {role === 'owner' && <option value="deputy">{person.role === 'admin' ? 'Bỏ quyền phó nhóm' : 'Đặt làm phó nhóm'}</option>}
          {role === 'owner' && <option value="transfer">Chuyển quyền trưởng nhóm</option>}
          <option value="remove">Xóa khỏi nhóm</option>
        </select>}
      </PersonRow>
    })}
    <div className="button-row">
      {role === 'owner' || group.participantIds.length === 1 ? <button className="danger" onClick={() =>
        ask(`Xóa ${title}`, 'Lịch sử và tệp của tất cả thành viên sẽ bị xóa.', 'Xóa', async () => { await deleteGroup(group.id); deselect(); close() }, true)
      }>Xóa {title}</button> : role && <button className="danger" onClick={() =>
        ask(`Rời ${title}`, 'Bạn sẽ mất quyền truy cập hội thoại.', 'Rời nhóm', async () => { await changeMembership(group.id, uid); deselect(); close() }, true)
      }>Rời {title}</button>}
    </div>
  </Modal>
}

export function Profile({ user, data, presence, blocked, people, setDialog, error, notify, signout }) {
  const [enabled, setEnabled] = useState(() => notificationsEnabled(user.uid))
  const [preview, setPreview] = useState(() => notificationPreferences(user.uid).showPreview)
  const [busy, setBusy] = useState(false)
  const [savingPreview, setSavingPreview] = useState(false)
  return <div className="tab-page profile-page">
    <div className="profile-hero"><Avatar name={data.displayName} photo={data.photoUrl} size={76} />
      <h3>{data.displayName}</h3><p>{user.email}</p>{data.bio && <p>{data.bio}</p>}
      <button className="secondary" onClick={() => setDialog({ type: 'profile' })}>Chỉnh sửa hồ sơ</button>
    </div>
    <button className="settings-row" onClick={() => setDialog({ type: 'qr' })}><QrCode /><span><strong>Mã QR của tôi</strong><small>Chia sẻ mã để kết bạn</small></span><ChevronRight /></button>
    <label className="settings-row"><CircleUserRound /><span><strong>Trạng thái hoạt động</strong><small>Hiển thị khi bạn đang trực tuyến</small></span>
      <input type="checkbox" role="switch" checked={presence?.visible === true} onChange={event => setVisibility(user.uid, event.target.checked).catch(error)} /></label>
    <label className="settings-row"><Bell /><span><strong>Thông báo trên thiết bị này</strong><small>Tin nhắn và cuộc gọi đến</small></span>
      <input type="checkbox" role="switch" checked={enabled} disabled={busy} onChange={async event => {
        const on = event.target.checked
        setBusy(true)
        try { await (on ? configureNotifications(user.uid, true) : disableNotifications(user.uid)); setEnabled(on); notify(on ? 'Đã bật thông báo.' : 'Đã tắt thông báo.') }
        catch (failure) { error(failure) } finally { setBusy(false) }
      }} /></label>
    <label className="settings-row"><Eye /><span><strong>Xem trước tin nhắn</strong><small>Hiện nội dung trong thông báo của thiết bị</small></span>
      <input type="checkbox" role="switch" checked={preview} disabled={savingPreview} onChange={async event => {
        const value = event.target.checked
        setSavingPreview(true)
        try { await saveNotificationPreferences(user.uid, { showPreview: value }); setPreview(value) }
        catch (failure) { error(failure) } finally { setSavingPreview(false) }
      }} /></label>
    <button className="settings-row" onClick={() => setDialog({ type: 'password' })}><LockKeyhole /><span><strong>Đặt lại mật khẩu</strong><small>Gửi liên kết về email đã xác minh</small></span><ChevronRight /></button>
    <button className="settings-row" onClick={() => setDialog({ type: 'blocked', blocked, people })}><Shield /><span><strong>Danh sách chặn</strong><small>{blocked.length} tài khoản</small></span><ChevronRight /></button>
    <button className="settings-row danger-text" onClick={signout}><LogOut /><span><strong>Đăng xuất</strong></span><ChevronRight /></button>
  </div>
}

export function BlockedList({ blocked, people, error, close }) {
  return <Modal title="Danh sách chặn" onClose={close}>
    {blocked.length ? blocked.map(id => <PersonRow key={id} person={people[id] || { displayName: 'Người dùng UTEChat' }}>
      <button className="secondary compact" onClick={() => blockUser(id, false).catch(error)}>Bỏ chặn</button></PersonRow>)
      : <Empty icon={Shield} title="Chưa có tài khoản bị chặn" />}
  </Modal>
}
