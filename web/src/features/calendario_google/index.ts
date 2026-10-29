import { lazy } from 'react'
import type { FeatureModule } from '../../core/feature'

export const calendarioGoogle: FeatureModule = {
  id: 'calendario_google',
  title: 'Reuniones',
  routes: [
    {
      path: 'proximas',
      title: 'Próximas',
      Component: lazy(() => import('./views/ReunionesView')),
    },
  ],
}
