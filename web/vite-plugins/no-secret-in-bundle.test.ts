// @vitest-environment node
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { filesContaining, noSecretInBundle } from './no-secret-in-bundle'

const SECRET = 'token-inventado-0123456789abcdefghijkl'
let dir = ''

afterEach(() => {
  if (dir) rmSync(dir, { recursive: true, force: true })
})

function fakeDist(files: Record<string, string>): string {
  dir = mkdtempSync(path.join(tmpdir(), 'cauce-dist-'))
  for (const [name, content] of Object.entries(files)) {
    mkdirSync(path.dirname(path.join(dir, name)), { recursive: true })
    writeFileSync(path.join(dir, name), content)
  }
  return dir
}

describe('noSecretInBundle', () => {
  it('finds the token in any output file, nested or not', () => {
    const out = fakeDist({
      'index.html': '<html>',
      'assets/app.js': `const t="${SECRET}"`,
      _redirects: '/* /',
    })
    expect(filesContaining(out, [SECRET, ''])).toEqual([path.join('assets', 'app.js')])
  })

  it('ignores empty secrets and clean output', () => {
    const out = fakeDist({ 'assets/app.js': 'const t=1' })
    expect(filesContaining(out, ['', SECRET])).toEqual([])
    expect(filesContaining(out, [])).toEqual([])
  })

  it('fails the build without printing the value', () => {
    const out = fakeDist({ 'assets/app.js': SECRET })
    const plugin = noSecretInBundle([SECRET])
    const configResolved = plugin.configResolved as (c: unknown) => void
    configResolved({ root: out, build: { outDir: '.' } })
    const closeBundle = plugin.closeBundle as () => void
    expect(closeBundle).toThrow(/aparece en el build/)
    try {
      closeBundle()
    } catch (error) {
      expect(String(error)).not.toContain(SECRET)
    }
  })
})
