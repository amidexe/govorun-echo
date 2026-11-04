export function layersOf(n, original = 'Расшифровка') {
  const keys = new Set(['original'])
  const saved = (n.layers ?? []).filter((l) => {
    if (!l || !l.key || keys.has(l.key)) return false
    keys.add(l.key)
    return true
  })
  const names = { fair: 'Текст', brief: 'Кратко' }
  const order = { fair: 0, brief: 1 }
  const readable = saved.map((l) => ({ ...l, name: names[l.key] || l.name }))
    .sort((a, b) => (order[a.key] ?? 2) - (order[b.key] ?? 2))
  return [{ key: 'original', name: n.originalName || (original === 'Исходный' ? 'Расшифровка' : original), text: n.text || '', cut: n.aboutCut || (n.sourceCut ?? n.cut) }, ...readable]
}
export const layerOf = (n, key, original) => layersOf(n, original).find((l) => l.key === key) ?? layersOf(n, original)[0]
const savedTitle = (n) => n.title || (n.layers ?? []).find((l) => l.title)?.title || ''
export const titleOf = (n) => savedTitle(n)
export const previewOf = (n) => n.about || (n.layers ?? []).find((l) => l.key === 'brief' && l.about)?.about || (n.layers ?? []).find((l) => l.about)?.about || n.text || ''
export const searchText = (n) => [n.title, n.about, n.text, ...(n.layers ?? []).flatMap((l) => [l.title, l.about, l.text])].filter(Boolean).join(' ')
export const commonLayers = (notes, original) => notes.length ? layersOf(notes[0], original).filter((l) => notes.every((n) => layersOf(n, original).some((x) => x.key === l.key))) : []
export function metadataOf(n, key, original) {
  const l = layerOf(n, key, original)
  // A linked working text has its own legacy metadata, never the digest header from its source.
  return { title: (l.key === 'fair' ? '' : n.title) || l.title || '', about: l.key === 'original' ? n.about || '' : l.about || '' }
}
export function asText(n, key, original, date = '') {
  const l = layerOf(n, key, original)
  const { title, about } = metadataOf(n, key, original)
  // A display-only first-phrase title must not duplicate the source in copy/download.
  return [date, title, about, l.text].filter(Boolean).join('\n\n')
}
import { plainMarkdown } from './markdown.js'


export const isMarkdown = (n, key, original) => layerOf(n, key, original).key !== 'original' || n.originalName === 'Текст'

export function asPlainText(n, key, original, date = '') {
  const l = layerOf(n, key, original)
  const { title, about } = metadataOf(n, key, original)
  const text = isMarkdown(n, key, original) ? plainMarkdown(l.text || '') : l.text
  return [date, title, about, text].filter(Boolean).join('\n\n')
}
