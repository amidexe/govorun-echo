import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

// In development the relay runs beside Vite (go run ../relay); on the server nginx serves dist/ and passes /v1 to it.
const relay = process.env.ECHO_RELAY ?? 'http://127.0.0.1:8787'

export default defineConfig({
  plugins: [svelte()],
  // The page is echo.govorun.club, a site of its own, unlinked and not indexed until Echo is public.
  base: '/',
  // ECHO_RELAY=https://echo.govorun.club tries the page against the relay on the server.
  server: { proxy: { '/v1': { target: new URL(relay).origin, changeOrigin: true, rewrite: (p) => new URL(relay).pathname.replace(/\/$/, '') + p } } },
  build: { target: 'es2022', assetsInlineLimit: 0 },
})
