// @vitest-environment node
// Tests de la Pages Function (functions/_shared/proxy.ts) con el WebCrypto de Node.
// Viven fuera de functions/ para que Pages nunca los trate como rutas.
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { onRequest as apiOnRequest } from '../functions/api/[[path]]'
import { onRequest as syncOnRequest } from '../functions/sync/[[path]]'
import { resetAccessKeyCache, type ProxyEnv } from '../functions/_shared/proxy'

// Valores inventados: ningún dominio, aud ni token reales.
const TEAM = 'equipo-de-prueba.cloudflareaccess.com'
const AUD = 'aud-de-prueba-0123456789'
const ORIGIN = 'https://api.example.test'
const API_TOKEN = 'token-de-prueba-0123456789abcdefghijkl'
const EMAIL = 'duena@example.test'
const KID = 'kid-1'

const ENV: ProxyEnv = {
  API_ORIGIN: ORIGIN,
  API_TOKEN,
  ACCESS_TEAM_DOMAIN: TEAM,
  ACCESS_AUD: AUD,
}

let signingKey: CryptoKey
let otherKey: CryptoKey
let publicJwk: JsonWebKey

function b64url(bytes: Uint8Array | string): string {
  const raw = typeof bytes === 'string' ? new TextEncoder().encode(bytes) : bytes
  return Buffer.from(raw)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

async function makeJwt(
  claims: Record<string, unknown> = {},
  opts: { key?: CryptoKey; kid?: string } = {},
): Promise<string> {
  const now = Math.floor(Date.now() / 1000)
  const header = b64url(JSON.stringify({ alg: 'RS256', kid: opts.kid ?? KID, typ: 'JWT' }))
  const payload = b64url(
    JSON.stringify({
      aud: [AUD],
      iss: `https://${TEAM}`,
      email: EMAIL,
      iat: now - 10,
      nbf: now - 10,
      exp: now + 3600,
      ...claims,
    }),
  )
  const signature = await crypto.subtle.sign(
    'RSASSA-PKCS1-v1_5',
    opts.key ?? signingKey,
    new TextEncoder().encode(`${header}.${payload}`),
  )
  return `${header}.${payload}.${b64url(new Uint8Array(signature))}`
}

async function rsaPair(): Promise<CryptoKeyPair> {
  return crypto.subtle.generateKey(
    {
      name: 'RSASSA-PKCS1-v1_5',
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: 'SHA-256',
    },
    true,
    ['sign', 'verify'],
  )
}

interface Seen {
  url: string
  headers: Headers
  method: string
}

let originCalls: Seen[]
let certCalls: number

beforeAll(async () => {
  const pair = await rsaPair()
  signingKey = pair.privateKey
  publicJwk = await crypto.subtle.exportKey('jwk', pair.publicKey)
  otherKey = (await rsaPair()).privateKey
})

beforeEach(() => {
  resetAccessKeyCache()
  originCalls = []
  certCalls = 0
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input instanceof Request ? input.url : input)
      if (url === `https://${TEAM}/cdn-cgi/access/certs`) {
        certCalls++
        const jwk = { ...publicJwk, kid: KID, alg: 'RS256', use: 'sig' }
        return Response.json({ keys: [jwk], public_cert: { kid: KID, cert: '-' } })
      }
      originCalls.push({ url, headers: new Headers(init?.headers), method: init?.method ?? 'GET' })
      return Response.json({ ok: true }, { headers: { 'X-Upstream': '1' } })
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function request(path: string, headers: Record<string, string> = {}, method = 'GET'): Request {
  return new Request(`https://consola.example.test${path}`, { method, headers })
}

async function errorOf(res: Response): Promise<string> {
  const body = (await res.json()) as { error?: unknown }
  expect(typeof body.error).toBe('string')
  return body.error as string
}

describe('Pages Function proxy', () => {
  it('proxies a valid Access JWT, adds the bearer and strips client credentials', async () => {
    const jwt = await makeJwt()
    const res = await apiOnRequest({
      request: request('/api/projections/alarmas/alarmas_activas?tz=UTC', {
        'Cf-Access-Jwt-Assertion': jwt,
        'Cf-Access-Authenticated-User-Email': EMAIL,
        Cookie: `CF_Authorization=${jwt}; otra=1`,
        Authorization: 'Bearer token-del-navegador',
        Accept: 'application/json',
      }),
      env: ENV,
    })
    expect(res.status).toBe(200)
    expect(res.headers.get('X-Upstream')).toBe('1')
    expect(originCalls).toHaveLength(1)
    const call = originCalls[0]!
    expect(call.url).toBe(`${ORIGIN}/api/projections/alarmas/alarmas_activas?tz=UTC`)
    expect(call.headers.get('Authorization')).toBe(`Bearer ${API_TOKEN}`)
    expect(call.headers.get('Cookie')).toBeNull()
    expect(call.headers.get('Cf-Access-Jwt-Assertion')).toBeNull()
    expect(call.headers.get('Cf-Access-Authenticated-User-Email')).toBeNull()
    expect(call.headers.get('Accept')).toBe('application/json')
  })

  it('accepts the CF_Authorization cookie as fallback, on /sync too', async () => {
    const jwt = await makeJwt()
    const res = await syncOnRequest({
      request: request('/sync/pull?since=0', { Cookie: `a=b; CF_Authorization=${jwt}` }),
      env: ENV,
    })
    expect(res.status).toBe(200)
    expect(originCalls[0]?.url).toBe(`${ORIGIN}/sync/pull?since=0`)
    expect(originCalls[0]?.headers.get('Cookie')).toBeNull()
  })

  it('proxies GET /sync/devices like the other /sync routes', async () => {
    const jwt = await makeJwt()
    const res = await syncOnRequest({
      request: request('/sync/devices', { 'Cf-Access-Jwt-Assertion': jwt }),
      env: ENV,
    })
    expect(res.status).toBe(200)
    expect(originCalls[0]?.url).toBe(`${ORIGIN}/sync/devices`)
    expect(originCalls[0]?.headers.get('Authorization')).toBe(`Bearer ${API_TOKEN}`)
  })

  it('caches the team keys between requests', async () => {
    const jwt = await makeJwt()
    for (let i = 0; i < 3; i++) {
      await apiOnRequest({
        request: request('/api/x', { 'Cf-Access-Jwt-Assertion': jwt }),
        env: ENV,
      })
    }
    expect(certCalls).toBe(1)
    expect(originCalls).toHaveLength(3)
  })

  it('rejects a request without token', async () => {
    const res = await apiOnRequest({ request: request('/api/x'), env: ENV })
    expect(res.status).toBe(403)
    expect(await errorOf(res)).toMatch(/Sesión no válida/)
    expect(originCalls).toHaveLength(0)
  })

  it('rejects a bad signature', async () => {
    const jwt = await makeJwt({}, { key: otherKey })
    const res = await apiOnRequest({
      request: request('/api/x', { 'Cf-Access-Jwt-Assertion': jwt }),
      env: ENV,
    })
    expect(res.status).toBe(403)
    expect(originCalls).toHaveLength(0)
  })

  it('rejects a tampered payload', async () => {
    const [h, , s] = (await makeJwt()).split('.')
    const forged = `${h}.${b64url(JSON.stringify({ aud: [AUD], iss: `https://${TEAM}`, exp: 9999999999 }))}.${s}`
    const res = await apiOnRequest({
      request: request('/api/x', { 'Cf-Access-Jwt-Assertion': forged }),
      env: ENV,
    })
    expect(res.status).toBe(403)
    expect(originCalls).toHaveLength(0)
  })

  it('rejects alg none and unknown kid', async () => {
    const payload = b64url(JSON.stringify({ aud: [AUD], iss: `https://${TEAM}`, exp: 9999999999 }))
    const none = `${b64url(JSON.stringify({ alg: 'none', kid: KID }))}.${payload}.`
    const unknownKid = await makeJwt({}, { kid: 'otro' })
    for (const jwt of [none, unknownKid, 'no-es-un-jwt']) {
      const res = await apiOnRequest({
        request: request('/api/x', { 'Cf-Access-Jwt-Assertion': jwt }),
        env: ENV,
      })
      expect(res.status).toBe(403)
    }
    expect(originCalls).toHaveLength(0)
  })

  it('rejects the wrong aud', async () => {
    const jwt = await makeJwt({ aud: ['otra-aplicacion'] })
    const res = await apiOnRequest({
      request: request('/api/x', { 'Cf-Access-Jwt-Assertion': jwt }),
      env: ENV,
    })
    expect(res.status).toBe(403)
    expect(originCalls).toHaveLength(0)
  })

  it('rejects the wrong issuer', async () => {
    const jwt = await makeJwt({ iss: 'https://otro-equipo.cloudflareaccess.com' })
    const res = await apiOnRequest({
      request: request('/api/x', { 'Cf-Access-Jwt-Assertion': jwt }),
      env: ENV,
    })
    expect(res.status).toBe(403)
    expect(originCalls).toHaveLength(0)
  })

  it('rejects an expired token and one not yet valid', async () => {
    const now = Math.floor(Date.now() / 1000)
    for (const claims of [{ exp: now - 1 }, { nbf: now + 600 }, { exp: undefined }]) {
      const jwt = await makeJwt(claims)
      const res = await apiOnRequest({
        request: request('/api/x', { 'Cf-Access-Jwt-Assertion': jwt }),
        env: ENV,
      })
      expect(res.status).toBe(403)
    }
    expect(originCalls).toHaveLength(0)
  })

  it.each(['API_ORIGIN', 'API_TOKEN', 'ACCESS_TEAM_DOMAIN', 'ACCESS_AUD'] as const)(
    'answers 403 without contacting anything when %s is missing',
    async (name) => {
      const jwt = await makeJwt()
      const env: ProxyEnv = { ...ENV, [name]: '' }
      const res = await apiOnRequest({
        request: request('/api/x', { 'Cf-Access-Jwt-Assertion': jwt }),
        env,
      })
      expect(res.status).toBe(403)
      expect(await errorOf(res)).toMatch(/no configurado/)
      expect(fetch).not.toHaveBeenCalled()
    },
  )

  it('enforces ACCESS_ALLOWED_EMAIL case-insensitively', async () => {
    const jwt = await makeJwt({ email: 'Duena@Example.Test' })
    const ok = await apiOnRequest({
      request: request('/api/x', { 'Cf-Access-Jwt-Assertion': jwt }),
      env: { ...ENV, ACCESS_ALLOWED_EMAIL: EMAIL.toUpperCase() },
    })
    expect(ok.status).toBe(200)

    const other = await makeJwt({ email: 'intrusa@example.test' })
    const res = await apiOnRequest({
      request: request('/api/x', { 'Cf-Access-Jwt-Assertion': other }),
      env: { ...ENV, ACCESS_ALLOWED_EMAIL: EMAIL },
    })
    expect(res.status).toBe(403)
    expect(originCalls).toHaveLength(1)
  })

  it('accepts ACCESS_TEAM_DOMAIN written as a URL', async () => {
    const jwt = await makeJwt()
    const res = await apiOnRequest({
      request: request('/api/x', { 'Cf-Access-Jwt-Assertion': jwt }),
      env: { ...ENV, ACCESS_TEAM_DOMAIN: `https://${TEAM}/` },
    })
    expect(res.status).toBe(200)
  })

  it('keeps rejecting methods other than GET, POST and OPTIONS', async () => {
    const res = await apiOnRequest({ request: request('/api/x', {}, 'DELETE'), env: ENV })
    expect(res.status).toBe(405)
    expect(fetch).not.toHaveBeenCalled()
  })
})
