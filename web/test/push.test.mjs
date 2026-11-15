import { readFile } from 'node:fs/promises'
import { runInNewContext } from 'node:vm'
import { MessageChannel } from 'node:worker_threads'
import assert from 'node:assert/strict'
import test from 'node:test'

const worker = await readFile(new URL('../public/sw.js', import.meta.url), 'utf8')
const origin = 'https://utechat-ab9d8.web.app'

function fixture(windows = []) {
  const events = new Map()
  const shown = []
  const opened = []
  const cache = new Map()
  const self = {
    location: { origin },
    addEventListener: (name, callback) => events.set(name, callback),
    registration: {
      async showNotification(title, options) { shown.push({ title, ...options }) },
      async getNotifications() { return [] }
    },
    clients: {
      async matchAll() { return windows },
      async openWindow(url) { opened.push(url) }
    }
  }
  runInNewContext(worker, {
    self, URL, Request, Response, MessageChannel, setTimeout, clearTimeout,
    caches: { async open() {
      return {
        async match(request) { return cache.get(request.url)?.clone() },
        async put(request, value) { cache.set(request.url, value) },
        async delete(request) { cache.delete(request.url) }
      }
    } }
  })
  function emit(name, payload) {
    let result
    events.get(name)({ ...payload, waitUntil: promise => { result = promise } })
    return result
  }
  return {
    shown, opened,
    bind(uid) { return emit('message', { source: { type: 'window' }, data: { type: 'account', uid }, ports: [] }) },
    deliver(data) { return emit('push', { data: { json: () => ({ data }) } }) },
    click(data = shown.at(-1)?.data) { return emit('notificationclick', { notification: { data, close() {} } }) }
  }
}

test('valid pushes show a notification even with UTEChat focused, and clear on account logout', async () => {
  const sw = fixture([{ focused: true }])
  await sw.bind('alice')
  await sw.deliver({ eventType: 'call', recipientUid: 'alice', callId: 'call1', conversationId: 'chat1' })
  assert.equal(sw.shown.length, 1)
  assert.equal(sw.shown[0].tag, 'call-call1')
  assert.equal(sw.shown[0].data.id, 'chat1')
  await sw.deliver({ eventType: 'message', recipientUid: 'bob', conversationId: 'chat2' })
  await sw.deliver({ eventType: 'message', recipientUid: 'alice', conversationId: '//external' })
  await sw.bind(null)
  await sw.deliver({ eventType: 'message', recipientUid: 'alice', conversationId: 'chat1' })
  await sw.click()
  assert.equal(sw.shown.length, 1)
  assert.equal(sw.opened.length, 0)
})

test('a tap focuses a ready client and waits for its confirmation before finishing', async () => {
  let received
  const sw = fixture([{ async focus() {
    return { postMessage(data, [port]) { received = data; port.postMessage(true); port.close() } }
  } }])
  await sw.bind('alice')
  await sw.click({ id: 'chat1', uid: 'alice' })
  assert.equal(received.id, 'chat1')
  assert.equal(sw.opened.length, 0)
})

test('the worker exposes the sender and content only for an explicit preview', async () => {
  const sw = fixture()
  await sw.bind('alice')
  const data = { eventType: 'message', recipientUid: 'alice', conversationId: 'chat1', senderName: 'Bob', preview: 'Hello' }
  await sw.deliver(data)
  assert.equal(sw.shown[0].title, 'UTEChat')
  assert.equal(sw.shown[0].body, 'Bạn có tin nhắn mới.')
  await sw.deliver({ ...data, showPreview: 'true' })
  assert.equal(sw.shown[1].title, 'Bob')
  assert.equal(sw.shown[1].body, 'Hello')
})

test('a tap navigates an unready client or opens the conversation when the app is closed', async () => {
  const navigated = []
  const fallback = fixture([{ async focus() {
    return {
      postMessage(data, [port]) { port.postMessage(false); port.close() },
      async navigate(url) { navigated.push(url); return this }
    }
  } }])
  const closed = fixture()
  for (const sw of [fallback, closed]) {
    await sw.bind('alice')
    await sw.click({ id: 'chat1', uid: 'alice' })
  }
  assert.deepEqual(navigated, [origin + '/?chat=chat1'])
  assert.deepEqual(closed.opened, navigated)
})
