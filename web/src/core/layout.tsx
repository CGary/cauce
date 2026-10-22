import { Suspense } from 'react'
import { NavLink, Outlet } from 'react-router'
import type { FeatureModule } from './feature'
import { Spinner } from './ui'

/** Navegación construida desde el registro; core/ recibe los features, nunca los importa. */
export function Layout({ features }: { features: FeatureModule[] }) {
  return (
    <div className="min-h-dvh">
      <header className="border-b border-stone-200 bg-white">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-8 gap-y-2 px-4 py-3">
          <span className="font-semibold tracking-tight">Cauce · admin</span>
          <nav aria-label="Principal" className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
            {features.map((feature) => (
              <div key={feature.id} className="flex items-center gap-3">
                <span className="text-stone-500">{feature.title}</span>
                {feature.routes.map((route) => (
                  <NavLink
                    key={route.path}
                    to={`/${feature.id}/${route.path}`}
                    className={({ isActive }) =>
                      `rounded-md px-2 py-1 ${isActive ? 'bg-teal-50 font-medium text-teal-800' : 'text-stone-700 hover:bg-stone-100'}`
                    }
                  >
                    {route.title}
                  </NavLink>
                ))}
              </div>
            ))}
          </nav>
        </div>
      </header>
      <Suspense
        fallback={
          <div className="mx-auto max-w-5xl px-4">
            <Spinner />
          </div>
        }
      >
        <Outlet />
      </Suspense>
    </div>
  )
}
