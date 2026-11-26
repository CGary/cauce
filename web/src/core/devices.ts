import { queryOptions, skipToken, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { API_BASE, ApiError } from './api'

/** Dispositivos que no son el teléfono: la web y la CLI solo escriben eventos. */
const NOT_PHONE = new Set(['web', 'cli'])

export interface DeviceSync {
  device_id: string
  last_push_at: string | null
  last_pull_at: string | null
  pulled_through_seq: number | null
}

/** Respuesta de `GET /sync/devices` (contrato D22). */
export interface DevicesResponse {
  now: string
  server_seq: number
  devices: DeviceSync[]
}

export const DEVICES_KEY = ['sync', 'devices'] as const
export const PENDING_KEY = ['sync', 'pending'] as const

/** Un cambio empujado desde la web: espera a que algún teléfono lo baje. */
export interface PendingPush {
  /** Mayor `server_seq` devuelto por un push de esta sesión. */
  serverSeq: number
  /** Cuándo (ms epoch) se hizo el último push; fija el ritmo del sondeo. */
  pushedAt: number
  /** ISO del pull del teléfono que lo trajo; ausente mientras siga pendiente. */
  receivedAt?: string
}

export async function fetchDevices(signal?: AbortSignal): Promise<DevicesResponse> {
  let res: Response
  try {
    res = await fetch(`${API_BASE}/sync/devices`, { credentials: 'include', signal })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new ApiError(0, 'Sin respuesta del servidor')
  }
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status} ${res.statusText}`.trim())
  return (await res.json()) as DevicesResponse
}

export function phones(data: DevicesResponse | undefined): DeviceSync[] {
  return (data?.devices ?? []).filter((d) => !NOT_PHONE.has(d.device_id))
}

/** Un teléfono ya bajó todo hasta `serverSeq`; devuelve el instante de su último pull. */
export function receivedBy(data: DevicesResponse | undefined, serverSeq: number): string | null {
  for (const d of phones(data)) {
    if (d.pulled_through_seq !== null && d.pulled_through_seq >= serverSeq) {
      return d.last_pull_at ?? data?.now ?? null
    }
  }
  return null
}

/** Estado del push pendiente, guardado en el caché de consultas; undefined si no hay ninguno. */
export function usePending(): PendingPush | undefined {
  return useQuery({
    queryKey: PENDING_KEY,
    queryFn: skipToken,
    staleTime: Infinity,
    gcTime: Infinity,
  }).data as PendingPush | undefined
}

const FAST_MS = 5_000
const SLOW_MS = 30_000
const IDLE_MS = 60_000

/**
 * Estado de sincronización por dispositivo. Mientras hay un push sin confirmar sondea cada 5 s
 * (2 min) y luego cada 30 s; sin push pendiente, cada minuto.
 */
export function useDevices() {
  const pending = usePending()
  const waiting = pending !== undefined && pending.receivedAt === undefined
  return useQuery({
    ...queryOptions({
      queryKey: DEVICES_KEY,
      queryFn: ({ signal }) => fetchDevices(signal),
    }),
    // Si el servidor aún no tiene /sync/devices (4xx) no tiene sentido reintentar.
    retry: (count, error) => !(error instanceof ApiError && error.status >= 400) && count < 1,
    refetchInterval: () => {
      if (!waiting) return IDLE_MS
      return Date.now() - pending.pushedAt < 2 * 60_000 ? FAST_MS : SLOW_MS
    },
  })
}

/** Marca como recibido el push pendiente cuando un teléfono ya lo bajó. */
export function usePendingResolver(devices: DevicesResponse | undefined) {
  const client = useQueryClient()
  const pending = usePending()
  useEffect(() => {
    if (!pending || pending.receivedAt !== undefined) return
    const at = receivedBy(devices, pending.serverSeq)
    if (at !== null) client.setQueryData<PendingPush>(PENDING_KEY, { ...pending, receivedAt: at })
  }, [client, devices, pending])
}
