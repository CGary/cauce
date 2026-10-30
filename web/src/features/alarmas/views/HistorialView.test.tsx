import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../../mocks/node'
import { renderApp } from '../../../test/render'

function dosis(n: number) {
  // Una dosis por hora hacia atrás desde el 25/10 12:00 UTC (más recientes primero).
  return Array.from({ length: n }, (_, i) => ({
    ts: new Date(Date.UTC(2026, 9, 25, 12) - i * 3_600_000).toISOString(),
    destinatario: 'Juan',
    remedio: 'Amoxicilina',
  }))
}

describe('HistorialView (P3)', () => {
  it('muestra las dosis del fixture, más recientes primero', async () => {
    renderApp('/alarmas/historial')
    const list = (await screen.findAllByRole('listitem'))[0] as HTMLElement
    expect(list).toHaveTextContent('Juan')
    expect(list).toHaveTextContent('Amoxicilina')
    expect(document.title).toBe('Historial de dosis · Cauce')
  })

  it('el destinatario es un select con las opciones de adherencia y filtra al cambiar', async () => {
    const seen: (string | null)[] = []
    server.use(
      http.get('*/api/projections/alarmas/historial_dosis', ({ request }) => {
        seen.push(new URL(request.url).searchParams.get('destinatario'))
        return HttpResponse.json([])
      }),
    )
    renderApp('/alarmas/historial')
    await screen.findByText('Todavía no hay dosis registradas')
    expect(screen.queryByRole('button', { name: 'Filtrar' })).toBeNull()
    const select = screen.getByLabelText('Destinatario')
    await within(select).findByRole('option', { name: 'Juan' })
    expect(within(select).getByRole('option', { name: 'Todos' })).toBeInTheDocument()
    await userEvent.selectOptions(select, 'Juan')
    expect(await screen.findByText('Sin dosis para Juan')).toBeInTheDocument()
    expect(seen).toEqual([null, 'Juan'])
  })

  it('agrupa por día y carga de a 50', async () => {
    server.use(
      http.get('*/api/projections/alarmas/historial_dosis', () => HttpResponse.json(dosis(60))),
    )
    renderApp('/alarmas/historial')
    await screen.findAllByRole('listitem')
    expect(screen.getAllByRole('listitem')).toHaveLength(50)
    expect(screen.getAllByRole('heading', { level: 3 }).length).toBeGreaterThanOrEqual(2)
    await userEvent.click(screen.getByRole('button', { name: /Cargar más/ }))
    expect(screen.getAllByRole('listitem')).toHaveLength(60)
    expect(screen.queryByRole('button', { name: /Cargar más/ })).toBeNull()
  })
})
