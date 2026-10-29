import type { ButtonHTMLAttributes, ReactNode } from 'react'

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost'

const BASE =
  'inline-flex min-h-11 min-w-11 cursor-pointer items-center justify-center gap-2 rounded-control px-4 py-2 text-caption font-semibold transition-colors select-none disabled:cursor-not-allowed disabled:opacity-50 sm:text-body'

const VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-primary text-on-primary hover:bg-primary-hover',
  secondary: 'border border-line-strong bg-surface text-ink hover:bg-surface-2',
  danger: 'bg-danger text-on-danger hover:bg-danger-hover',
  ghost: 'text-primary-ink hover:bg-primary-soft',
}

/** Clase compartida para botones y para etiquetas que se comportan como botón (importar archivo). */
export function buttonClass(variant: ButtonVariant = 'secondary', extra = ''): string {
  return `${BASE} ${VARIANTS[variant]} ${extra}`.trim()
}

export function Button({
  variant = 'secondary',
  pending = false,
  className = '',
  children,
  disabled,
  type = 'button',
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant
  /** Operación en curso: deshabilita el botón y muestra un indicador. */
  pending?: boolean
  children: ReactNode
}) {
  return (
    <button
      type={type}
      className={buttonClass(variant, className)}
      disabled={disabled || pending}
      aria-busy={pending || undefined}
      {...rest}
    >
      {pending && (
        <span
          aria-hidden="true"
          className="size-4 animate-spin rounded-full border-2 border-current border-t-transparent"
        />
      )}
      {children}
    </button>
  )
}
