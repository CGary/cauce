import type { ReactNode } from 'react'

export function Page({
  title,
  description,
  actions,
  children,
}: {
  title: string
  description?: string
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <main
      id="contenido"
      tabIndex={-1}
      className="mx-auto flex w-full max-w-page flex-col gap-6 px-4 py-6 outline-none sm:px-6 sm:py-10"
    >
      <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
        <div className="flex flex-col gap-1">
          <h1 className="text-title font-semibold tracking-tight text-balance">{title}</h1>
          {description && <p className="max-w-prose text-muted">{description}</p>}
        </div>
        {actions}
      </div>
      {children}
    </main>
  )
}
