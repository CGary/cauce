// Proxy de mismo origen hacia la API (Cloudflare Pages Function).
// Atiende /api/* y (vía functions/sync/[[path]].ts) /sync/*. El destino real
// es la variable de entorno API_ORIGIN del proyecto de Pages, nunca el repo.

interface Env {
  API_ORIGIN?: string
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

function strip(headers: Headers): Headers {
  const out = new Headers(headers)
  for (const name of HOP_BY_HOP) out.delete(name)
  return out
}

export const onRequest = async (context: { request: Request; env: Env }): Promise<Response> => {
  const { request, env } = context
  if (!ALLOWED.has(request.method)) {
    return new Response('Method Not Allowed', { status: 405, headers: { Allow: 'GET, POST, OPTIONS' } })
  }
  if (!env.API_ORIGIN) {
    return new Response('API_ORIGIN no está configurado', { status: 500 })
  }
  const incoming = new URL(request.url)
  const target = new URL(incoming.pathname + incoming.search, env.API_ORIGIN.replace(/\/+$/, ''))
  const upstream = await fetch(target, {
    method: request.method,
    headers: strip(request.headers),
    body: request.method === 'GET' ? undefined : request.body,
    redirect: 'manual',
  })
  // El cuerpo se pasa como stream, sin bufferizar.
  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: strip(upstream.headers),
  })
}
