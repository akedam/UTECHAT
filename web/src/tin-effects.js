import catalog from '../../shared/tin-filters/catalog.json'

export const filters = catalog
export const SIZE = 960
const files = import.meta.glob('../../shared/tin-filters/*.png', { eager: true, import: 'default', query: '?url' })
const images = new Map()
export const assetUrl = filter => files[`../../shared/tin-filters/${filter.art}`]

export function artwork(filter) {
  if (!filter.art) return Promise.resolve(null)
  if (!images.has(filter.id)) images.set(filter.id, new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('Chưa tải được bộ lọc. Hãy thử lại.'))
    image.src = assetUrl(filter)
  }).catch(error => { images.delete(filter.id); throw error }))
  return images.get(filter.id)
}

export function cellRect(filter, index) {
  const [top, right, bottom, left] = filter.insets
  const rows = Math.ceil(filter.cells / filter.columns)
  const width = (SIZE - left - right - filter.gap * (filter.columns - 1)) / filter.columns
  const height = (SIZE - top - bottom - filter.gap * (rows - 1)) / rows
  return [left + index % filter.columns * (width + filter.gap),
    top + Math.floor(index / filter.columns) * (height + filter.gap), width, height]
}

export function crop(ctx, source, width, height, x, y, w, h, mirror = false) {
  const scale = Math.max(w / width, h / height)
  const sw = w / scale, sh = h / scale
  ctx.save()
  if (mirror) { ctx.translate(2 * x + w, 0); ctx.scale(-1, 1) }
  ctx.drawImage(source, (width - sw) / 2, (height - sh) / 2, sw, sh, x, y, w, h)
  ctx.restore()
}

export function drawFrame(ctx, shots, filter, art) {
  ctx.fillStyle = filter.fill || '#cbdced'
  ctx.fillRect(0, 0, SIZE, SIZE)
  for (let index = 0; index < filter.cells; index++) {
    const [x, y, width, height] = cellRect(filter, index)
    const shot = shots[index]
    if (shot) crop(ctx, shot, shot.width, shot.height, x, y, width, height)
    else {
      ctx.fillStyle = '#e7eff8'
      ctx.fillRect(x, y, width, height)
    }
  }
  if (art && !filter.anchor) ctx.drawImage(art, 0, 0, SIZE, SIZE)
}

export function drawFaces(ctx, filter, image, faces) {
  for (const face of faces) {
    const w = face.width * filter.scale
    const h = w * image.height / image.width
    const hair = filter.anchor === 'hair'
    const cx = hair ? face.x + face.width / 2 : face.mouthX
    const cy = hair ? face.y + face.height * .06
      : face.mouthY + (filter.anchor === 'chin' ? face.height * .17 : 0)
    ctx.save()
    ctx.translate(cx, cy)
    ctx.rotate(face.angle)
    ctx.drawImage(image, -w / 2, -h / 2, w, h)
    ctx.restore()
  }
}
