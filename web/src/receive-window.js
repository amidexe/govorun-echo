// Receiving has a deadline; notes and drafts do not. A reload never grants another eight hours.
export const RECEIVE_MS = 8 * 60 * 60 * 1000
export const receiving = (until, now = Date.now()) => Number.isFinite(until) && now < until
// A paused workspace still accepts authenticated revocation from its paired phone.
export const accepting = (kind, until, now = Date.now()) => kind === 'bye' || kind === 'ping' || receiving(until, now)
export const extend = (until, now = Date.now()) => Math.max(Number.isFinite(until) ? until : 0, now) + RECEIVE_MS
