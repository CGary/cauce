import { ApiError } from '../api'

export function ErrorState({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const status = error instanceof ApiError ? error.status : undefined
  const detail =
    status === 0
      ? 'Sin respuesta del servidor'
      : status !== undefined
        ? `HTTP ${status}`
        : 'Error inesperado'
  return (
    <div
      role="alert"
      className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-red-900"
    >
      <div>
        <p className="font-medium">No se pudo cargar</p>
        <p className="text-sm">{detail}</p>
      </div>
      <button
        type="button"
        onClick={onRetry}
        className="rounded-md bg-red-700 px-3 py-1.5 text-sm font-medium text-white hover:bg-red-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-700"
      >
        Reintentar
      </button>
    </div>
  )
}
