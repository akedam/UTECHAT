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
    if (!uid || data?.recipientUid !== uid || !['call', 'message'].includes(data.eventType)) return
    const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    if (clients.some(client => client.focused)) return
    const call = data.eventType === 'call'
    const id = data.conversationId
    if (!/^[A-Za-z0-9_-]{1,256}$/.test(id || '')) return
    await self.registration.showNotification(call ? 'Cuộc gọi UTEChat' : 'UTEChat', {
      body: call ? 'Mở UTEChat để trả lời cuộc gọi.' : 'Bạn có tin nhắn mới.',
      icon: '/apple-touch-icon.png',
      tag: call ? 'call-' + data.callId : 'chat-' + id,
      data: { id, uid }
    })
  })())
})

self.addEventListener('notificationclick', event => {
  event.notification.close()
  event.waitUntil((async () => {
    const { id, uid } = event.notification.data || {}
    const cache = await caches.open(state)
    if (!uid || uid !== await (await cache.match(stateRequest))?.text()) return
    const url = new URL('/', self.location.origin)
    if (id) url.searchParams.set('chat', id)
    const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    for (const client of clients) {
      if (!client.focus) continue
      client.postMessage({ type: 'conversation', id, uid })
      await client.focus()
      return
    }
    await self.clients.openWindow(url.toString())
  })())
})
