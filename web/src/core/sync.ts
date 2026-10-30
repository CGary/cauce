import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { ApiError } from './api'

/** Reloj que se actualiza cada `intervalMs`; solo para mostrar «hace X min». */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), intervalMs)
    return () => window.clearInterval(id)
  }, [intervalMs])
  return now
}

/** «hace unos segundos», «hace 5 min», «hace 2 h», «hace 3 d». Solo presentación. */
export function formatAgo(timestamp: number, now: number): string {
  const seconds = Math.max(0, Math.round((now - timestamp) / 1000))
  if (seconds < 45) return 'hace unos segundos'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `hace ${minutes} min`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `hace ${hours} h`
  return `hace ${Math.round(hours / 24)} d`
}

export interface SyncStatus {
  /** Última lectura exitosa de cualquier proyección (ms epoch), o 0 si no hay. */
  lastFetch: number
  /** Alguna lectura falló sin respuesta del servidor, o el navegador está sin red. */
  offline: boolean
}

/** Resume el estado de las consultas del cliente: última lectura exitosa y si hay conexión. */
export function useSyncStatus(): SyncStatus {
  const client = useQueryClient()
  const [, setTick] = useState(0)
  useEffect(() => {
    const cache = client.getQueryCache()
    // Diferido: el caché notifica mientras otro componente renderiza.
    const bump = () => queueMicrotask(() => setTick((n) => n + 1))
    const unsubscribe = cache.subscribe(bump)
    window.addEventListener('online', bump)
    window.addEventListener('offline', bump)
    return () => {
      unsubscribe()
      window.removeEventListener('online', bump)
      window.removeEventListener('offline', bump)
    }
  }, [client])
  const queries = client.getQueryCache().getAll()
  let lastFetch = 0
  let failed = false
  for (const query of queries) {
    lastFetch = Math.max(lastFetch, query.state.dataUpdatedAt)
    const error = query.state.error
    if (
      query.state.status === 'error' &&
      (error instanceof TypeError || (error instanceof ApiError && error.status === 0))
    ) {
      failed = true
    }
  }
  return { lastFetch, offline: failed || !navigator.onLine }
}
