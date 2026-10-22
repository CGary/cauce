import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../../mocks/node'
import { renderApp } from '../../../test/render'

const URL_P2 = '*/api/projections/alarmas/adherencia_tratamiento'

describe('AdherenciaView (P2)', () => {
  it('muestra la fila del fixture con sus contadores', async () => {
    renderApp('/alarmas/adherencia')
    const table = await screen.findByRole('table', { name: 'Adherencia por tratamiento' })
    const row = within(table).getByRole('row', { name: /Juan/ })
    expect(row).toHaveTextContent('Amoxicilina')
    expect(row).toHaveTextContent('15') // dosis_esperadas
    expect(row).toHaveTextContent('13') // dosis_dadas
    expect(row).toHaveTextContent('2') // ignoradas
  })

  it('envía la zona del navegador como tz y no envía now', async () => {
    let params: URLSearchParams | undefined
    server.use(
      http.get(URL_P2, ({ request }) => {
        params = new URL(request.url).searchParams
        return HttpResponse.json([])
      }),
    )
    renderApp('/alarmas/adherencia')
    await screen.findByText('Todavía no hay tratamientos')
    expect(params?.get('tz')).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone)
    expect(params?.has('now')).toBe(false)
  })

  it('filtra por destinatario', async () => {
    server.use(
      http.get(URL_P2, () =>
        HttpResponse.json([
          {
            alarma_id: 'a',
            destinatario: 'Ana',
            remedio: 'Ibuprofeno',
            dosis_esperadas: 3,
            dosis_dadas: 3,
            ignoradas: 0,
            ultima_dosis: null,
          },
          {
            alarma_id: 't1',
            destinatario: 'Juan',
            remedio: 'Amoxicilina',
            dosis_esperadas: 15,
            dosis_dadas: 13,
            ignoradas: 2,
            ultima_dosis: '2026-10-25T04:05:00Z',
          },
        ]),
      ),
    )
    const { router } = renderApp('/alarmas/adherencia')
    const table = await screen.findByRole('table')
    expect(within(table).getAllByRole('row')).toHaveLength(3)
    await userEvent.selectOptions(screen.getByLabelText('Destinatario'), 'Ana')
    expect(within(table).getAllByRole('row')).toHaveLength(2)
    expect(within(table).queryByText('Juan')).not.toBeInTheDocument()
    expect(router.state.location.search).toBe('?destinatario=Ana')
  })

  it('muestra el status HTTP y reintenta', async () => {
    let calls = 0
    server.use(
      http.get(URL_P2, () => {
        calls += 1
        return calls === 1 ? new HttpResponse(null, { status: 502 }) : HttpResponse.json([])
      }),
    )
    renderApp('/alarmas/adherencia')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('HTTP 502')
    await userEvent.click(within(alert).getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Todavía no hay tratamientos')).toBeInTheDocument()
  })
})
