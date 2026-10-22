import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../../mocks/node'
import { renderApp } from '../../../test/render'

describe('HistorialView (P3)', () => {
  it('muestra las dosis del fixture, más recientes primero', async () => {
    renderApp('/alarmas/historial')
    const table = await screen.findByRole('table', { name: 'Historial de dosis' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Juan')
    expect(rows[0]).toHaveTextContent('Amoxicilina')
  })

  it('pasa el destinatario como param de la proyección', async () => {
    const seen: (string | null)[] = []
    server.use(
      http.get('*/api/projections/alarmas/historial_dosis', ({ request }) => {
        seen.push(new URL(request.url).searchParams.get('destinatario'))
        return HttpResponse.json([])
      }),
    )
    renderApp('/alarmas/historial')
    await screen.findByText('Todavía no hay dosis registradas')
    await userEvent.type(screen.getByLabelText('Destinatario'), 'Ana')
    await userEvent.click(screen.getByRole('button', { name: 'Filtrar' }))
    expect(await screen.findByText('Sin dosis para Ana')).toBeInTheDocument()
    expect(seen).toEqual([null, 'Ana'])
  })
})
