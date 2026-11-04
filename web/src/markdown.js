import MarkdownIt from 'markdown-it'

// Note content is untrusted. Raw HTML is text; remote images never load from a note.
const md = new MarkdownIt({ html: false, linkify: false, typographer: false })
const validLink = md.validateLink.bind(md)
md.validateLink = (url) => /^(https?:|mailto:)/i.test(url) && validLink(url)
md.renderer.rules.image = (tokens, i) => md.utils.escapeHtml(tokens[i].content)
md.renderer.rules.link_open = (tokens, i, options, env, self) => {
  tokens[i].attrSet('target', '_blank')
  tokens[i].attrSet('rel', 'noopener noreferrer')
  return self.renderToken(tokens, i, options)
}

const fold = (s) => s.toLowerCase().replaceAll('ё', 'е')
md.renderer.rules.text = (tokens, i, options, env) => {
  const text = tokens[i].content
  const words = (env.words ?? []).map(fold).filter(Boolean)
  const escaped = md.utils.escapeHtml
  if (!words.length) return escaped(text)
  const folded = fold(text)
  let out = ''; let at = 0
  while (at < text.length) {
    let hit = -1; let size = 0
    for (const word of words) {
      const index = folded.indexOf(word, at)
      if (index >= 0 && (hit < 0 || index < hit)) { hit = index; size = word.length }
    }
    if (hit < 0) break
    out += escaped(text.slice(at, hit)) + '<mark>' + escaped(text.slice(hit, hit + size)) + '</mark>'
    at = hit + size
  }
  return out + escaped(text.slice(at))
}

export const renderMarkdown = (text, words = []) => md.render(text, { words })

// Use the same parser as the reader: escaped punctuation and code remain literal,
// formatting disappears, and a link keeps its destination for pasting elsewhere.
export function plainMarkdown(text) {
  const lists = []
  let out = ''
  const line = () => { if (out && !out.endsWith('\n')) out += '\n' }
  const inline = (tokens) => {
    let value = ''
    const links = []
    for (const t of tokens ?? []) {
      if (t.type === 'link_open') links.push({ start: value.length, href: t.attrGet('href') })
      else if (t.type === 'link_close') {
        const l = links.pop()
        if (l?.href && value.slice(l.start) !== l.href) value += ` (${l.href})`
      } else if (t.type === 'softbreak' || t.type === 'hardbreak') value += '\n'
      else if (t.type === 'image') value += t.content
      else if (t.type === 'text' || t.type === 'code_inline' || t.type === 'html_inline') value += t.content
    }
    return value
  }
  for (const t of md.parse(text, {})) {
    if (t.type === 'inline') out += inline(t.children)
    else if (t.type === 'ordered_list_open' || t.type === 'bullet_list_open') {
      line()
      lists.push({ ordered: t.type === 'ordered_list_open', next: Number(t.attrGet('start') || 1) })
    } else if (t.type === 'ordered_list_close' || t.type === 'bullet_list_close') {
      lists.pop(); line()
      if (!lists.length) out += '\n'
    } else if (t.type === 'list_item_open') {
      line()
      const list = lists.at(-1)
      out += '  '.repeat(Math.max(0, lists.length - 1)) + (list?.ordered ? `${list.next++}. ` : '• ')
    } else if (t.type === 'fence' || t.type === 'code_block' || t.type === 'html_block') {
      line(); out += t.content; line(); out += '\n'
    } else if (t.type === 'paragraph_close' || t.type === 'heading_close' || t.type === 'blockquote_close') {
      line()
      if (!lists.length) out += '\n'
    } else if (t.type === 'hr') { line(); out += '———\n\n' }
    else if (t.type === 'td_close' || t.type === 'th_close') out += '\t'
    else if (t.type === 'tr_close') { out = out.replace(/\t$/, ''); line() }
    else if (t.type === 'table_close') out += '\n'
  }
  return out.trimEnd()
}
