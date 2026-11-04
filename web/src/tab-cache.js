// A reload keeps this tab's window. IndexedDB holds ciphertext only; its wrapping secret lives in sessionStorage.
// Runtime ECDH keys remain non-extractable. Disconnect/idle drops the secret immediately, even if storage is unavailable.
const enc = new TextEncoder()
const dec = new TextDecoder()
const b64 = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes)))
const bytes = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0))
const iv = () => crypto.getRandomValues(new Uint8Array(12))

export function indexedVault() {
  let opened
  const db = () => opened ??= new Promise((resolve, reject) => {
    const r = indexedDB.open('echo-tab-cache-v1', 1)
    r.onupgradeneeded = () => r.result.createObjectStore('windows')
    r.onsuccess = () => { r.result.onversionchange = () => r.result.close(); resolve(r.result) }
    r.onerror = () => reject(r.error)
  })
  async function run(mode, action) {
    const database = await db()
    return new Promise((resolve, reject) => {
      const tx = database.transaction('windows', mode)
      const req = action(tx.objectStore('windows'))
      tx.oncomplete = () => resolve(req.result)
      tx.onabort = tx.onerror = () => reject(tx.error ?? new Error('storage'))
    })
  }
  return {
    get: (id) => run('readonly', (s) => s.get(id)), put: (id, record) => run('readwrite', (s) => s.put(record, id)), delete: (id) => run('readwrite', (s) => s.delete(id)),

  }
}

export class TabCache {
  constructor(storage, vault, namespace) {
    this.storage = storage; this.vault = vault; this.name = `echo-window-v1:${namespace}`
    this.queue = Promise.resolve(); this.epoch = 0; this.pending = 0
  }
  async key(seed) { return crypto.subtle.importKey('raw', bytes(seed), 'AES-GCM', false, ['encrypt', 'decrypt', 'wrapKey', 'unwrapKey']) }
  aad(suffix = '') { return enc.encode(`echo-tab-v1:${this.ref.id}${suffix}`) }
  async protect(privateKey, room) {
    const nonce = iv(); const options = { name: 'AES-GCM', iv: nonce, additionalData: this.aad(`:key:${room}`) }
    const wrapped = await crypto.subtle.wrapKey('pkcs8', privateKey, this.secret, options)
    return { wrappedKey: { iv: b64(nonce), data: b64(wrapped) }, privateKey: await crypto.subtle.unwrapKey('pkcs8', wrapped, this.secret, options, { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']) }
  }
  async create(factory) {
    this.ref = { id: crypto.randomUUID(), seed: b64(crypto.getRandomValues(new Uint8Array(32))) }
    this.secret = await this.key(this.ref.seed)
    try { this.storage.setItem(this.name, JSON.stringify(this.ref)) }
    catch { this.ref = null; this.secret = null; return factory() }
    const session = await factory({ protect: (key, room) => this.protect(key, room) })
    await this.save(session, null)
    return session
  }
  async restore() {
    try {
      const raw = this.storage.getItem(this.name)
      if (!raw) return null
      this.ref = JSON.parse(raw); this.secret = await this.key(this.ref.seed)
      const record = await this.vault.get(this.ref.id)
      if (!record) { await this.clear(); return null }
      const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: record.iv, additionalData: this.aad() }, this.secret, record.cipher)
      const { session, state } = JSON.parse(dec.decode(plain))
      session.privateKey = await crypto.subtle.unwrapKey('pkcs8', bytes(session.wrappedKey.data), this.secret,
        { name: 'AES-GCM', iv: bytes(session.wrappedKey.iv), additionalData: this.aad(`:key:${session.room}`) }, { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits'])
      session.pub = bytes(session.pairing.split('.').at(-1).replaceAll('-', '+').replaceAll('_', '/'))
        return { session, state }
    } catch { await this.clear(); return null }
  }
  save(session, state) {
    if (!this.ref || !this.secret || !session.wrappedKey) return Promise.resolve(false)
    const generation = this.epoch; const ref = this.ref; const key = this.secret; const aad = this.aad()
    const plain = enc.encode(JSON.stringify({ session: { room: session.room, wait: session.wait, pairing: session.pairing, phone: session.phone, wrappedKey: session.wrappedKey }, state }))
    this.pending++
    const job = this.queue.then(async () => {
      if (generation !== this.epoch) return false
      const nonce = iv(); const cipher = await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: aad }, key, plain)
      if (generation !== this.epoch) return false
      await this.vault.put(ref.id, { iv: nonce, cipher })
      return true
    }).catch(() => false).finally(() => { this.pending-- })
    this.queue = job
    return job
  }
  async clear() {
    this.epoch++
    const id = this.ref?.id
    this.ref = null; this.secret = null
    try { this.storage.removeItem(this.name) } catch { /* Still clear the in-memory secret. */ }
    await this.queue
    if (id) try { await this.vault.delete(id) } catch { /* Orphaned ciphertext has no tab secret. */ }
  }
}
