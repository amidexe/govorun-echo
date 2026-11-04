import { previewOf } from './note-content.js'

export const fold = (s) => s.toLowerCase().replaceAll('ё', 'е')

// One chronological list, as in the phone app. Favorites never move a note above newer ones.
const dayName = new Intl.DateTimeFormat('ru', { day: 'numeric', month: 'long' })
const dayNameYear = new Intl.DateTimeFormat('ru', { day: 'numeric', month: 'long', year: 'numeric' })
const time = new Intl.DateTimeFormat('ru', { hour: '2-digit', minute: '2-digit' })
export function dayOf(ms) {
  const d = new Date(ms)
  const today = new Date()
  const start = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const days = Math.round((start(today) - start(d)) / 86400000)
  if (days === 0) return 'Сегодня'
  if (days === 1) return 'Вчера'
  return d.getFullYear() === today.getFullYear() ? dayName.format(d) : dayNameYear.format(d)
}
export const when = (ms) => `${dayOf(ms)}, ${time.format(ms)}`
const shortDay = new Intl.DateTimeFormat('ru', { day: 'numeric', month: 'short' })
export function rowWhen(ms) {
  const d = new Date(ms); const today = new Date()
  if (d.toDateString() === today.toDateString()) return time.format(d)
  return d.getFullYear() === today.getFullYear() ? shortDay.format(d) : dayNameYear.format(d)
}
export function length(ms, compact = false) {
  const s = Math.round(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const pad = (x) => String(x).padStart(2, '0')
  return h ? compact ? `${h}:${pad(m)} ч` : `${h}:${pad(m)}:${pad(s % 60)}` : `${m}:${pad(s % 60)}`
}

// A card holds a piece of the text, not a lecture: its start, or the place a searched word is in, as search engines show it.
const SNIPPET = 260
export function snippet(n, words, layersOf) {
  let text = previewOf(n)
  if (words.length && !words.some((w) => fold(text).includes(w))) {
    const match = layersOf(n).find((l) => words.some((w) => fold([l.about, l.text].filter(Boolean).join(' ')).includes(w)))
    if (match) text = [match.about, match.text].filter(Boolean).join('\n')
  }
  if (text.length <= SNIPPET) return text
  let at = -1
  if (words.length) {
    const f = fold(text)
    for (const w of words) {
      const j = f.indexOf(w)
      if (j >= 0 && (at < 0 || j < at)) at = j
    }
  }
  if (at < 0) return text.slice(0, SNIPPET).trimEnd() + '…'
  const start = Math.max(0, at - 80)
  return (start ? '…' : '') + text.slice(start, start + SNIPPET).trim() + (start + SNIPPET < text.length ? '…' : '')
}

// The list draws a hundred cards and adds more as it is scrolled, so thousands of notes stay light.
export function marks(text, words) {
  if (!words.length) return [{ t: text }]
  const f = fold(text)
  const out = []
  let i = 0
  while (i < text.length) {
    let best = -1
    let len = 0
    for (const w of words) {
      const j = f.indexOf(w, i)
      if (j >= 0 && (best < 0 || j < best)) {
        best = j
        len = w.length
      }
    }
    if (best < 0) break
    if (best > i) out.push({ t: text.slice(i, best) })
    out.push({ t: text.slice(best, best + len), hit: true })
    i = best + len
  }
  if (i < text.length) out.push({ t: text.slice(i) })
  return out
}
