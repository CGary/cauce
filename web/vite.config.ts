/// <reference types="vitest/config" />
import { fileURLToPath } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'
import { noSecretInBundle } from './vite-plugins/no-secret-in-bundle'

const repoRoot = fileURLToPath(new URL('..', import.meta.url))

// Experimento futuro (NO activo): medir el bundle con Preact en lugar de React
// sin tocar el código, vía el alias de compatibilidad:
//   resolve: { alias: { react: 'preact/compat', 'react-dom': 'preact/compat',
//                       'react/jsx-runtime': 'preact/jsx-runtime' } }
// Requiere agregar `preact` como dependencia. Solo vale la pena si el chunk
// inicial de React resulta un problema real.
export default defineConfig(({ mode }) => {
  // Prefijo '' = también variables sin VITE_. Solo se usan aquí, en Node: Vite expone al
  // navegador únicamente las VITE_*, así que el token nunca entra al bundle (y el plugin
  // noSecretInBundle lo verifica en cada build).
  const env = loadEnv(mode, process.cwd(), '')
  const devToken = env.CAUCE_API_TOKEN?.trim() ?? ''
  // En desarrollo, el proxy de Vite hace de Pages Function: agrega el bearer que exige el servidor (D20).
  const target = {
    target: env.CAUCE_DEV_API_ORIGIN?.trim() || 'http://localhost:8080',
    headers: devToken ? { Authorization: `Bearer ${devToken}` } : undefined,
  }
  return {
    plugins: [react(), tailwindcss(), noSecretInBundle([devToken, env.API_TOKEN?.trim() ?? ''])],
    // El service worker de MSW solo se sirve en modo mock; nunca llega a dist/. En el build normal, public/ aporta _redirects.
    publicDir: mode === 'mock' ? 'mocks-public' : 'public',
    server: {
      proxy: { '/api': target, '/sync': target },
      // Los mocks leen los fixtures del contrato en ../schema.
      fs: { allow: [repoRoot] },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: [
        'src/**/*.test.{ts,tsx}',
        'functions-test/**/*.test.ts',
        'vite-plugins/**/*.test.ts',
      ],
    },
  }
})
