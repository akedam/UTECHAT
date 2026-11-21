import { api, auth } from './firebase'

const prefix = uid => `utechat.web.pending.${uid}.`
const memory = new Map()
const permanent = new Set([400, 403, 404, 409])

export function queueNetworkJob(uid, kind, id, data, expiresAt) {
  const key = prefix(uid) + kind + ':' + id
  const job = { kind, data, expiresAt }
  memory.set(key, job)
  try { localStorage.setItem(key, JSON.stringify(job)) } catch { /* Keep retrying in this tab. */ }
  window.dispatchEvent(new Event('utechat-network-job'))
}

export function watchNetworkJobs(uid, finalizeCall) {
  let stopped = false
  const running = new Set()
  let timer
  const scope = prefix(uid)
  async function drain() {
    if (stopped || !navigator.onLine || auth.currentUser?.uid !== uid) return
    clearTimeout(timer)
    const jobs = new Map([...memory].filter(([key]) => key.startsWith(scope)))
    try {
      for (const key of Object.keys(localStorage).filter(key => key.startsWith(scope))) {
        try { jobs.set(key, JSON.parse(localStorage.getItem(key))) } catch { localStorage.removeItem(key) }
      }
    } catch { /* Use the in-memory entries. */ }
    const available = [...jobs].filter(([key]) => !running.has(key))
    const selected = [
      ...available.filter(([, job]) => job?.kind !== 'message'),
      ...available.filter(([, job]) => job?.kind === 'message').slice(0, 4)
    ]
    await Promise.all(selected.map(async ([key, job]) => {
      running.add(key)
      try {
        try {
          if (job?.expiresAt > Date.now()) {
            if (job.kind === 'call-status') await finalizeCall(job.data)
            else if (job.kind === 'message' || job.kind === 'invite') {
              await api(job.kind === 'message' ? '/api/message-notify' : '/api/call-invite',
                { body: job.data, signal: AbortSignal.timeout?.(65_000) })
            }
          }
        } catch (failure) {
          if (!permanent.has(failure.httpStatus) && failure.code !== 'permission-denied') {
            return
          }
        }
        memory.delete(key)
        try { localStorage.removeItem(key) } catch { /* The in-memory entry is gone. */ }
      } finally { running.delete(key) }
    }))
    if (!stopped && selected.length) {
      clearTimeout(timer)
      timer = setTimeout(drain, 5_000)
    }
  }
  window.addEventListener('online', drain)
  window.addEventListener('utechat-network-job', drain)
  void drain()
  return () => {
    stopped = true
    clearTimeout(timer)
    window.removeEventListener('online', drain)
    window.removeEventListener('utechat-network-job', drain)
  }
}
