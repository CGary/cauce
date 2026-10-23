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

/** Reintenta solo errores de red o 5xx; un 4xx no se arregla reintentando. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false
  return failureCount < 2
}
