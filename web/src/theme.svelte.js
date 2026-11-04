import { onDestroy } from 'svelte'

/** State belongs to the root app, including while the QR screen is visible. */
export function createTheme(onOpen) {
  let themeMenu = $state(false)
  let themeMenuTop = $state(0)
  let themeMenuLeft = $state(0)
  function toggle(e) {
    onOpen()
    const rect = e.currentTarget.getBoundingClientRect()
    themeMenuLeft = Math.max(8, Math.min(window.innerWidth - 216, rect.right - 208))
    themeMenuTop = rect.bottom + 144 < window.innerHeight ? rect.bottom + 6 : Math.max(8, rect.top - 134)
    themeMenu = !themeMenu
  }
  const systemTheme = window.matchMedia('(prefers-color-scheme: dark)')
  let systemDark = $state(systemTheme.matches)
  let theme = $state('system')
  let dark = $derived(theme === 'system' ? systemDark : theme === 'dark')
  try {
    const saved = localStorage.getItem('echo-theme')
    if (['system', 'light', 'dark'].includes(saved)) theme = saved
  } catch { /* The switch still works when browser storage is blocked. */ }
  $effect(() => { document.documentElement.dataset.theme = dark ? 'dark' : 'light' })
  function followTheme(e) { systemDark = e.matches }
  systemTheme.addEventListener('change', followTheme)
  function select(next) {
    theme = next
    themeMenu = false
    try { localStorage.setItem('echo-theme', theme) } catch {}
  }

  onDestroy(() => systemTheme.removeEventListener('change', followTheme))
  return {
    get mode() { return theme },
    get dark() { return dark },
    get menu() { return themeMenu },
    get top() { return themeMenuTop },
    get left() { return themeMenuLeft },
    toggle, select,
    close() { themeMenu = false },
    outside(e) { if (!e.target.closest('.theme-control')) themeMenu = false },
  }
}
