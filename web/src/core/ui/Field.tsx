import { useId, type ReactNode } from 'react'

export const inputClass =
  'min-h-11 w-full rounded-control border border-line-strong bg-surface px-3 py-2 text-body text-ink placeholder:text-muted/80 disabled:opacity-50 aria-[invalid=true]:border-danger aria-[invalid=true]:border-2'

export interface FieldControlProps {
  id: string
  'aria-describedby'?: string
  'aria-invalid'?: true
}

/** Etiqueta + control + ayuda + error en línea, enlazados por id (no depende del color). */
export function Field({
  label,
  hint,
  error,
  className = '',
  children,
}: {
  label: string
  hint?: string
  error?: string | null
  className?: string
  children: (control: FieldControlProps) => ReactNode
}) {
  const id = useId()
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ')
  return (
    <div className={`flex min-w-0 flex-col gap-1.5 ${className}`}>
      <label htmlFor={id} className="text-caption font-medium text-ink">
        {label}
      </label>
      {children({
        id,
        'aria-describedby': describedBy || undefined,
        'aria-invalid': error ? true : undefined,
      })}
      {hint && (
        <p id={hintId} className="text-caption text-muted">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="flex gap-1.5 text-caption font-medium text-danger-ink">
          <span aria-hidden="true">⚠</span>
          {error}
        </p>
      )}
    </div>
  )
}
