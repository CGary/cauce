import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { shouldRetry } from './core/api'
import { buildRoutes } from './core/routes'
import { FEATURES } from './features'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 60_000, refetchOnWindowFocus: true, retry: shouldRetry },
  },
})

const router = createBrowserRouter(buildRoutes(FEATURES))

async function enableMocks() {
  // Solo en `vite --mode mock`: en el build normal la condición es falsa y MSW no entra al bundle.
  if (import.meta.env.VITE_MSW === 'true') {
    const { worker } = await import('./mocks/browser')
    await worker.start({ onUnhandledRequest: 'bypass' })
  }
}

void enableMocks().then(() => {
  const root = document.getElementById('root')
  if (!root) throw new Error('Falta #root en index.html')
  createRoot(root).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </StrictMode>,
  )
})
