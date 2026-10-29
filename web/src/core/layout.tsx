import { Suspense } from 'react'
import { NavLink, Outlet } from 'react-router'
import type { FeatureModule } from './feature'
import { Spinner } from './ui'

function Brand() {
  return (
    <div className="flex items-center gap-3">
      <svg
        aria-hidden="true"
        viewBox="0 0 32 32"
        className="size-9 flex-none rounded-control bg-primary"
        fill="none"
        strokeWidth="2.4"
        strokeLinecap="round"
      >
        <path d="M5 12c3-3 5-3 8 0s5 3 8 0 4-2 6-1" stroke="var(--on-primary)" />
        <path d="M5 19c3-3 5-3 8 0s5 3 8 0 4-2 6-1" stroke="var(--on-primary)" opacity="0.6" />
      </svg>
      <div className="flex flex-col leading-tight">
        <span className="text-heading font-semibold tracking-tight">Cauce</span>
        <span className="text-caption text-muted">Consola de administración</span>
      </div>
    </div>
  )
}

/** Navegación construida desde el registro; core/ recibe los features, nunca los importa. */
export function Layout({ features }: { features: FeatureModule[] }) {
  return (
    <div className="min-h-dvh">
      <a
        href="#contenido"
        className="sr-only rounded-control bg-primary px-4 py-3 font-semibold text-on-primary focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50"
      >
        Saltar al contenido
      </a>
      <header className="border-b border-line bg-surface">
        <div className="mx-auto flex max-w-page flex-col gap-3 px-4 pt-3 sm:px-6 lg:flex-row lg:items-center lg:gap-10 lg:py-3">
          <Brand />
          <nav
            aria-label="Principal"
            className="-mx-4 flex gap-6 overflow-x-auto px-4 pb-2 sm:-mx-6 sm:px-6 lg:mx-0 lg:p-0"
          >
            {features.map((feature) => (
              <div key={feature.id} className="flex flex-none items-center gap-1">
                <span className="mr-2 text-caption font-semibold uppercase tracking-wide text-muted">
                  {feature.title}
                </span>
                {feature.routes.map((route) => (
                  <NavLink
                    key={route.path}
                    to={`/${feature.id}/${route.path}`}
                    className={({ isActive }) =>
                      `inline-flex min-h-11 items-center rounded-control px-3 text-caption font-medium whitespace-nowrap sm:text-body ${
                        isActive
                          ? 'bg-primary-soft font-semibold text-primary-ink underline decoration-2 underline-offset-8'
                          : 'text-ink hover:bg-surface-2'
                      }`
                    }
                  >
                    {route.title}
                  </NavLink>
                ))}
              </div>
            ))}
          </nav>
        </div>
        <div
          aria-hidden="true"
          className="h-1 bg-linear-to-r from-primary via-info to-primary opacity-60"
        />
      </header>
      <Suspense
        fallback={
          <div className="mx-auto max-w-page px-4 sm:px-6">
            <Spinner />
          </div>
        }
      >
        <Outlet />
      </Suspense>
    </div>
  )
}
