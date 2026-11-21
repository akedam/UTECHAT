import { useEffect, useRef, useState } from 'react'
import { CameraOff, Images, RefreshCw, Sparkles, Zap } from 'lucide-react'
import { IconButton, Modal } from './ui'
import { artwork, assetUrl, cellRect, crop, drawFaces, drawFrame, filters as catalog, SIZE } from './tin-effects'

const tones = [
  { id: 'natural', label: 'Tự nhiên', css: 'none', color: '#b9cce7' },
  { id: 'peach', label: 'Hồng đào', css: 'sepia(.25) saturate(1.24)', color: '#fdabaf', weights: [1.12, 1.02, .91] },
  { id: 'sky', label: 'Xanh mây', css: 'saturate(.8) hue-rotate(12deg)', color: '#91cef3', weights: [.89, 1.04, 1.13] },
  { id: 'mono', label: 'Đen trắng', css: 'grayscale(1)', color: '#949fac' }
]
const groups = [['booth', 'Khung & Photobooth'], ['trend', 'Khung viền & Xu hướng'], ['funny', 'Hài hước']]

function cellStyle(frame, index) {
  const [left, top, width, height] = cellRect(frame, index).map(value => `${100 * value / SIZE}%`)
  return { left, top, width, height }
}

function canvas() {
  const result = document.createElement('canvas')
  result.width = result.height = SIZE
  return result
}

function sample(source, width, height, tone, mirror = false) {
  if (!width || !height) throw new Error('Camera chưa sẵn sàng.')
  const frame = canvas()
  const ctx = frame.getContext('2d', { alpha: false, willReadFrequently: true })
  // Keep enough of the original frame to match the crop of a tall or wide cell.
  const scale = Math.min(SIZE / width, SIZE / height, 1)
  frame.width = Math.max(1, Math.round(width * scale))
  frame.height = Math.max(1, Math.round(height * scale))
  crop(ctx, source, width, height, 0, 0, frame.width, frame.height, mirror)
  if (tone.id === 'natural') return frame
  const data = ctx.getImageData(0, 0, frame.width, frame.height)
  for (let index = 0; index < data.data.length; index += 4) {
    if (tone.weights) tone.weights.forEach((weight, channel) => { data.data[index + channel] *= weight })
    else {
      const gray = data.data[index] * .299 + data.data[index + 1] * .587 + data.data[index + 2] * .114
      data.data.fill(gray, index, index + 3)
    }
  }
  ctx.putImageData(data, 0, 0)
  return frame
}

async function joinPhotos(images, frame) {
  const result = canvas()
  const ctx = result.getContext('2d', { alpha: false })
  const art = await artwork(frame)
  drawFrame(ctx, images, frame, art)
  if (frame.anchor) {
    const { faceDetector, readFaces } = await import('./tin-face')
    const faces = readFaces(await faceDetector(), result)
    if (!faces.length) throw new Error('Chưa thấy khuôn mặt. Hãy đưa mặt vào giữa khung hoặc chọn ảnh chân dung.')
    drawFaces(ctx, frame, art, faces)
  }
  for (const quality of [.82, .68, .52]) {
    const blob = await new Promise(resolve => result.toBlob(resolve, 'image/jpeg', quality))
    if (blob && blob.size <= 512 * 1024) return blob
  }
  throw new Error('Ảnh quá lớn. Hãy chụp lại.')
}

export function TinCamera({ sendPhoto, error, active, clear }) {
  const video = useRef(null)
  const effects = useRef(null)
  const file = useRef(null)
  const shots = useRef([])
  const working = useRef(false)
  const [facing, setFacing] = useState('user')
  const [ready, setReady] = useState(false)
  const [cameraError, setCameraError] = useState(false)
  const [track, setTrack] = useState(null)
  const [flash, setFlash] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [tone, setTone] = useState(tones[0])
  const [frame, setFrame] = useState(() => catalog.find(filter => filter.id === 'natural'))
  const [effectMessage, setEffectMessage] = useState('')
  const [count, setCount] = useState(0)
  const [thumbnails, setThumbnails] = useState([])
  const [filters, setFilters] = useState(false)
  const [busy, setBusy] = useState(false)
  const reset = () => { shots.current = []; setCount(0); setThumbnails([]) }
  useEffect(reset, [clear])
  useEffect(() => {
    setReady(false)
    setCameraError(false)
    setTrack(null)
    setFlash(false)
    if (!active) return
    let live = true
    let stream
    const preview = video.current
    ;(async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: {
          facingMode: { ideal: facing }, width: { ideal: 1280 }, height: { ideal: 1280 }
        } })
        if (!live) { stream.getTracks().forEach(track => track.stop()); return }
        preview.srcObject = stream
        setTrack(stream.getVideoTracks()[0])
        await preview.play()
      } catch { if (live) setCameraError(true) }
    })()
    return () => {
      live = false
      stream?.getTracks().forEach(track => track.stop())
      if (preview) preview.srcObject = null
    }
  }, [active, facing, attempt])

  useEffect(() => {
    setEffectMessage('')
    if (!ready || !active || !frame.anchor || filters || busy) return
    let stopped = false
    let timer
    const target = effects.current
    const output = target.getContext('2d')
    const analysis = document.createElement('canvas')
    analysis.width = analysis.height = target.width
    const scan = analysis.getContext('2d', { alpha: false })
    const source = video.current
    const size = target.width
    setEffectMessage('Đang chuẩn bị hiệu ứng…')
    ;(async () => {
      const [art, model] = await Promise.all([artwork(frame), import('./tin-face')])
      const detector = await model.faceDetector()
      if (stopped) return
      function next() {
        if (stopped) return
        if (!document.hidden && source.readyState >= 2 && source.videoWidth) {
          output.clearRect(0, 0, size, size)
          try {
            crop(scan, source, source.videoWidth, source.videoHeight, 0, 0, size, size, facing === 'user')
            const faces = model.readFaces(detector, analysis)
            drawFaces(output, frame, art, faces)
            target.dataset.faces = String(faces.length)
            setEffectMessage(faces.length ? '' : 'Đưa khuôn mặt vào giữa khung')
          } catch { setEffectMessage('Chưa phân tích được khuôn mặt.') }
        }
        timer = setTimeout(next, 175)
      }
      next()
    })().catch(() => {
      if (!stopped) setEffectMessage('Chưa tải được hiệu ứng. Hãy chọn lại.')
    })
    return () => { stopped = true; clearTimeout(timer); output.clearRect(0, 0, size, size); delete target.dataset.faces }
  }, [frame, ready, active, facing, filters, busy])

  async function capture(load) {
    if (working.current) return
    working.current = true
    setBusy(true)
    try {
      const image = await load()
      const next = [...shots.current, image]
      if (next.length === frame.cells) {
        sendPhoto(await joinPhotos(next, frame))
        reset()
      } else {
        shots.current = next
        setCount(next.length)
        const thumbnail = document.createElement('canvas')
        const [, , width, height] = cellRect(frame, next.length - 1)
        const scale = Math.min(240 / width, 240 / height, 1)
        thumbnail.width = Math.round(width * scale)
        thumbnail.height = Math.round(height * scale)
        crop(thumbnail.getContext('2d'), image, image.width, image.height, 0, 0, thumbnail.width, thumbnail.height)
        setThumbnails(current => [...current, thumbnail.toDataURL('image/jpeg', .62)])
      }
    } catch (problem) { error(problem) }
    finally { working.current = false; setBusy(false) }
  }

  return <section className="tin-camera" aria-label="Máy ảnh">
    <div className="tin-camera-window" style={{ '--filter': tone.css, backgroundColor: frame.fill }}>
      {Array.from({ length: frame.cells }, (_, index) => index !== count && <div key={index} className="tin-camera-cell"
        style={cellStyle(frame, index)}>{thumbnails[index] && <img src={thumbnails[index]} alt="" />}</div>)}
      <video ref={video} muted autoPlay playsInline onLoadedData={() => setReady(true)}
        style={cellStyle(frame, count)} className={facing === 'user' ? 'mirror' : ''} />
      {frame.art && !frame.anchor && <img className="tin-camera-art" src={assetUrl(frame)} alt="" />}
      {ready && active && frame.anchor && <canvas ref={effects} className="tin-live-effect" width="256" height="256" aria-hidden="true" />}
      {(!active || cameraError || !ready) && <div className="tin-camera-message">
        <CameraOff size={30} />
        {cameraError ? <><span>Chưa mở được camera.</span><button onClick={() => setAttempt(value => value + 1)}>Thử lại</button></> :
          <span>{active ? 'Đang mở camera…' : 'Máy ảnh đang tạm dừng'}</span>}
      </div>}
      {frame.cells > 1 && <span className="tin-shot-count">{count}/{frame.cells} ảnh</span>}
      {effectMessage && <span className="tin-shot-count" role="status">{effectMessage}</span>}
      {track?.getCapabilities?.().torch && <IconButton className="tin-flash-toggle" icon={Zap} label={flash ? 'Tắt đèn' : 'Bật đèn'}
        aria-pressed={flash} onClick={async () => {
          try { await track.applyConstraints({ advanced: [{ torch: !flash }] }); setFlash(!flash) }
          catch { error(new Error('Chưa đổi được trạng thái đèn.')) }
        }} />}
      <IconButton className="tin-filter-toggle" icon={Sparkles} label="Bộ lọc và khung ảnh" aria-expanded={filters}
        disabled={busy || count > 0} onClick={() => setFilters(!filters)} />
    </div>
    {filters && <Modal title="Bộ lọc và khung ảnh" onClose={() => setFilters(false)} className="tin-sheet">
      <div className="tin-filters">
        {groups.map(([group, name]) => <section key={group} aria-label={name}><strong>{name}</strong>
          <div className="tin-filter-shelf">{catalog.filter(option => option.group === group).map(option =>
            <button key={option.id} aria-pressed={option === frame} onClick={() => { setFrame(option); setFilters(false) }}>
              {option.art ? <span className={`tin-art-sample ${option.anchor ? 'tin-sticker-sample' : ''}`}>
                <img src={assetUrl(option)} alt="" /></span>
                : <i className="tin-frame-sample" style={{ '--cells': option.columns, background: option.fill || '#cbdced' }}>
                  {Array.from({ length: option.cells }, (_, index) => <b key={index} />)}
                </i>}{option.label}</button>)}</div>
        </section>)}
        <section aria-label="Màu ảnh"><strong>Màu ảnh</strong>
          <div className="tin-filter-shelf">{tones.map(option => <button key={option.id} aria-pressed={option === tone} onClick={() => setTone(option)}>
            <i style={{ background: option.color }} />{option.label}</button>)}</div>
        </section>
      </div>
    </Modal>}
    <div className="tin-capture-controls">
      <IconButton icon={Images} label="Chọn ảnh từ album" onClick={() => file.current?.click()} disabled={busy} />
      <button className="tin-shutter" aria-label="Chụp ảnh" disabled={!ready || cameraError || busy} onClick={() => {
        const source = video.current
        capture(() => sample(source, source.videoWidth, source.videoHeight, tone, facing === 'user'))
      }} />
      <IconButton icon={RefreshCw} label="Đổi camera trước/sau" disabled={busy}
        onClick={() => setFacing(value => value === 'user' ? 'environment' : 'user')} />
    </div>
    {count > 0 && <div className="tin-shot-strip">
      {thumbnails.map((source, index) => <img key={index} src={source} alt={`Ảnh ${index + 1} đã chụp`} />)}
      <button className="text-button" disabled={busy} onClick={reset}>Bỏ bộ ảnh đang chụp</button>
    </div>}
    <input ref={file} type="file" accept="image/*" hidden onChange={event => {
      const imageFile = event.target.files[0]
      event.target.value = ''
      if (!imageFile) return
      capture(async () => {
        const url = URL.createObjectURL(imageFile)
        try {
          const image = new window.Image()
          await new Promise((resolve, reject) => {
            image.onload = resolve
            image.onerror = () => reject(new Error('Không mở được ảnh trong album.'))
            image.src = url
          })
          return sample(image, image.naturalWidth, image.naturalHeight, tone)
        } finally { URL.revokeObjectURL(url) }
      })
    }} />
  </section>
}
