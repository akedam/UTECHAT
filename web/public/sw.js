const state = 'utechat-web-push'
const stateRequest = new Request(new URL('/push-account', self.location.origin))

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()))

self.addEventListener('message', event => {
  if (event.data?.type !== 'account' || event.source?.type !== 'window') return
  event.waitUntil((async () => {
    const cache = await caches.open(state)
    if (event.data.uid) await cache.put(stateRequest, new Response(event.data.uid))
    else {
      await cache.delete(stateRequest)
      for (const notice of await self.registration.getNotifications()) notice.close()
    }
    event.ports[0]?.postMessage(true)
  })())
})

self.addEventListener('push', event => {
  event.waitUntil((async () => {
    let data
    try { data = event.data?.json()?.data } catch { return }
    const cache = await caches.open(state)
    const uid = await (await cache.match(stateRequest))?.text()
    if (!uid || data?.recipientUid !== uid || !['call', 'message', 'moment'].includes(data.eventType)) return
    if (data.eventType === 'moment') {
      const id = data.momentId
      if (!/^[a-f0-9]{64}$/.test(id || '')) return
      await self.registration.showNotification('Tin mới', {
        body: 'Mở UTEChat để xem ảnh.',
        icon: '/apple-touch-icon.png',
        tag: 'moment-' + id,
        data: { id, uid, kind: 'moment' }
      })
      return
    }
    const call = data.eventType === 'call'
    const id = data.conversationId
    if (!/^[A-Za-z0-9_-]{1,256}$/.test(id || '')) return
    const preview = data.showPreview === 'true' && typeof data.preview === 'string' && data.preview.trim().slice(0, 300)
    const title = preview && String(data.conversationTitle || data.senderName || '').slice(0, 80)
    await self.registration.showNotification(call ? 'Cuộc gọi UTEChat' : title || 'UTEChat', {
      body: call ? 'Mở UTEChat để trả lời cuộc gọi.' : preview || 'Bạn có tin nhắn mới.',
      icon: '/apple-touch-icon.png',
      silent: false,
      renotify: !call,
      tag: call ? 'call-' + data.callId : 'chat-' + id,
      data: { id, uid }
    })
  })())
})

self.addEventListener('notificationclick', event => {
  event.notification.close()
  event.waitUntil((async () => {
    const { id, uid, kind } = event.notification.data || {}
    const cache = await caches.open(state)
    if (!uid || uid !== await (await cache.match(stateRequest))?.text()) return
    const url = new URL('/', self.location.origin)
    if (id) url.searchParams.set(kind === 'moment' ? 'moment' : 'chat', id)
    const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    for (const client of clients) {
      let focused
      try { focused = await client.focus() } catch { continue }
      if (await handOff(focused, { type: kind === 'moment' ? 'moment' : 'conversation', id, uid })) return
      try { if (await focused.navigate(url.href)) return } catch { /* Open the link in a new window below. */ }
    }
    await self.clients.openWindow(url.toString())
  })())
})

function handOff(client, message) {
  return new Promise(resolve => {
    const channel = new MessageChannel()
    const finish = opened => {
      clearTimeout(timer)
      channel.port1.close()
      resolve(opened)
    }
    const timer = setTimeout(() => finish(false), 600)
    channel.port1.onmessage = event => finish(event.data === true)
    try { client.postMessage(message, [channel.port2]) } catch {
      channel.port2.close()
      finish(false)
    }
  })
}
