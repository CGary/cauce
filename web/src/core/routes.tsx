import { Link, Navigate, type RouteObject } from 'react-router'
import type { FeatureModule } from './feature'
import { Layout } from './layout'
import { buttonClass, EmptyState, Page } from './ui'

/** Una ruta por vista de proyección: /{feature}/{vista}. */
export function buildRoutes(features: FeatureModule[]): RouteObject[] {
  const first = features[0]?.routes[0]
  return [
    {
      path: '/',
      element: <Layout features={features} />,
      children: [
        {
          index: true,
          element:
            first && features[0] ? (
              <Navigate to={`/${features[0].id}/${first.path}`} replace />
            ) : null,
        },
        ...features.flatMap((feature) =>
          feature.routes.map((route) => ({
            path: `${feature.id}/${route.path}`,
            element: <route.Component />,
          })),
        ),
        {
          path: '*',
          element: (
            <Page title="No encontrado">
              <EmptyState
                title="Esta página no existe"
                description="Elegí una vista en la navegación o volvé al inicio."
                action={
                  <Link to="/" className={buttonClass('primary')}>
                    Ir al inicio
                  </Link>
                }
              />
            </Page>
          ),
        },
      ],
    },
  ]
}
