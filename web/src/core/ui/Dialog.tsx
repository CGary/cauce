import { useEffect, useId, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * Diálogo modal accesible: foco atrapado, Escape cierra, el foco vuelve al disparador.
 * Se monta solo mientras está abierto (el padre lo renderiza condicionalmente).
 * El elemento con `data-autofocus` recibe el foco inicial; si no hay, el primer enfocable.
 */
export function Dialog({
  title,
  description,
  onClose,
  children,
}: {
  title: string
  description?: string
  onClose: () => void
  children: ReactNode
}) {
  const ref = useRef<HTMLDivElement>(null)
  const onCloseRef = useRef(onClose)
  const titleId = useId()
  const descId = useId()

  useEffect(() => {
    onCloseRef.current = onClose
  })

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const root = ref.current
    if (!root) return
    const first =
      root.querySelector<HTMLElement>('[data-autofocus]') ??
      root.querySelector<HTMLElement>(FOCUSABLE)
    ;(first ?? root).focus()
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab' || !root) return
      const items = [...root.querySelectorAll<HTMLElement>(FOCUSABLE)]
      if (items.length === 0) {
        event.preventDefault()
        return
      }
      const head = items[0]
      const tail = items[items.length - 1]
      const active = document.activeElement
      if (event.shiftKey && (active === head || active === root)) {
        event.preventDefault()
        tail?.focus()
      } else if (!event.shiftKey && active === tail) {
        event.preventDefault()
        head?.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = overflow
      if (previous?.isConnected) previous.focus()
    }
  }, [])

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-scrim p-0 sm:items-center sm:p-4">
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descId : undefined}
        tabIndex={-1}
        className="flex max-h-[92dvh] w-full max-w-xl flex-col overflow-hidden rounded-t-card bg-surface text-ink shadow-overlay sm:rounded-card"
      >
        <div className="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
          <div className="flex flex-col gap-1">
            <h2 id={titleId} className="text-heading font-semibold">
              {title}
            </h2>
            {description && (
              <p id={descId} className="text-caption text-muted">
                {description}
              </p>
            )}
          </div>
          <button
            type="button"
            aria-label="Cerrar diálogo"
            onClick={onClose}
            className="-mr-2 -mt-1 inline-flex size-11 flex-none cursor-pointer items-center justify-center rounded-control text-muted hover:bg-surface-2"
          >
            <span aria-hidden="true" className="text-heading leading-none">
              ×
            </span>
          </button>
        </div>
        <div className="overflow-y-auto px-5 py-4">{children}</div>
      </div>
    </div>,
    document.body,
  )
}
