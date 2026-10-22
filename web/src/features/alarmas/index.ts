import { lazy } from 'react'
import type { FeatureModule } from '../../core/feature'

export const alarmas: FeatureModule = {
  id: 'alarmas',
  title: 'Alarmas',
  routes: [
    {
      path: 'adherencia',
      title: 'Adherencia',
      Component: lazy(() => import('./views/AdherenciaView')),
    },
    {
      path: 'historial',
      title: 'Historial',
      Component: lazy(() => import('./views/HistorialView')),
    },
  ],
}
