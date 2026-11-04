// One visit: a room and a key pair made here, the public half shown to the phone as a QR code, and a loop that waits for what
// the phone sends.
//
// QR:      https://echo.govorun.club/p#2.<room>.<this tab's P-256 public key>  (after #, so no browser sends it anywhere)
// Room:    the first 22 base64url characters of SHA-256 over a waiting secret that stays in this tab: whoever photographs the
//          code can send into the room, only this tab can wait in it.
// Message: 0x02 · the phone's P-256 public key (65 bytes) · 12-byte IV · AES-256-GCM(key, gzip(JSON)), the room as additional
//          data; key = HKDF-SHA-256(ECDH(tab, phone), salt = room, info = "echo-web 2").
// Runtime keys are non-extractable. Reload recovery wraps a temporary key locally; its secret stays in this tab.

const enc = new TextEncoder()
const b64url = (bytes) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
const relay = `${import.meta.env.BASE_URL}v1`

export function browserLabel(ua = '') {
  const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Браузер'
  const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Windows/.test(ua) ? 'Windows' : /CrOS/.test(ua) ? 'ChromeOS' : /Macintosh/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : ''
  return { browser, os }
}

export async function newSession({ protect } = {}) {
  const secret = crypto.getRandomValues(new Uint8Array(16))
  const room = b64url(new Uint8Array(await crypto.subtle.digest('SHA-256', secret))).slice(0, 22)
  const pair = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, !!protect, ['deriveBits'])
  const pub = new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey))
  const label = browserLabel(typeof navigator === 'undefined' ? '' : navigator.userAgent)
  const metadata = new URLSearchParams(label)
  return {
    room,
    wait: b64url(secret),
    ...(protect ? await protect(pair.privateKey, room) : { privateKey: pair.privateKey }),
    pub,
    phone: null,
    pairing: `${location.origin}${import.meta.env.BASE_URL}p?${metadata}#2.${room}.${b64url(pub)}`,
  }
}

export class OtherPhone extends Error {}

// Opens a message; the first phone that sends is the only one this visit listens to after.
export async function open(session, message) {
  const bytes = new Uint8Array(message)
  if (bytes[0] !== 2 || bytes.length < 1 + 65 + 12 + 16) throw new Error('format')
  const phonePub = bytes.slice(1, 66)
  const phoneId = b64url(phonePub)
  if (session.phone && session.phone !== phoneId) throw new OtherPhone()
  const phone = await crypto.subtle.importKey('raw', phonePub, { name: 'ECDH', namedCurve: 'P-256' }, false, [])
  const shared = await crypto.subtle.deriveBits({ name: 'ECDH', public: phone }, session.privateKey, 256)
  const hkdf = await crypto.subtle.importKey('raw', shared, 'HKDF', false, ['deriveKey'])
  const key = await crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: enc.encode(session.room), info: enc.encode('echo-web 2') },
    hkdf,
    { name: 'AES-GCM', length: 256 },
    false,
    ['decrypt'],
  )
  const plain = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: bytes.subarray(66, 78), additionalData: enc.encode(session.room) },
    key,
    bytes.subarray(78),
  )
  const json = await new Response(new Blob([plain]).stream().pipeThrough(new DecompressionStream('gzip'))).text()
  const payload = JSON.parse(json)
  if (!payload || typeof payload.kind !== 'string') throw new Error('format')
  session.phone = phoneId
  return payload
}

export async function acceptance(session, delivery) {
  if (!session.phone) throw new Error('unpaired')
  const raw = Uint8Array.from(atob(session.phone.replaceAll('-', '+').replaceAll('_', '/')), c => c.charCodeAt(0))
  const phone = await crypto.subtle.importKey('raw', raw, { name: 'ECDH', namedCurve: 'P-256' }, false, [])
  const shared = await crypto.subtle.deriveBits({ name: 'ECDH', public: phone }, session.privateKey, 256)
  const hkdf = await crypto.subtle.importKey('raw', shared, 'HKDF', false, ['deriveKey'])
  const key = await crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: enc.encode(session.room), info: enc.encode('echo-web 2 receipt') }, hkdf, { name: 'HMAC', hash: 'SHA-256', length: 256 }, false, ['sign'])
  return crypto.subtle.sign('HMAC', key, enc.encode(delivery))
}

// Waits for messages until stopped: each one goes to onMessage; a relay that is away is asked again a little later.
export function listen(session, onMessage, onState, onStranger) {
  let stopped = false
  let controller
  ;(async () => {
    let pause = 1000
    while (!stopped) {
      controller = new AbortController()
      try {
        const res = await fetch(`${relay}/wait/${session.room}`, {
          signal: controller.signal,
          cache: 'no-store',
          headers: { 'X-Echo-Wait': session.wait },
        })
        if (res.status === 200) {
          onState('receiving')
          const body = await res.arrayBuffer()
          try {
            const message = await open(session, body)
            if (!stopped) {
              const accepted = await onMessage(message)
              if (accepted !== false) {
                if (!/^[A-Za-z0-9_-]{22}$/.test(message.delivery ?? '')) throw new Error('missing receipt id')
                const proof = await acceptance(session, message.delivery)
                const ack = await fetch(`${relay}/receipt/${session.room}/${message.delivery}`, {
                  method: 'POST', headers: { 'X-Echo-Wait': session.wait }, body: proof,
                })
                if (!ack.ok) throw new Error('receipt failed')
              }
            }
          } catch (e) {
            // Someone who saw the code sent something: it is not from the phone this visit belongs to, and is dropped.
            onStranger(e)
          }
          if (!stopped) onState('connected')
        } else if (res.status !== 204) {
          throw new Error(`relay ${res.status}`)
        }
        pause = 1000
      } catch (e) {
        if (stopped) break
        onState('offline')
        await new Promise((r) => setTimeout(r, pause + Math.random() * pause / 5))
        pause = Math.min(pause * 2, 60000)
      }
    }
  })()
  return () => {
    stopped = true
    controller?.abort()
  }
}
