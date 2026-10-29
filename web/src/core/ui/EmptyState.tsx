import type { ReactNode } from 'react'

/** Vacío amable: dice qué pasa y, si hay, ofrece el siguiente paso. */
export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description?: string
  action?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center gap-2 rounded-card border border-dashed border-line-strong px-4 py-10 text-center">
      <svg
        aria-hidden="true"
        viewBox="0 0 48 48"
        className="size-12 text-primary"
        fill="none"
        stroke="currentColor"
        strokeWidth="2.5"
        strokeLinecap="round"
      >
        <path d="M6 18c5-5 8-5 12 0s7 5 12 0 7-4 12-1" />
        <path d="M6 30c5-5 8-5 12 0s7 5 12 0 7-4 12-1" opacity="0.55" />
      </svg>
      <p className="text-heading font-semibold">{title}</p>
      {description && <p className="max-w-prose text-muted">{description}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  )
}
