// Proxy de mismo origen hacia la API, con dos cerrojos (D14, D20):
//  1. Verifica el JWT de Cloudflare Access de cada petición (RS256 con las claves del equipo,
//     `aud`, `iss`, `exp`, `nbf` y, opcional, el email). No basta con que Access esté delante:
//     cada deploy de Pages tiene también su hostname `<hash>.<proyecto>.pages.dev`.
//  2. Reenvía a API_ORIGIN agregando `Authorization: Bearer <API_TOKEN>`; nunca reenvía la
//     Authorization, las cookies ni las cabeceras Cf-Access-* del navegador.
// Falla cerrado: sin configuración o sin JWT válido responde 403 y no contacta al origen.
// Solo WebCrypto, sin dependencias. Este módulo no exporta `onRequest` para no ser una ruta.

export interface ProxyEnv {
  API_ORIGIN?: string
  API_TOKEN?: string
  ACCESS_TEAM_DOMAIN?: string
  ACCESS_AUD?: string
  ACCESS_ALLOWED_EMAIL?: string
}

const HOP_BY_HOP = [
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'host',
]

const ALLOWED = new Set(['GET', 'POST', 'OPTIONS'])

/** Cuánto vive en memoria el JWKS del equipo. Cloudflare rota claves cada semanas, con solape. */
const JWKS_TTL_MS = 5 * 60 * 1000
/** Ante un `kid` desconocido se vuelve a pedir el JWKS, pero no más de una vez por este intervalo. */
const JWKS_MIN_REFRESH_MS = 30 * 1000
/** Tolerancia de reloj para `nbf`/`iat`. `exp` no tiene tolerancia. */
const CLOCK_SKEW_S = 60

interface Jwk {
  kid?: string
  kty?: string
  n?: string
  e?: string
}

interface JwksCache {
  fetchedAt: number
  keys: Map<string, Promise<CryptoKey>>
}

// Caché de módulo: sobrevive entre peticiones mientras el isolate de Workers siga vivo.
const jwksCache = new Map<string, JwksCache>()

/** Solo para tests: vacía el caché de claves. */
export function resetAccessKeyCache(): void {
  jwksCache.clear()
}

function forbidden(message: string): Response {
  return new Response(JSON.stringify({ error: message }), {
    status: 403,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  })
}

function base64UrlDecode(input: string): Uint8Array<ArrayBuffer> {
  const b64 = input.replace(/-/g, '+').replace(/_/g, '/')
  const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4)
  const binary = atob(padded)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

function decodeJson(part: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(new TextDecoder().decode(base64UrlDecode(part)))
    return value !== null && typeof value === 'object' && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : null
  } catch {
    return null
  }
}

/** `equipo.cloudflareaccess.com`, aunque la variable venga con `https://` o barra final. */
export function normalizeTeamDomain(value: string): string {
  return value
    .trim()
    .replace(/^https?:\/\//i, '')
    .replace(/\/+$/, '')
}

function readCookie(header: string | null, name: string): string | null {
  if (!header) return null
  for (const part of header.split(';')) {
    const eq = part.indexOf('=')
    if (eq === -1) continue
    if (part.slice(0, eq).trim() === name) return part.slice(eq + 1).trim()
  }
  return null
}

async function loadKeys(teamDomain: string): Promise<JwksCache> {
  const res = await fetch(`https://${teamDomain}/cdn-cgi/access/certs`, {
    headers: { Accept: 'application/json' },
  })
  if (!res.ok) throw new Error(`certs HTTP ${res.status}`)
  const body = (await res.json()) as { keys?: Jwk[] }
  const keys = new Map<string, Promise<CryptoKey>>()
  for (const jwk of body.keys ?? []) {
    if (jwk.kty !== 'RSA' || !jwk.kid || !jwk.n || !jwk.e) continue
    keys.set(
      jwk.kid,
      crypto.subtle.importKey(
        'jwk',
        { kty: 'RSA', n: jwk.n, e: jwk.e, alg: 'RS256', ext: true },
        { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
        false,
        ['verify'],
      ),
    )
  }
  const entry = { fetchedAt: Date.now(), keys }
  jwksCache.set(teamDomain, entry)
  return entry
}

async function keyFor(teamDomain: string, kid: string): Promise<CryptoKey | null> {
  const now = Date.now()
  let entry = jwksCache.get(teamDomain)
  if (!entry || now - entry.fetchedAt > JWKS_TTL_MS) {
    entry = await loadKeys(teamDomain)
  } else if (!entry.keys.has(kid) && now - entry.fetchedAt > JWKS_MIN_REFRESH_MS) {
    // Rotación de claves: un `kid` nuevo fuerza una relectura, con tope de frecuencia.
    entry = await loadKeys(teamDomain)
  }
  const key = entry.keys.get(kid)
  return key ? await key : null
}

export interface AccessConfig {
  teamDomain: string
  aud: string
  allowedEmail?: string
}

/**
 * Verifica un JWT de Access. Devuelve null si es válido, o el motivo (para registro, no para el
 * cliente) si no lo es.
 */
export async function verifyAccessJwt(
  token: string,
  config: AccessConfig,
  nowSeconds: number = Math.floor(Date.now() / 1000),
): Promise<string | null> {
  const parts = token.split('.')
  if (parts.length !== 3) return 'formato'
  const [rawHeader, rawPayload, rawSignature] = parts as [string, string, string]
  const header = decodeJson(rawHeader)
  const payload = decodeJson(rawPayload)
  if (!header || !payload) return 'formato'
  if (header.alg !== 'RS256') return 'alg'
  if (typeof header.kid !== 'string' || header.kid === '') return 'kid'

  let key: CryptoKey | null
  try {
    key = await keyFor(config.teamDomain, header.kid)
  } catch {
    return 'certs'
  }
  if (!key) return 'kid desconocido'

  let signature: Uint8Array<ArrayBuffer>
  try {
    signature = base64UrlDecode(rawSignature)
  } catch {
    return 'firma'
  }
  const signed = new TextEncoder().encode(`${rawHeader}.${rawPayload}`)
  const valid = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, signature, signed)
  if (!valid) return 'firma'

  if (payload.iss !== `https://${config.teamDomain}`) return 'iss'
  const aud = payload.aud
  const audiences = Array.isArray(aud) ? aud : [aud]
  if (!audiences.includes(config.aud)) return 'aud'
  if (typeof payload.exp !== 'number' || nowSeconds >= payload.exp) return 'exp'
  if (
    payload.nbf !== undefined &&
    (typeof payload.nbf !== 'number' || payload.nbf > nowSeconds + CLOCK_SKEW_S)
  ) {
    return 'nbf'
  }
  if (config.allowedEmail) {
    const email = typeof payload.email === 'string' ? payload.email.trim().toLowerCase() : ''
    if (email !== config.allowedEmail.trim().toLowerCase()) return 'email'
  }
  return null
}

function upstreamHeaders(incoming: Headers, apiToken: string): Headers {
  const out = new Headers()
  incoming.forEach((value, name) => {
    const lower = name.toLowerCase()
    if (HOP_BY_HOP.includes(lower)) return
    if (lower === 'authorization' || lower === 'cookie' || lower.startsWith('cf-access-')) return
    out.set(name, value)
  })
  out.set('Authorization', `Bearer ${apiToken}`)
  return out
}

function responseHeaders(upstream: Headers): Headers {
  const out = new Headers(upstream)
  for (const name of HOP_BY_HOP) out.delete(name)
  return out
}

/** Atiende `/api/*` y `/sync/*`. Los catch-all de `functions/` lo exportan como `onRequest`. */
export async function handleProxy(context: { request: Request; env: ProxyEnv }): Promise<Response> {
  const { request, env } = context
  if (!ALLOWED.has(request.method)) {
    return new Response('Method Not Allowed', {
      status: 405,
      headers: { Allow: 'GET, POST, OPTIONS' },
    })
  }

  const teamDomain = normalizeTeamDomain(env.ACCESS_TEAM_DOMAIN ?? '')
  const aud = (env.ACCESS_AUD ?? '').trim()
  const origin = (env.API_ORIGIN ?? '').trim()
  const apiToken = (env.API_TOKEN ?? '').trim()
  if (!teamDomain || !aud || !origin || !apiToken) {
    return forbidden('Acceso no configurado: faltan variables del proyecto de Pages (D20).')
  }

  const jwt =
    request.headers.get('Cf-Access-Jwt-Assertion') ??
    readCookie(request.headers.get('Cookie'), 'CF_Authorization')
  if (!jwt) return forbidden('Sesión no válida: falta el inicio de sesión de Cloudflare Access.')

  const allowedEmail = (env.ACCESS_ALLOWED_EMAIL ?? '').trim() || undefined
  const reason = await verifyAccessJwt(jwt, { teamDomain, aud, allowedEmail })
  if (reason !== null) {
    console.warn(`access: JWT rechazado (${reason})`)
    return forbidden(
      'Sesión no válida: el inicio de sesión de Cloudflare Access no se pudo verificar.',
    )
  }

  const incoming = new URL(request.url)
  const target = new URL(incoming.pathname + incoming.search, origin.replace(/\/+$/, ''))
  const upstream = await fetch(target, {
    method: request.method,
    headers: upstreamHeaders(request.headers, apiToken),
    body: request.method === 'GET' ? undefined : request.body,
    redirect: 'manual',
  })
  // El cuerpo se pasa como stream, sin bufferizar.
  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: responseHeaders(upstream.headers),
  })
}
