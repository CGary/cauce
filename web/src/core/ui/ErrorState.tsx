import { describeError } from '../api'
import { Button } from './Button'

/** Error de carga: causa corta arriba, detalle técnico plegado. Tono suave, sin rojo pleno. */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const { cause, detail } = describeError(error)
  return (
    <div
      role="alert"
      className="flex flex-wrap items-center justify-between gap-4 rounded-card border border-danger/50 bg-danger-soft px-4 py-4 text-danger-ink"
    >
      <div className="flex flex-col gap-1">
        <p className="text-heading font-semibold">No se pudo cargar</p>
        <p>Causa: {cause}. Tus alarmas y dosis no se perdieron.</p>
        <details className="text-caption">
          <summary className="cursor-pointer">Detalle técnico</summary>
          <span className="font-mono">{detail}</span>
        </details>
      </div>
      <Button
        variant="ghost"
        className="border border-danger text-danger-ink! hover:bg-danger-soft!"
        onClick={onRetry}
      >
        Reintentar
      </Button>
    </div>
  )
}
