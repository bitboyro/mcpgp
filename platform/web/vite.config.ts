import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// The page is this dev server; it proxies /api to a running platform.
// `npm run build` writes the same page into the platform jar (target/classes/static).
// MCPGP_WEB_PORT and MCPGP_API_PORT pick other ports.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'MCPGP_')
  const webPort = Number(env.MCPGP_WEB_PORT || 5173)
  const apiPort = Number(env.MCPGP_API_PORT || 8443)
  return {
    plugins: [react()],
    build: {
      outDir: '../target/classes/static',
      emptyOutDir: true,
      // fonts as files, never data: URIs (the platform's CSP allows fonts from 'self' only)
      assetsInlineLimit: 0,
    },
    server: {
      host: '127.0.0.1',
      port: webPort,
      strictPort: true,
      // keep the Host header: the platform checks a browser session's Origin against it
      proxy: { '/api': { target: `http://127.0.0.1:${apiPort}`, changeOrigin: false } },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
      setupFiles: ['src/test-setup.ts'],
      // whole tests that click through several pages can pass 5 s under a full parallel run
      testTimeout: 15_000,
    },
  }
})
