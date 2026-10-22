import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { buildRoutes } from '../core/routes'
import type { FeatureModule } from '../core/feature'
import { FEATURES } from '../features'

/** Monta la app real (rutas del registro) en una URL dada, con un QueryClient sin reintentos. */
export function renderApp(url: string, features: FeatureModule[] = FEATURES) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter(buildRoutes(features), { initialEntries: [url] })
  return {
    router,
    ...render(
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    ),
  }
}
