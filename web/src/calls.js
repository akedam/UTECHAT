import { useEffect, useRef, useState } from 'react'
import {
  collection, doc, getDoc, limit, onSnapshot, orderBy, query,
  runTransaction, setDoc, where
} from 'firebase/firestore'
import { api, backend, db } from './firebase'
import { messageRef, profile, read, watch, writeSummary } from './chat-data'
import { queueNetworkJob, watchNetworkJobs } from './network-jobs'
import { useOnline } from './ui'

const terminal = ['ended', 'failed', 'missed', 'rejected', 'busy']
const calls = collection(db, 'calls')
const callKind = media => media === 'video' ? 'video' : 'thoại'

export function callHistoryTitle(content = '') {
  if (!/^(📞 Cuộc gọi thoại|📹 Cuộc gọi video)/u.test(content)) return null
  const media = content.startsWith('📹') ? 'video' : 'audio'
  return { media, title: / nhỡ|chưa được trả lời/.test(content)
    ? 'Đã nhỡ cuộc gọi ' + callKind(media) : content.slice(content.indexOf(' ') + 1) }
}

function historyText(call, status, expired) {
  const prefix = (call.mediaType === 'video' ? '📹' : '📞') + ' Cuộc gọi ' + callKind(call.mediaType)
  const texts = { rejected: ' bị từ chối', busy: ' không thành công: người nhận đang bận', failed: ' không kết nối được', missed: ' nhỡ' }
  return prefix + (texts[status] || (call.status === 'accepted' ? ' đã kết thúc' :
    expired ? ' nhỡ' : ' đã kết thúc (chưa được trả lời)'))
}

async function setStatus(id, uid, nextStatus) {
  const target = doc(calls, id)
  return runTransaction(db, async tx => {
    const call = (await tx.get(target)).data()
    if (!call || terminal.includes(call.status)) return call?.status
    const incoming = uid === call.calleeUid
    const expired = Date.now() >= call.expiresAt
    if (nextStatus === 'expired' && (call.status !== 'ringing' || !expired)) return call.status
    const status = nextStatus === 'expired' ? (incoming ? 'missed' : 'ended') : nextStatus
    if (call.status === 'ringing' && incoming && status === 'accepted' && expired) throw new Error('Cuộc gọi đã hết thời gian chờ.')
    if (call.status === 'ringing' && incoming && ['ended', 'failed'].includes(status)) return call.status
    if (call.status === 'accepted' && ['accepted', 'busy', 'rejected'].includes(status)) return call.status
    const now = Math.max(Date.now(), call.updatedAt)
    if (terminal.includes(status)) {
      const conversation = (await tx.get(doc(db, 'conversations', call.conversationId))).data()
      const user = (await tx.get(doc(db, 'publicProfiles', uid))).data()
      const time = Math.max(now, (conversation.lastMessageAt || 0) + 1)
      writeSummary(tx, call.conversationId, messageRef(call.conversationId, 'call_' + id), {
        type: 'text', senderName: user.displayName, content: historyText(call, status, expired)
      }, uid, time)
    }
    tx.update(target, { status, updatedAt: now })
    return status
  })
}

async function openMedia(mode) {
  if (!navigator.mediaDevices?.getUserMedia) throw new Error('Hãy mở UTEChat qua HTTPS để sử dụng mic và camera.')
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: mode === 'video' ? { facingMode: 'user' } : false })
  return stream
}

function connectRtc(call, stream, connected) {
  const tracks = []
  let Agora
  let client
  let closed = false
  let queue
  const guard = () => { if (closed) throw new Error('Cuộc gọi đã đóng.') }
  const session = {
    remoteTracks: new Map(),
    localVideo: null,
    async setCamera(enabled, facing) {
      await queue
      guard()
      if (!enabled) {
        const current = session.localVideo
        if (!current) return null
        await client.unpublish(current)
        current.close()
        session.localVideo = null
        return null
      }
      const camera = await navigator.mediaDevices.getUserMedia({ video: { facingMode: facing }, audio: false })
      const source = camera.getVideoTracks()[0]
      let track
      try {
        guard()
        track = Agora.createCustomVideoTrack({ mediaStreamTrack: source })
        await client.publish(track)
        guard()
        session.localVideo = track
        return track
      } catch (failure) { track?.close(); source.stop(); throw failure }
    },
    async switchCamera(facing) {
      await queue
      guard()
      if (!session.localVideo) return
      const camera = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { exact: facing } }, audio: false
      })
      const next = camera.getVideoTracks()[0]
      try { guard(); await session.localVideo.replaceTrack(next, true) }
      catch (failure) { next.stop(); throw failure }
    },
    playAudio() { session.remoteTracks.get('audio')?.play() },
    close() {
      if (closed) return
      closed = true
      tracks[0]?.close()
      session.localVideo?.close()
      stream.getTracks().forEach(track => track.stop())
      Promise.resolve(queue).catch(() => {}).then(() => client?.leave()).catch(() => {})
    }
  }
  queue = (async () => {
    Agora = (await import('agora-rtc-sdk-ng')).default
    guard()
    const token = await api('/api/rtc-token', { body: { callId: call.id }, signal: AbortSignal.timeout?.(30_000) })
    guard()
    client = Agora.createClient({ mode: 'rtc', codec: 'h264' })
    client.on('connection-state-change', state => {
      if (!closed) connected({ type: 'connection', state })
    })
    client.on('user-published', async (remote, type) => {
      if (type !== 'audio' && type !== 'video') return
      try {
        await client.subscribe(remote, type)
        guard()
        const track = type === 'audio' ? remote.audioTrack : remote.videoTrack
        if (!track) return
        session.remoteTracks.set(type, track)
        if (type === 'audio') track.play()
        connected({ type, track })
      } catch {
        if (!closed) connected({ type: 'retry-audio' })
      }
    })
    const removeRemote = (type) => {
      session.remoteTracks.get(type)?.stop()
      session.remoteTracks.delete(type)
      connected({ type, track: null })
    }
    client.on('user-unpublished', (_, type) => removeRemote(type))
    client.on('user-left', (_, reason) => {
      removeRemote('video')
      removeRemote('audio')
      if (reason === 'Quit') connected({ type: 'peer-left' })
      else connected({ type: 'peer-connection', interrupted: true })
    })
    client.on('user-joined', () => connected({ type: 'peer-connection', interrupted: false }))
    client.on('token-privilege-will-expire', async () => {
      try {
        const renewed = await api('/api/rtc-token', { body: { callId: call.id } })
        if (!closed) await client.renewToken(renewed.token)
      } catch {
        if (!closed) connected({ type: 'error', error: new Error('Không thể gia hạn kết nối cuộc gọi.') })
      }
    })
    tracks.push(Agora.createCustomAudioTrack({ mediaStreamTrack: stream.getAudioTracks()[0] }))
    if (call.mediaType === 'video') {
      session.localVideo = Agora.createCustomVideoTrack({ mediaStreamTrack: stream.getVideoTracks()[0] })
      tracks.push(session.localVideo)
    }
    await client.join(token.appId, token.channelName, token.token, token.uid)
    guard()
    await client.publish(tracks)
    guard()
    connected({ type: 'ready' })
  })()
  session.ready = queue
  return session
}

export function useCall(uid, error) {
  const online = useOnline()
  const [active, setActive] = useState(null)
  const [waiting, setWaiting] = useState(null)
  const [media, setMedia] = useState({})
  const [muted, setMuted] = useState(false)
  const [rear, setRear] = useState(false)
  const [busy, setBusy] = useState(false)
  const [seconds, setSeconds] = useState(0)
  const stream = useRef(null)
  const rtc = useRef(null)
  const busyRef = useRef(false)
  const activeRef = useRef(null)
  const waitingRef = useRef(null)
  const dismissed = useRef(new Set())
  useEffect(() => watchNetworkJobs(uid, ({ id, status }) => setStatus(id, uid, status)), [uid])
  const closeOnServer = (id, status) =>
    queueNetworkJob(uid, 'call-status', id, { id, status }, Date.now() + 86_400_000)
  const updateActive = call => { activeRef.current = call; setActive(call) }
  const clear = () => {
    rtc.current?.close()
    rtc.current = null
    stream.current?.getTracks().forEach(track => track.stop())
    stream.current = null
    updateActive(null)
    setMedia({})
    setMuted(false)
    setRear(false)
    setSeconds(0)
  }

  useEffect(() => {
    const users = where('participants', 'array-contains', uid)
    let fallback
    const receive = list => {
      const ringing = list.filter(call => call.calleeUid === uid && call.status === 'ringing')
      const next = ringing.find(call => call.expiresAt > Date.now() && call.id !== activeRef.current?.id &&
        !dismissed.current.has(call.id))
      waitingRef.current = next || null
      setWaiting(waitingRef.current)
    }
    const cancel = watch(query(calls, users, where('calleeUid', '==', uid),
      where('status', '==', 'ringing'), orderBy('createdAt'), limit(10)), receive, failure => {
      if (failure.code === 'failed-precondition') fallback = watch(query(calls, users), receive, error)
      else error(failure)
    })
    return () => { cancel(); fallback?.(); rtc.current?.close(); stream.current?.getTracks().forEach(track => track.stop()) }
  }, [uid, error])

  useEffect(() => {
    if (!active?.id) return
    return onSnapshot(doc(calls, active.id), snap => {
      if (!snap.exists() || terminal.includes(snap.data().status)) clear()
      else updateActive(read(snap))
    }, error)
  }, [active?.id, error])

  const displayed = active || waiting
  useEffect(() => {
    if (displayed?.status !== 'ringing') return
    const { id, expiresAt } = displayed
    const timer = setTimeout(() => {
      setWaiting(call => call?.id === id ? null : call)
      setStatus(id, uid, 'expired').catch(err => {
        if (activeRef.current?.id === id) clear()
        error(err)
      })
    }, Math.max(0, expiresAt - Date.now()))
    return () => clearTimeout(timer)
  }, [displayed?.id, displayed?.status, uid, error])

  useEffect(() => {
    if (active?.status !== 'accepted' || !stream.current || rtc.current) return
    const id = active.id
    const controller = connectRtc(active, stream.current, value => {
      if (activeRef.current?.id !== id) return
      if (value.type === 'error') error(value.error)
      else if (value.type === 'peer-left') {
        closeOnServer(id, 'ended')
        clear()
      } else if (value.type === 'connection') {
        setMedia(previous => ({ ...previous, connection: value.state }))
      } else if (value.type === 'peer-connection') {
        setMedia(previous => ({ ...previous, peerInterrupted: value.interrupted }))
      }
      else setMedia(previous => ({
        ...previous, [value.type]: (value.type === 'ready' || value.type === 'retry-audio') ? true : value.track ?? null
      }))
    })
    rtc.current = controller
    controller.ready.then(() => {
      if (activeRef.current?.id === id) setMedia(previous => ({ ...previous, localTrack: controller.localVideo }))
    }).catch(err => {
      if (activeRef.current?.id !== id) return
      closeOnServer(id, 'failed')
      clear()
      error(err)
    })
  }, [active?.id, active?.status, uid, error])

  const reconnecting = active?.status === 'accepted' &&
    (!online || ['RECONNECTING', 'DISCONNECTED'].includes(media.connection) || media.peerInterrupted === true)
  useEffect(() => {
    if (!reconnecting) return
    const id = active.id
    const timer = setTimeout(() => {
      if (activeRef.current?.id !== id) return
      closeOnServer(id, 'failed')
      clear()
      error(new Error('Cuộc gọi đã kết thúc do mất kết nối.'))
    }, 30_000)
    return () => clearTimeout(timer)
  }, [active?.id, reconnecting, uid, error])

  useEffect(() => {
    if (!media.ready || (!media.audio && !media.video)) return
    const start = Date.now()
    const timer = setInterval(() => setSeconds(Math.floor((Date.now() - start) / 1000)), 1000)
    return () => clearInterval(timer)
  }, [media.ready, Boolean(media.audio || media.video)])

  const act = async fn => {
    if (busyRef.current) return
    busyRef.current = true
    setBusy(true)
    try { await fn() } catch (err) { error(err) } finally { setBusy(false); busyRef.current = false }
  }
  return {
    displayed, busy, media, muted, rear, seconds, reconnecting, preview: stream.current,
    outgoing: (conversation, mediaType) => act(async () => {
      if (activeRef.current || waiting) throw new Error('Bạn đang có một cuộc gọi.')
      if (!navigator.onLine) throw new Error('Hãy kết nối mạng để gọi.')
      const peer = conversation.participantIds.find(value => value !== uid)
      const local = await openMedia(mediaType)
      try {
        const response = await fetch(backend + '/healthz',
          { cache: 'no-store', signal: AbortSignal.timeout?.(65_000) })
        if (!response.ok) throw new Error('backend unavailable')
      } catch {
        local.getTracks().forEach(track => track.stop())
        throw new Error('Chưa kết nối được máy chủ cuộc gọi. Hãy thử lại.')
      }
      if (activeRef.current || (waitingRef.current?.expiresAt > Date.now())) {
        local.getTracks().forEach(track => track.stop())
        throw new Error('Bạn đang có một cuộc gọi.')
      }
      const target = doc(calls)
      const now = Date.now()
      const data = { conversationId: conversation.id, callerUid: uid, calleeUid: peer,
        participants: [uid, peer], channelName: target.id, mediaType, status: 'ringing',
        createdAt: now, updatedAt: now, expiresAt: now + 45_000 }
      try { await setDoc(target, data) } catch (err) { local.getTracks().forEach(track => track.stop()); throw err }
      stream.current = local
      updateActive({ id: target.id, ...data })
      queueNetworkJob(uid, 'invite', target.id, { callId: target.id }, data.expiresAt)
    }),
    answer: () => act(async () => {
      if (!waiting || waiting.expiresAt <= Date.now()) return
      if (!navigator.onLine) throw new Error('Hãy kết nối mạng để trả lời cuộc gọi.')
      const local = await openMedia(waiting.mediaType)
      try {
        const status = await setStatus(waiting.id, uid, 'accepted')
        if (status !== 'accepted') throw new Error('Cuộc gọi đã kết thúc.')
        stream.current = local
        updateActive({ ...waiting, status })
        setWaiting(null)
      } catch (err) { local.getTracks().forEach(track => track.stop()); throw err }
    }),
    finish: () => act(async () => {
      if (!displayed) return
      const incoming = !active && displayed.calleeUid === uid
      closeOnServer(displayed.id, incoming ? 'rejected' : 'ended')
      if (incoming) {
        dismissed.current.add(displayed.id)
        waitingRef.current = null
        setWaiting(null)
      }
      else clear()
    }),
    toggleMute: () => act(async () => {
      stream.current?.getAudioTracks().forEach(track => { track.enabled = muted })
      setMuted(!muted)
    }),
    toggleVideo: () => act(async () => {
      const session = rtc.current
      if (!session || !media.ready) return
      const localTrack = await session.setCamera(!media.localTrack, rear ? 'environment' : 'user')
      if (rtc.current === session) setMedia(previous => ({ ...previous, localTrack }))
    }),
    switchCamera: () => act(async () => {
      const session = rtc.current
      if (!session || !media.localTrack) return
      try { await session.switchCamera(rear ? 'user' : 'environment') }
      catch (failure) {
        if (failure.name === 'OverconstrainedError' || failure.name === 'NotFoundError')
          throw new Error('Thiết bị không có camera còn lại.')
        throw failure
      }
      if (rtc.current === session) setRear(!rear)
    }),
    playAudio: () => rtc.current?.playAudio()
  }
}

export async function callPeer(call, uid) {
  return profile(call.callerUid === uid ? call.calleeUid : call.callerUid)
}

export function useIncomingTone(ringing, messageKey) {
  const audio = useRef(null)
  useEffect(() => {
    const enable = () => {
      const Audio = window.AudioContext || window.webkitAudioContext
      if (!Audio) return
      try {
        audio.current ||= new Audio()
      if (audio.current.state !== 'running') audio.current.resume().catch(() => {})
      } catch { /* Some browser profiles disable autoplay entirely. */ }
    }
    window.addEventListener('pointerdown', enable)
    window.addEventListener('click', enable)
    window.addEventListener('keydown', enable)
    return () => {
      window.removeEventListener('pointerdown', enable)
      window.removeEventListener('click', enable)
      window.removeEventListener('keydown', enable)
      audio.current?.close().catch(() => {})
      audio.current = null
    }
  }, [])
  function playTone(frequency, delays) {
      const context = audio.current
      if (context?.state !== 'running' || document.visibilityState !== 'visible') return
      const start = context.currentTime
      for (const delay of delays) {
        const oscillator = context.createOscillator()
        const level = context.createGain()
        oscillator.frequency.value = frequency
        level.gain.setValueAtTime(0, start + delay)
        level.gain.linearRampToValueAtTime(.065, start + delay + .025)
        level.gain.linearRampToValueAtTime(0, start + delay + .18)
        oscillator.connect(level).connect(context.destination)
        oscillator.start(start + delay)
        oscillator.stop(start + delay + .19)
        oscillator.onended = () => { oscillator.disconnect(); level.disconnect() }
      }
  }
  useEffect(() => {
    if (!ringing) return
    const pulse = () => playTone(510, [0, .25])
    pulse()
    const id = setInterval(pulse, 1800)
    return () => clearInterval(id)
  }, [ringing])
  useEffect(() => { if (messageKey && !ringing) playTone(730, [0]) }, [messageKey])
}
