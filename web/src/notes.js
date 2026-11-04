// What the tab holds and how each message from the phone changes it, apart from the page so it can be tested on its own.

export const SILENT_MS = 7 * 60 * 1000

export const empty = () => ({ notes: [], fresh: new Set(), device: '', original: 'Исходный', loading: null, ended: false })

/** The state after one message: `notes` (all of them, in parts, newest first), `changes`, `ping` or `bye`. */
export function apply(state, message) {
  if (message.kind === 'notes') {
    const part = message.part ?? 0
    const of = message.parts ?? 1
    const first = part === 0
    return {
      ...state,
      device: first ? (message.device ?? '') : state.device,
      original: first ? (message.original ?? 'Исходный') : state.original,
      notes: [...new Map([...(first ? [] : state.notes), ...message.notes].map((n) => [n.id, n])).values()],
      fresh: first ? new Set() : state.fresh,
      loading: part + 1 < of ? { got: part + 1, of } : null,
    }
  }
  if (message.kind === 'changes') {
    const byId = new Map(state.notes.map((n) => [n.id, n]))
    const fresh = new Set(state.fresh)
    for (const n of message.notes) {
      if (!byId.has(n.id)) fresh.add(n.id)
      byId.set(n.id, n)
    }
    for (const id of message.gone ?? []) {
      byId.delete(id)
      fresh.delete(id)
    }
    return { ...state, notes: [...byId.values()], fresh }
  }
  if (message.kind === 'bye') return { ...empty(), ended: true }
  return state
}

/** The phone pings every 3 minutes; heard nothing for 7 means it is asleep, killed or offline. */
export const silent = (heardAt, now) => heardAt > 0 && now - heardAt > SILENT_MS
