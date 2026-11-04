<script>
  import { onDestroy, tick } from 'svelte'
  import { createTheme } from './theme.svelte.js'
  import { fold, when, length, snippet, marks } from './display.js'
  import ThemeSwitch from './ThemeSwitch.svelte'
  import ExportActions from './ExportActions.svelte'
  import ReaderContent from './ReaderContent.svelte'
  import NotesList from './NotesList.svelte'
  import PairingScreen from './PairingScreen.svelte'
  import qrcode from 'qrcode-generator'
  import { newSession, listen } from './session.js'
  import { TabCache, indexedVault } from './tab-cache.js'
  import { RECEIVE_MS, receiving, accepting, extend } from './receive-window.js'
  import { icons } from './icons.js'
  import { apply } from './notes.js'
  import { inSection, newest } from './library.js'
  import { plainMarkdown } from './markdown.js'
  import { layersOf as contentLayers, layerOf as contentLayer, searchText, commonLayers, metadataOf, asText as contentText, asPlainText, isMarkdown } from './note-content.js'

  // Someone pointed a phone's own camera at the code: the page that opens there only says where to scan it.
  const scannedByCamera = location.pathname.endsWith('/p') && location.hash.startsWith('#2.')

  let receiveUntil = $state(Date.now() + RECEIVE_MS)
  let selection = $state(null)
  let sessionInfo = $state(null)
  let sessionInfoButton = $state(null)
  let receiveTimer
  let sessionOpen = $state(false)
  let navigationDialog = $state(null)
  let navigationOpen = $state(false)

  let stage = $state(scannedByCamera ? 'camera' : 'qr') // qr → notes → ended
  let endedBy = $state('') // idle | phone | here
  let link = $state('') // connected | receiving | offline
  let qrSvg = $state('')
  let debugPairing = $state('')
  let device = $state('')
  let notes = $state([])
  let fresh = $state(new Set()) // came while the window was open
  let scope = $state('all') // all | favorites | archive
  let tag = $state('')
  let query = $state('')
  let chosen = $state(new Set())
  let openId = $state('')
  let readerDismissed = $state(false)
  let compactPane = $state(false)
  let paneElement = $state(null)
  let listElement = $state(null)
  let returnFocus
  let actionMenu = $state(null)
  const theme = createTheme(() => sessionInfo?.hidePopover())
  let withDates = $state(false)
  let draft = $state('')
  let edited = $state(false)
  let toast = $state('')
  let originalName = $state('Исходный')
  let loading = $state(null) // { got, of } while a large library arrives in parts
  let heardAt = $state(0)
  // The layer read and copied: the improved text when a note has one, unless the person turns to what was said.
  let view = $state('original')

  let stop = () => {}
  let persistTimer
  let activeSession = $state(null)
  const tabStorage = { getItem: (k) => sessionStorage.getItem(k), setItem: (k, v) => sessionStorage.setItem(k, v), removeItem: (k) => sessionStorage.removeItem(k) }
  const cache = new TabCache(tabStorage, indexedVault(), import.meta.env.BASE_URL)

  function snapshot() {
    return { stage, notes, fresh: [...fresh], device, originalName, loading, openId, readerDismissed, compactPane, chosen: [...chosen], view, scope, tag, query, draft, edited, withDates, heardAt, receiveUntil, selection }
  }

  async function start() {
    stop()
    forget()
    const saved = await cache.restore()
    const session = saved?.session ?? await cache.create(newSession)
    activeSession = session
    // Only in development: the code's text, for tools/fake-phone.mjs and the emulator, which has no camera to point.
    if (import.meta.env.DEV) { window.__echoPairing = session.pairing; debugPairing = session.pairing }
    const qr = qrcode(0, 'M')
    qr.addData(session.pairing)
    qr.make()
    qrSvg = qr.createSvgTag({ cellSize: 4, margin: 0, scalable: true })
    if (saved?.state) {
      const s = saved.state
      ;({ notes, device, originalName, loading, openId, view, scope, tag, query, draft, edited, withDates, heardAt } = s)
      receiveUntil = Number.isFinite(s.receiveUntil) ? s.receiveUntil : Date.now() + RECEIVE_MS
      selection = s.selection ?? null
      readerDismissed = s.readerDismissed ?? false
      compactPane = s.compactPane ?? false
      fresh = new Set(s.fresh); chosen = new Set(s.chosen); stage = s.stage === 'notes' ? 'notes' : 'qr'
    } else { stage = 'qr'; receiveUntil = Date.now() + RECEIVE_MS; selection = null; heardAt = 0 }
    link = ''
    resumeReception(false)
  }

  function forget() {
    notes = []
    fresh = new Set()
    chosen = new Set()
    openId = ''
    readerDismissed = false
    compactPane = false
    draft = ''
    edited = false
    query = ''
    tag = ''
    scope = 'all'
  }

  async function receive(message) {
    if (!activeSession || !accepting(message.kind, receiveUntil)) { pauseReception(); return false }
    // Presence is authenticated by the transport; it never rewrites the note archive.
    if (message.kind === 'ping') return stage === 'qr' ? cache.save(activeSession, snapshot()) : true
    heardAt = Date.now()
    const next = apply({ notes, fresh, device, original: originalName, loading, ended: false }, message)
    if (next.ended) { await end('phone'); return true }
    ;({ notes, fresh, device, loading } = next)
    originalName = next.original
    if (message.kind === 'notes') {
      if (stage !== 'notes') { receiveUntil = Date.now() + RECEIVE_MS; armReception() }
      stage = 'notes'
    }
    if (message.selection?.kind === 'all') selection = { kind: 'all' }
    else if (message.selection?.kind === 'tags' && Array.isArray(message.selection.tags) && message.selection.tags.every(t => typeof t === 'string')) selection = { kind: 'tags', tags: message.selection.tags }
    if (!loading) chosen = new Set([...chosen].filter((id) => notes.some((n) => n.id === id)))
    const saved = await cache.save(activeSession, snapshot())
    if (!saved) { say('Не удалось сохранить данные вкладки. Проверьте свободное место в браузере.'); return false }
    return true
  }

  function pauseReception() {
    clearTimeout(receiveTimer)
    link = 'paused'
  }

  function armReception() {
    clearTimeout(receiveTimer)
    receiveTimer = setTimeout(pauseReception, Math.max(0, receiveUntil - Date.now()))
  }

  function resumeReception(prolong = true) {
    stop()
    if (prolong) receiveUntil = extend(receiveUntil)
    clearTimeout(receiveTimer)
    const session = activeSession
    if (!session) return
    link = ''
    stop = listen(session, receive, (s) => (link = receiving(receiveUntil) ? s : 'paused'), () => say('Не удалось принять сообщение'))
    if (receiving(receiveUntil)) armReception()
    else pauseReception()
  }

  async function end(by) {
    closeNavigation()
    sessionInfo?.hidePopover()
    sessionOpen = false
    theme.close()
    stop()
    clearTimeout(receiveTimer)
    activeSession = null
    clearTimeout(persistTimer)
    // clear() drops the tab secret synchronously; ciphertext cleanup may finish later.
    void cache.clear()
    forget()
    qrSvg = ''
    endedBy = by
    stage = 'ended'
  }

  if (!scannedByCamera) start()
  onDestroy(() => {
    stop()
    clearTimeout(receiveTimer)
    clearTimeout(persistTimer)
  })

  // Local selections and edited text survive a reload too. Explicit disconnect clears the tab secret instead.
  $effect(() => {
    const session = activeSession
    const state = snapshot()
    clearTimeout(persistTimer)
    if (session) persistTimer = setTimeout(() => { void cache.save(session, state) }, 200)
  })

  let words = $derived(fold(query).split(/\s+/).filter(Boolean))
  // Each note's words, folded once when it arrives, so a semester of lectures is searched as you type.
  let hay = $derived(
    new Map(notes.map((n) => [n.id, fold(searchText(n))])),
  )
  let inScope = $derived(inSection(notes, scope, !!query.trim()))
  let tags = $derived.by(() => {
    const set = new Set()
    inScope.forEach((n) => (n.tags ?? []).forEach((t) => set.add(t)))
    return [...set].sort((a, b) => a.localeCompare(b, 'ru'))
  })
  let shown = $derived(
    inScope
      .filter((n) => {
        if (tag && !(n.tags ?? []).includes(tag)) return false
        const text = hay.get(n.id)
        return words.every((w) => text.includes(w))
      })
      .sort((a, b) => b.at - a.at),
  )

  let opened = $derived(notes.find((n) => n.id === openId))
  let picked = $derived(notes.filter((n) => chosen.has(n.id)).sort((a, b) => a.at - b.at))
  let pane = $derived(picked.length ? 'glue' : opened ? 'reader' : '')
  // A background/automatic choice must not hide the list in a compact window.
  const compactMedia = window.matchMedia('(max-width: 980px)')
  let compact = $state(compactMedia.matches)
  function resize() {
    closeNavigation()
    compact = compactMedia.matches
    theme.close()
    compactPane = !!pane
  }
  compactMedia.addEventListener('change', resize)
  onDestroy(() => compactMedia.removeEventListener('change', resize))
  $effect(() => { if (!pane) compactPane = false })
  $effect(() => {
    if (stage === 'notes' && !loading && !chosen.size && !readerDismissed && !shown.some((n) => n.id === openId)) {
      openId = newest(shown)?.id ?? ''
      view = 'original'
    }
  })

  const layersOf = (n) => contentLayers(n, originalName)
  const layerOf = (n) => contentLayer(n, view, originalName)
  const metadata = (n) => metadataOf(n, view, originalName)
  const asText = (n, dates) => contentText(n, view, originalName, dates ? when(n.at) : '')
  const asPlain = (n, dates) => asPlainText(n, view, originalName, dates ? when(n.at) : '')
  let plainDraft = $derived(edited
    ? picked.some((n) => isMarkdown(n, view, originalName)) ? plainMarkdown(draft) : draft
    : picked.map((n) => asPlain(n, withDates)).join('\n\n'))
  let pickedLayers = $derived(commonLayers(picked, originalName))
  $effect(() => { if (picked.length && !pickedLayers.some((l) => l.key === view)) view = 'original' })

  // The glued text follows the choice until it is edited by hand; then it stays as written.
  $effect(() => {
    const text = picked.map((n) => asText(n, withDates)).join('\n\n')
    if (!edited) draft = text
  })
  const PAGE = 100
  let drawn = $state(PAGE)
  $effect(() => {
    query
    tag
    scope
    drawn = PAGE
  })
  function more(node) {
    const seen = new IntersectionObserver((e) => e[0].isIntersecting && (drawn += PAGE), { rootMargin: '600px' })
    seen.observe(node)
    return { destroy: () => seen.disconnect() }
  }

  function toggle(id) {
    const next = new Set(chosen)
    next.has(id) ? next.delete(id) : next.add(id)
    chosen = next
    edited = false
  }

  async function enterPane() {
    returnFocus = document.activeElement
    compactPane = true
    await tick()
    paneElement?.focus({ preventScroll: true })
  }

  async function closePane() {
    actionMenu = null
    compactPane = false
    if (chosen.size) chosen = new Set()
    else { openId = ''; readerDismissed = true }
    await tick()
    if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true })
    else listElement?.querySelector('input')?.focus({ preventScroll: true })
  }

  async function backToSelection() {
    actionMenu = null
    compactPane = false
    await tick()
    listElement?.querySelector('.compact-selection button')?.focus({ preventScroll: true })
  }

  async function show(id) {
    chosen = new Set()
    actionMenu = null
    openId = id
    readerDismissed = false
    view = 'original'
    const note = notes.find((n) => n.id === id)
    if (note && words.length && !words.every((w) => fold(note.text || '').includes(w))) {
      view = layersOf(note).find((l) => words.every((w) => fold([l.title, l.about, l.text].filter(Boolean).join(' ')).includes(w)))?.key || 'original'
    }
    const next = new Set(fresh)
    next.delete(id)
    fresh = next
    await enterPane()
    const reader = paneElement?.querySelector('.reader')
    if (reader) reader.scrollTop = 0
  }

  function place(nextScope, nextTag = '') {
    closeNavigation()
    scope = nextScope
    tag = nextTag
    readerDismissed = false
  }

  async function copy(text, count) {
    actionMenu = null
    try {
      await navigator.clipboard.writeText(text)
      say(count > 1 ? `Скопировано заметок: ${count}` : 'Скопировано')
    } catch {
      say('Браузер не дал скопировать')
    }
  }

  function save(text, markdown = false) {
    actionMenu = null
    const name = `echo-${new Date().toISOString().slice(0, 10)}.${markdown ? 'md' : 'txt'}`
    const url = URL.createObjectURL(new Blob([text], { type: markdown ? 'text/markdown' : 'text/plain;charset=utf-8' }))
    const a = Object.assign(document.createElement('a'), { href: url, download: name })
    a.click()
    URL.revokeObjectURL(url)
  }

  function openNavigation() {
    if (!navigationDialog?.open) navigationDialog?.showModal()
    navigationOpen = true
  }
  function closeNavigation() {
    navigationDialog?.close()
    navigationClosed()
  }
  function navigationClosed() {
    navigationOpen = false
    theme.close()
    sessionInfo?.hidePopover()
  }
  function dismissNavigation(e) {
    if (e.target !== navigationDialog) return
    const r = navigationDialog.getBoundingClientRect()
    if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) closeNavigation()
  }

  // Native popover supplies light-dismiss, Escape and focus behaviour.
  function placeSessionInfo() {
    if (!sessionInfo?.matches(':popover-open') || !sessionInfoButton) return
    const anchor = sessionInfoButton.getBoundingClientRect()
    const panel = sessionInfo.getBoundingClientRect()
    sessionInfo.style.left = `${Math.max(12, Math.min(anchor.right - panel.width, innerWidth - panel.width - 12))}px`
    sessionInfo.style.top = `${Math.max(12, anchor.top > panel.height + 20 ? anchor.top - panel.height - 8 : Math.min(anchor.bottom + 8, innerHeight - panel.height - 12))}px`
  }
  function sessionToggled(e) {
    sessionOpen = e.newState === 'open'
    if (sessionOpen) { theme.close(); placeSessionInfo() }
  }

  function keys(e) {
    if (e.key !== 'Escape') return
    if (sessionInfo?.matches(':popover-open')) return
    if (theme.menu) { theme.close(); e.preventDefault(); return }
    if (navigationOpen) return
    if (actionMenu) { actionMenu = null; return }
    void closePane()
  }

  let toastTimer
  function say(text) {
    toast = text
    clearTimeout(toastTimer)
    toastTimer = setTimeout(() => (toast = ''), 2200)
  }

</script>

<svelte:window onresize={placeSessionInfo} onkeydown={keys} onpointerdown={(e) => { if (!e.target.closest('.export-menu')) actionMenu = null; theme.outside(e) }} />

{#snippet icon(name, size = 20)}
  <span class="icon s{size}" aria-hidden="true">{@html icons[name]}</span>
{/snippet}

{#snippet marked(text)}{#each marks(text, words) as p}{#if p.hit}<mark>{p.t}</mark>{:else}{p.t}{/if}{/each}{/snippet}

{#snippet themeSwitch()}<ThemeSwitch {theme} {icon} />{/snippet}

{#snippet navigation()}
      <div class="side-header"><div class="brand">Говорун Эхо</div>{@render themeSwitch()}
        {#if compact}<button class="place theme-button" onclick={closeNavigation} aria-label="Закрыть меню">{@render icon('close', 18)}</button>{/if}
      </div>
      <button class="place" class:on={scope === 'all' && !tag} onclick={() => place('all')}>{@render icon('tray', 18)}<span>Все</span></button>
      <button class="place" class:on={scope === 'favorites' && !tag} onclick={() => place('favorites')}>{@render icon('star', 18)}<span>Избранное</span></button>
      <button class="place" class:on={scope === 'archive' && !tag} onclick={() => place('archive')}>{@render icon('archive', 18)}<span>Архив</span></button>
      {#if tags.length}
        <div class="section">Теги</div>
        <div class="tags">
          {#each tags as t}
            <button class="place tag" class:on={tag === t} onclick={() => place(scope, tag === t ? '' : t)}>{@render icon('tag', 18)}<span>{t}</span></button>
          {/each}
        </div>
      {/if}
      <div class="grow"></div>
      <button class="place session-control" class:on={sessionOpen} popovertarget="session-info" bind:this={sessionInfoButton}
        aria-label="Сеанс на компьютере" aria-expanded={sessionOpen}>
        <span class="session-copy"><span>Сеанс</span><span class="session-caption">{loading ? `Получено ${loading.got} из ${loading.of}` : link === 'paused' ? 'Приём на паузе' : link === 'offline' ? 'Нет связи' : 'На компьютере'}</span></span>
        {@render icon('power', 18)}
      </button>
      <div id="session-info" class="session-info" popover="auto" bind:this={sessionInfo} ontoggle={sessionToggled}>
        <div class="session-info-head">
          <strong>Сеанс</strong>
          <button class="bare utility-icon" popovertarget="session-info" popovertargetaction="hide" aria-label="Закрыть сведения">{@render icon('close', 18)}</button>
        </div>
        <div class="session-phone">{device || 'Телефон'}</div>
        <dl>
          <div><dt>Заметки</dt><dd>{selection?.kind === 'tags' ? selection.tags.join(', ') : 'Все заметки'}</dd></div>
          <div><dt>{link === 'paused' ? 'Приём' : 'Приём до'}</dt><dd class="session-reception"><span>{link === 'paused' ? 'Пауза' : when(receiveUntil)}</span>
            <button class="place utility-icon" onclick={() => resumeReception()}
              aria-label={link === 'paused' ? 'Продолжить получение обновлений' : 'Принимать ещё 8 часов'}
              title={link === 'paused' ? 'Продолжить получение обновлений' : 'Принимать ещё 8 часов'}>{@render icon('refresh', 18)}</button>
          </dd></div>
          {#if heardAt}<div><dt>Передача</dt><dd>{when(heardAt)}</dd></div>{/if}
        </dl>
        {#if link === 'offline'}<p class="session-offline">Нет связи с сервером. Полученные заметки доступны.</p>{/if}
        <div class="session-actions"><button class="place" onclick={() => end('here')}>Завершить сеанс</button></div>
      </div>
{/snippet}

{#snippet exportActions(raw, plain, count = 1, fileRaw = raw, filePlain = plain)}
  <ExportActions {raw} {plain} {count} {fileRaw} {filePlain} {icon}
    open={actionMenu === pane} onToggle={() => (actionMenu = actionMenu === pane ? null : pane)}
    onSave={save} onCopy={copy} />
{/snippet}

{#if stage !== 'notes'}
  <PairingScreen {stage} {qrSvg} {debugPairing} {endedBy} {link} {icon} onStart={start} onResume={resumeReception} />
{:else}
  <div class="app" class:with-pane={pane} class:compact-open={pane && compactPane}>
    {#if compact}
      <header class="mobile-header">
        <button class="place mobile-menu" onclick={openNavigation} aria-label="Открыть меню" aria-expanded={navigationOpen}>{@render icon('menu', 20)}</button>
        <span>{tag || (scope === 'favorites' ? 'Избранное' : scope === 'archive' ? 'Архив' : 'Все заметки')}</span>
      </header>
      <dialog class="navigation-dialog" bind:this={navigationDialog} aria-label="Меню" onclose={navigationClosed} onpointerdown={dismissNavigation}>
        <nav class="side" aria-label="Навигация">{@render navigation()}</nav>
      </dialog>
    {:else}
      <nav class="side" aria-label="Навигация">{@render navigation()}</nav>
    {/if}

    <NotesList bind:query bind:element={listElement} {scope} {tag} pickedCount={picked.length}
      {shown} {drawn} {chosen} {openId} {fresh} {marked} {icon} {more}
      preview={(n) => snippet(n, words, layersOf)} onSearch={() => (readerDismissed = false)}
      onEnterPane={enterPane} onClear={() => (chosen = new Set())} onToggle={toggle} onShow={show} />

    {#if pane === 'reader'}
      <aside class="pane card" bind:this={paneElement} tabindex="-1" aria-label="Заметка">
        <div class="pane-head">
          <button class="bare compact-back" onclick={closePane} aria-label="К списку">{@render icon('back', 18)}<span>К списку</span></button>
          <span class="meta">{when(opened.at)}{#if opened.dur} · {length(opened.dur)}{/if}</span>
          <button class="bare wide-close" onclick={closePane} aria-label="Закрыть">{@render icon('close', 18)}</button>
        </div>
        {#if layersOf(opened).length > 1}
          <div class="layers" role="tablist">
            {#each layersOf(opened) as l (l.key)}
              <button role="tab" aria-selected={layerOf(opened).key === l.key} class:on={layerOf(opened).key === l.key} onclick={() => (view = l.key)}>{l.name}</button>
            {/each}
          </div>
        {/if}
        <ReaderContent note={opened} layer={layerOf(opened)} metadata={metadata(opened)}
          markdown={isMarkdown(opened, view, originalName)} {words} {marked} />
        {@render exportActions(asText(opened, false), asPlain(opened, false), 1, asText(opened, true), asPlain(opened, true))}
      </aside>
    {:else if pane === 'glue'}
      <aside class="pane card" bind:this={paneElement} tabindex="-1" aria-label="Выбранные заметки">
        <div class="pane-head">
          <button class="bare compact-back" onclick={backToSelection} aria-label="К списку">{@render icon('back', 18)}<span>К списку</span></button>
          <span>Выбрано: {picked.length}</span>
          {#if pickedLayers.length > 1}
            <div class="layers small" role="tablist">
              {#each pickedLayers as l (l.key)}
                <button role="tab" aria-selected={view === l.key} class:on={view === l.key} onclick={() => ((view = l.key), (edited = false))}>{l.name}</button>
              {/each}
            </div>
          {/if}
          <label class="toggle"><input type="checkbox" bind:checked={withDates} onchange={() => (edited = false)} /> С датами</label>
          <button class="bare wide-close" onclick={closePane} aria-label="Снять выбор">{@render icon('close', 18)}</button>
        </div>
        <textarea bind:value={draft} oninput={() => (edited = true)} aria-label="Склеенный текст"></textarea>
        <div class="hint">Правки здесь меняют только то, что вы скопируете. Заметки на телефоне остаются как были.</div>
        {@render exportActions(draft, plainDraft, picked.length)}
      </aside>
    {/if}
  </div>
{/if}
{#if toast}<div class="toast" role="status">{toast}</div>{/if}
