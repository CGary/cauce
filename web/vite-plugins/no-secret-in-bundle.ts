import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import type { Plugin } from 'vite'

/** Files under `dir` (recursive) whose bytes contain any of `secrets`. Empty secrets are ignored. */
export function filesContaining(dir: string, secrets: string[]): string[] {
  const needles = secrets.filter((s) => s.length > 0).map((s) => Buffer.from(s))
  if (needles.length === 0) return []
  const hits: string[] = []
  const walk = (current: string) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name)
      if (entry.isDirectory()) walk(full)
      else if (needles.some((n) => readFileSync(full).includes(n)))
        hits.push(path.relative(dir, full))
    }
  }
  walk(dir)
  return hits
}

/**
 * Build check (D20): the API token lives only in the dev proxy (vite.config.ts) and in the Pages
 * secret API_TOKEN. If its value shows up anywhere in the build output, the build fails.
 * The error names the files, never the value.
 */
export function noSecretInBundle(secrets: string[]): Plugin {
  let outDir = 'dist'
  return {
    name: 'cauce:no-secret-in-bundle',
    apply: 'build',
    configResolved(config) {
      outDir = path.resolve(config.root, config.build.outDir)
    },
    closeBundle() {
      const hits = filesContaining(outDir, secrets)
      if (hits.length > 0) {
        throw new Error(
          `El token de la API aparece en el build (${hits.join(', ')}). No se puede publicar así.`,
        )
      }
    },
  }
}
