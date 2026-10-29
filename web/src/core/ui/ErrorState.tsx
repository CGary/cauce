import { ApiError } from '../api'
import { Button } from './Button'

/** Error de carga en español llano; el detalle técnico queda como dato secundario. */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const status = error instanceof ApiError ? error.status : undefined
  const detail =
    status === 0
      ? 'Sin respuesta del servidor'
      : status !== undefined
        ? `HTTP ${status}`
        : 'Error inesperado'
  const cause =
    status === 0
      ? 'No hay conexión con el servidor. Revisá la red y probá de nuevo.'
      : 'El servidor no pudo entregar los datos. Tus alarmas y dosis no se perdieron.'
  return (
    <div
      role="alert"
      className="flex flex-wrap items-center justify-between gap-4 rounded-card border border-danger bg-danger-soft px-4 py-4 text-danger-ink"
    >
      <div className="flex flex-col gap-1">
        <p className="text-heading font-semibold">No se pudo cargar</p>
        <p>{cause}</p>
        <p className="text-caption">
          Detalle técnico: <span className="font-mono">{detail}</span>
        </p>
      </div>
      <Button variant="danger" onClick={onRetry}>
        Reintentar
      </Button>
    </div>
  )
}
