import { queryOptions } from '@tanstack/react-query'

/** Error HTTP de la API; `status` 0 = no hubo respuesta (red caída, CORS, Access). */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export type ProjectionParams = Record<string, string | undefined>

export const API_BASE: string = import.meta.env.VITE_API_BASE ?? ''

export function projectionUrl(feature: string, name: string, params: ProjectionParams = {}): URL {
  const url = new URL(`${API_BASE}/api/projections/${feature}/${name}`, window.location.origin)
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') url.searchParams.set(key, value)
  }
  return url
}

/** GET tipado a /api/projections/{feature}/{name}. El servidor calcula la proyección; la web solo la muestra. */
export async function fetchProjection<T>(
  feature: string,
  name: string,
  params: ProjectionParams = {},
  signal?: AbortSignal,
): Promise<T> {
  let res: Response
  try {
    // credentials: el login de Cloudflare Access viaja como cookie aunque la API esté en otro origen.
    res = await fetch(projectionUrl(feature, name, params), { credentials: 'include', signal })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new ApiError(0, 'Sin respuesta del servidor')
  }
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status} ${res.statusText}`.trim())
  return (await res.json()) as T
}

export function projectionQuery<T>(feature: string, name: string, params: ProjectionParams = {}) {
  return queryOptions({
    queryKey: ['projection', feature, name, params],
    queryFn: ({ signal }) => fetchProjection<T>(feature, name, params, signal),
  })
}

/**
 * Reintentos de lectura: máximo 2. Un 4xx no se arregla reintentando; un fallo de red o CORS
 * (status 0, o un TypeError crudo de fetch) se reintenta una sola vez, porque repetirlo no lo arregla.
 */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false
  if (error instanceof TypeError || (error instanceof ApiError && error.status === 0)) {
    return failureCount < 1
  }
  return failureCount < 2
}

/** Espera exponencial entre reintentos: 1 s, 2 s, 4 s… con tope de 8 s. */
export function retryDelay(attempt: number): number {
  return Math.min(1000 * 2 ** attempt, 8000)
}

export interface ErrorCause {
  /** Causa corta en español llano. */
  cause: string
  /** Dato técnico para el panel plegado. */
  detail: string
}

/** Traduce el error de una consulta a una causa corta y un detalle técnico. */
export function describeError(error: unknown, online: boolean = navigator.onLine): ErrorCause {
  if (error instanceof ApiError) {
    const { status } = error
    if (status === 0) {
      return {
        cause: online ? 'sin conexión con la API' : 'sin conexión a internet',
        detail: 'Sin respuesta del servidor (red caída, CORS o sesión de Access bloqueada)',
      }
    }
    const detail = `HTTP ${status}`
    if (status === 401 || status === 403) {
      return { cause: `la API rechazó el acceso (${status}); iniciá sesión de nuevo`, detail }
    }
    if (status === 404) return { cause: 'la API no encontró esa proyección (404)', detail }
    return { cause: `la API respondió ${status}`, detail }
  }
  if (error instanceof TypeError) {
    return {
      cause: online ? 'sin conexión con la API' : 'sin conexión a internet',
      detail: error.message || 'TypeError',
    }
  }
  return { cause: 'error inesperado', detail: error instanceof Error ? error.message : 'Error' }
}
