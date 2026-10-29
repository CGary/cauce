import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../../mocks/node'
import { renderApp } from '../../../test/render'

interface PushBody {
  events: { device_id: string; type: string; v: number; payload: Record<string, unknown> }[]
}

function capturePush() {
  let body: PushBody | undefined
  server.use(
    http.post('*/sync/push', async ({ request }) => {
      body = (await request.json()) as PushBody
      return HttpResponse.json({ accepted: body.events.length, duplicates: 0 })
    }),
  )
  return () => body
}

describe('ReunionesView (P1)', () => {
  it('muestra las reuniones del fixture con cuenta y aviso, y pide tz sin now', async () => {
    let params: URLSearchParams | undefined
    server.use(
      http.get('*/api/projections/calendario_google/reuniones_proximas', ({ request }) => {
        params = new URL(request.url).searchParams
        return HttpResponse.json([
          {
            reunion_id: 'r1',
            serie_id: 'r1',
            cuenta: 'ana.trabajo@example.com',
            titulo: 'Reunión de equipo',
            inicio: '2026-11-02T14:00:00Z',
            fin: '2026-11-02T15:00:00Z',
            todo_el_dia: false,
            aviso: { origen: 'google', minutos_antes: 30 },
            aviso_en: '2026-11-02T13:30:00Z',
          },
          {
            reunion_id: 'd1',
            serie_id: 'd1',
            cuenta: 'ana.personal@example.com',
            titulo: 'Feriado',
            inicio: '2026-11-06T00:00:00Z',
            fin: '2026-11-07T00:00:00Z',
            todo_el_dia: true,
            aviso: { origen: 'defecto', hora: '08:00' },
            aviso_en: '2026-11-06T12:00:00Z',
          },
          {
            reunion_id: 'y1',
            serie_id: 'y1',
            cuenta: 'ana.personal@example.com',
            titulo: 'Yoga',
            inicio: '2026-11-08T22:00:00Z',
            fin: '2026-11-08T23:00:00Z',
            todo_el_dia: false,
            aviso: { origen: 'cauce', apagado: true },
            aviso_en: null,
          },
        ])
      }),
    )
    renderApp('/calendario_google/proximas')
    const equipo = (await screen.findByText('Reunión de equipo')).closest('li')
    expect(equipo).toHaveTextContent('ana.trabajo@example.com')
    expect(equipo).toHaveTextContent('30 min antes · Google')
    const feriado = screen.getByText('Feriado').closest('li')
    expect(feriado).toHaveTextContent('Todo el día')
    expect(feriado).toHaveTextContent('08:00 · por defecto')
    expect(screen.getByText('Yoga').closest('li')).toHaveTextContent('Apagado · cauce')
    expect(params?.get('tz')).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone)
    expect(params?.has('now')).toBe(false)
  })

  it('renderiza las filas del mock por defecto', async () => {
    renderApp('/calendario_google/proximas')
    expect(await screen.findByText('Dentista')).toBeInTheDocument()
    expect(screen.getByText('Cumpleaños de Luis')).toBeInTheDocument()
  })

  it('editar minutos empuja un aviso_reunion_editado con solo minutos_antes', async () => {
    const pushed = capturePush()
    const user = userEvent.setup()
    renderApp('/calendario_google/proximas')
    await user.click(await screen.findByRole('button', { name: 'Editar aviso de Dentista' }))
    const form = screen.getByRole('form', { name: 'Aviso de Dentista' })
    const input = within(form).getByLabelText('Minutos antes', { selector: 'input' })
    await user.clear(input)
    await user.type(input, '15')
    await user.click(within(form).getByRole('button', { name: 'Guardar aviso' }))
    await waitFor(() => expect(pushed()).toBeDefined())
    const event = pushed()!.events[0]!
    expect(pushed()!.events).toHaveLength(1)
    expect(event.type).toBe('aviso_reunion_editado')
    expect(event.v).toBe(1)
    expect(event.device_id).toBe('web')
    expect(event.payload).toEqual({ serie_id: 'r2', minutos_antes: 15 })
  })

  it('rechaza minutos inválidos sin empujar', async () => {
    const pushed = capturePush()
    const user = userEvent.setup()
    renderApp('/calendario_google/proximas')
    await user.click(await screen.findByRole('button', { name: 'Editar aviso de Dentista' }))
    const form = screen.getByRole('form', { name: 'Aviso de Dentista' })
    const input = within(form).getByLabelText('Minutos antes', { selector: 'input' })
    await user.clear(input)
    await user.type(input, '-3')
    await user.click(within(form).getByRole('button', { name: 'Guardar aviso' }))
    expect(await screen.findByText(/entero de 0 o más/)).toBeInTheDocument()
    expect(pushed()).toBeUndefined()
  })

  it('apagar empuja solo apagado: true', async () => {
    const pushed = capturePush()
    const user = userEvent.setup()
    renderApp('/calendario_google/proximas')
    await user.click(await screen.findByRole('button', { name: 'Editar aviso de Dentista' }))
    const form = screen.getByRole('form', { name: 'Aviso de Dentista' })
    await user.selectOptions(within(form).getByLabelText('Aviso'), 'apagado')
    await user.click(within(form).getByRole('button', { name: 'Guardar aviso' }))
    await waitFor(() => expect(pushed()).toBeDefined())
    expect(pushed()!.events[0]!.payload).toEqual({ serie_id: 'r2', apagado: true })
  })

  it('una reunión de todo el día ofrece hora y empuja solo hora', async () => {
    const pushed = capturePush()
    const user = userEvent.setup()
    renderApp('/calendario_google/proximas')
    await user.click(await screen.findByRole('button', { name: 'Editar aviso de Feriado' }))
    const form = screen.getByRole('form', { name: 'Aviso de Feriado' })
    expect(within(form).queryByRole('option', { name: 'Minutos antes' })).toBeNull()
    const hora = within(form).getByLabelText('Hora')
    await user.clear(hora)
    await user.type(hora, '07:30')
    await user.click(within(form).getByRole('button', { name: 'Guardar aviso' }))
    await waitFor(() => expect(pushed()).toBeDefined())
    expect(pushed()!.events[0]!.payload).toEqual({ serie_id: 'd2', hora: '07:30' })
  })
})
