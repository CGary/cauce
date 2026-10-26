/// <reference types="vitest/config" />
import { fileURLToPath } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const repoRoot = fileURLToPath(new URL('..', import.meta.url))

// Experimento futuro (NO activo): medir el bundle con Preact en lugar de React
// sin tocar el código, vía el alias de compatibilidad:
//   resolve: { alias: { react: 'preact/compat', 'react-dom': 'preact/compat',
//                       'react/jsx-runtime': 'preact/jsx-runtime' } }
// Requiere agregar `preact` como dependencia. Solo vale la pena si el chunk
// inicial de React resulta un problema real.
export default defineConfig(({ mode }) => ({
  plugins: [react(), tailwindcss()],
  // El service worker de MSW solo se sirve en modo mock; nunca llega a dist/. En el build normal, public/ aporta _redirects.
  publicDir: mode === 'mock' ? 'mocks-public' : 'public',
  server: {
    proxy: { '/api': 'http://localhost:8080' },
    // Los mocks leen los fixtures del contrato en ../schema.
    fs: { allow: [repoRoot] },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
}))
