import type { ReactNode } from 'react'

export function SectionHeader({
  title,
  description,
  children,
}: {
  title: string
  description?: string
  children?: ReactNode
}) {
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end sm:justify-between">
      <div className="flex flex-col gap-0.5">
        <h2 className="text-heading font-semibold">{title}</h2>
        {description && <p className="max-w-prose text-caption text-muted">{description}</p>}
      </div>
      {children}
    </div>
  )
}
