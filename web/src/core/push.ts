import { useMutation, useQueryClient } from '@tanstack/react-query'
import { API_BASE } from './api'

/** Error de validación del servidor: el mensaje exacto del 400 de /sync/push. */
export class PushError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'PushError'
    this.status = status
  }
}

/** UUID v7 RFC 9562 con crypto.getRandomValues y Date.now. Monótono dentro del mismo milisegundo. */
let lastTimestamp = 0
let lastBytes: Uint8Array | null = null

export function uuidv7(): string {
  const timestamp = Date.now()
  let bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  if (timestamp === lastTimestamp && lastBytes !== null) {
    // Mismo milisegundo: incrementa el contador del final (bytes 8..15) para que
    // dos ids seguidos conserven el orden de creación.
    bytes = lastBytes.slice()
    const view = new DataView(bytes.buffer)
    for (let i = 15; i >= 8; i--) {
      const next = (view.getUint8(i) + 1) & 0xff
      view.setUint8(i, next)
      if (next !== 0) break
    }
  }
  lastTimestamp = timestamp
  lastBytes = bytes
  const ts = BigInt(timestamp)
  const view = new DataView(bytes.buffer)
  for (let i = 0; i < 6; i++) {
    view.setUint8(i, Number((ts >> BigInt(8 * (5 - i))) & 0xffn))
  }
  view.setUint8(6, 0x70 | (view.getUint8(6) & 0x0f))
  view.setUint8(8, 0x80 | (view.getUint8(8) & 0x3f))
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0'))
  return [
    hex.slice(0, 4).join(''),
    hex.slice(4, 6).join(''),
    hex.slice(6, 8).join(''),
    hex.slice(8, 10).join(''),
    hex.slice(10, 16).join(''),
  ].join('-')
}

/** user_id del sobre. Un solo usuario: el default es el mismo que usan los fixtures (`u1`). */
const USER_ID: string = (import.meta.env as { VITE_USER_ID?: string }).VITE_USER_ID ?? 'u1'

export interface BuildEventOptions {
  /** Sobrescribe la marca de tiempo (RFC 3339 UTC, p. `new Date().toISOString()`); Default: reloj del navegador. */
  ts?: string
  /** Sobrescribe user_id; default `u1` (o `VITE_USER_ID`). */
  userId?: string
  /** Versión del tipo de evento; default 1. */
  v?: number
}

export type EventEnvelope = {
  id: string
  user_id: string
  device_id: string
  type: string
  v: number
  ts: string
  payload: Record<string, unknown>
}

/** Arma un sobre completo según schema/envelope.v1.json, con device_id "web". */
export function buildEvent(
  type: string,
  payload: Record<string, unknown>,
  opts: BuildEventOptions = {},
): EventEnvelope {
  return {
    id: uuidv7(),
    // El default "u1" debe coincidir con CAUCE_USER_ID del servidor; si no, /sync/push responde 400.
    user_id: opts.userId ?? USER_ID,
    device_id: 'web',
    type,
    v: opts.v ?? 1,
    ts: opts.ts ?? new Date().toISOString(),
    payload,
  }
}

/** Cuerpo de `POST /sync/push`: los sobres completos, el servidor los valida contra el contrato. */
export async function pushEvents(events: EventEnvelope[]): Promise<{ accepted: number; duplicates: number }> {
  let res: Response
  try {
    res = await fetch(`${API_BASE}/sync/push`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ events }),
    })
  } catch {
    throw new PushError(0, 'Sin respuesta del servidor')
  }
  if (res.ok) {
    return (await res.json()) as { accepted: number; duplicates: number }
  }
  if (res.status !== 400) {
    throw new PushError(res.status, `HTTP ${res.status} ${res.statusText}`.trim())
  }
  // El handler de /sync/push responde 400 con {"error": "..."}.
  let message = `HTTP 400 ${res.statusText}`.trim()
  try {
    const body = (await res.json()) as { error?: string }
    if (typeof body.error === 'string' && body.error !== '') message = body.error
  } catch {
    // Respuesta 400 no JSON: conserva el mensaje por defecto.
  }
  throw new PushError(400, message)
}

/**
 * Reintenta solo fallos transitorios: un `PushError` 4xx (validación del servidor:
 * user_id, payload o sobre inválido) no se arregla reintentando, así que la
 * petición va una sola vez. Red caída (status 0) y 5xx sí se reintentan, 2 veces.
 */
export function shouldRetryPush(failureCount: number, error: unknown): boolean {
  if (error instanceof PushError && error.status >= 400 && error.status < 500) return false
  return failureCount < 2
}

/**
 * Mutation de push. Al confirmar el servidor invalida las queries de proyección
 * (`['projection', ...]`, las claves de `projectionQuery` en api.ts) para que las
 * vistas relean lo calculado server-side; la web no reimplementa proyecciones.
 */
export function usePushEvents() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: pushEvents,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['projection'] })
    },
    retry: shouldRetryPush,
  })
}
