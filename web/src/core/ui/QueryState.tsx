import type { UseQueryResult } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { EmptyState } from './EmptyState'
import { ErrorState } from './ErrorState'
import { Spinner } from './Spinner'

/** Carga, error y vacío iguales en todas las vistas; `children` solo recibe datos no vacíos. */
export function QueryState<T>({
  query,
  isEmpty,
  empty,
  children,
}: {
  query: UseQueryResult<T>
  isEmpty: (data: T) => boolean
  empty: { title: string; description?: string }
  children: (data: T) => ReactNode
}) {
  if (query.isPending) return <Spinner />
  if (query.isError) return <ErrorState error={query.error} onRetry={() => void query.refetch()} />
  if (isEmpty(query.data)) return <EmptyState {...empty} />
  return <>{children(query.data)}</>
}
