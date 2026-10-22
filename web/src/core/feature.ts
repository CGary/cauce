import type { ComponentType, LazyExoticComponent } from 'react'

/** Una vista = una proyección. Se carga con React.lazy para no inflar el bundle inicial. */
export interface FeatureRoute {
  path: string
  title: string
  Component: LazyExoticComponent<ComponentType>
}

/** Registro explícito de features, igual que en Android (sin descubrimiento automático). */
export interface FeatureModule {
  id: string
  title: string
  routes: FeatureRoute[]
}
