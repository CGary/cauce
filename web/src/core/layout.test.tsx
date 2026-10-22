import { screen } from '@testing-library/react'
import { lazy } from 'react'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../test/render'
import type { FeatureModule } from './feature'

const fake: FeatureModule[] = [
  {
    id: 'demo',
    title: 'Demo',
    routes: [
      {
        path: 'uno',
        title: 'Uno',
        Component: lazy(async () => ({ default: () => <p>vista uno</p> })),
      },
      {
        path: 'dos',
        title: 'Dos',
        Component: lazy(async () => ({ default: () => <p>vista dos</p> })),
      },
    ],
  },
]

describe('Layout y rutas', () => {
  it('construye la navegación desde el registro y redirige / a la primera vista', async () => {
    renderApp('/', fake)
    expect(await screen.findByText('vista uno')).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Principal' })
    expect(nav).toHaveTextContent('Demo')
    expect(screen.getByRole('link', { name: 'Uno' })).toHaveAttribute('href', '/demo/uno')
    expect(screen.getByRole('link', { name: 'Dos' })).toHaveAttribute('href', '/demo/dos')
  })

  it('muestra un estado vacío en rutas desconocidas', async () => {
    renderApp('/nada', fake)
    expect(await screen.findByText('Esta página no existe')).toBeInTheDocument()
  })
})
