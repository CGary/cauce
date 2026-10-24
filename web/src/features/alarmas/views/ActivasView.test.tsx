import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../../mocks/node'
import { renderApp } from '../../../test/render'

const URL_P1 = '*/api/projections/alarmas/alarmas_activas'
const URL_P2 = '*/api/projections/alarmas/adherencia_tratamiento'
const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

interface PushBody {
  events: {
    id: string
    user_id: string
    device_id: string
    type: string
    v: number
    ts: string
    payload: Record<string, unknown>
  }[]
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

describe('ActivasView (P1)', () => {
  it('muestra las alarmas del fixture y pide tz sin now', async () => {
    let params: URLSearchParams | undefined
    server.use(
      http.get(URL_P1, ({ request }) => {
        params = new URL(request.url).searchParams
        return HttpResponse.json([
          {
            alarma_id: 'a1',
            titulo: 'Vitaminas',
            tipo: 'otro',
            hora: '08:00',
            repeticion: { freq: 'DAILY' },
            proximo_disparo: '2026-10-22T12:00:00Z',
          },
        ])
      }),
    )
    renderApp('/alarmas/activas')
    const table = await screen.findByRole('table', { name: 'Alarmas activas' })
    expect(within(table).getByRole('row', { name: /Vitaminas/ })).toHaveTextContent('08:00')
    expect(screen.getByRole('link', { name: 'Activas' })).toHaveAttribute(
      'href',
      '/alarmas/activas',
    )
    expect(params?.get('tz')).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone)
    expect(params?.has('now')).toBe(false)
  })

  it('crear mensual día del mes -1 empuja alarma_creada v2 con el estado completo', async () => {
    const pushed = capturePush()
    renderApp('/alarmas/activas')
    await screen.findByRole('table', { name: 'Alarmas activas' })
    await userEvent.click(screen.getByRole('button', { name: 'Nueva alarma' }))
    await userEvent.type(screen.getByLabelText('Título'), 'Cierre')
    await userEvent.selectOptions(screen.getByLabelText('Repetición'), 'MONTHLY')
    await userEvent.click(screen.getByRole('checkbox', { name: 'Último día' }))
    await userEvent.click(screen.getByRole('button', { name: 'Crear' }))
    await waitFor(() => expect(pushed()?.events).toHaveLength(1))
    const event = pushed()?.events[0]
    expect(event).toMatchObject({ type: 'alarma_creada', v: 2, device_id: 'web', user_id: 'u1' })
    expect(event?.id).toMatch(UUID_V7)
    expect(event?.payload).toEqual({
      alarma_id: event?.payload.alarma_id,
      titulo: 'Cierre',
      tipo: 'otro',
      hora: '08:00',
      repeticion: { freq: 'MONTHLY', bymonthday: -1 },
    })
    expect(event?.payload.alarma_id).toMatch(UUID_V7)
  })

  it('crear mensual por ordinal empuja byday con ordinal', async () => {
    const pushed = capturePush()
    renderApp('/alarmas/activas')
    await screen.findByRole('table', { name: 'Alarmas activas' })
    await userEvent.click(screen.getByRole('button', { name: 'Nueva alarma' }))
    await userEvent.type(screen.getByLabelText('Título'), 'Último viernes')
    await userEvent.selectOptions(screen.getByLabelText('Repetición'), 'MONTHLY')
    await userEvent.click(screen.getByRole('radio', { name: 'Día de la semana' }))
    await userEvent.selectOptions(screen.getByLabelText('Ordinal'), '-1')
    await userEvent.selectOptions(screen.getByLabelText('Día'), 'FR')
    await userEvent.type(screen.getByLabelText('Intervalo'), '2')
    await userEvent.click(screen.getByRole('button', { name: 'Crear' }))
    await waitFor(() =>
      expect(pushed()?.events[0]?.payload.repeticion).toEqual({
        freq: 'MONTHLY',
        interval: 2,
        byday: ['-1FR'],
      }),
    )
  })

  it('editar empuja alarma_modificada v2 con el estado completo, no un diff', async () => {
    const pushed = capturePush()
    server.use(
      http.get(URL_P1, () =>
        HttpResponse.json([
          {
            alarma_id: 'm-edit',
            titulo: 'Primer lunes',
            tipo: 'remedio',
            hora: '09:00',
            repeticion: { freq: 'MONTHLY', interval: 1, byday: ['1MO'] },
            proximo_disparo: '2026-11-02T13:00:00Z',
          },
        ]),
      ),
      http.get(URL_P2, () =>
        HttpResponse.json([
          {
            alarma_id: 'm-edit',
            destinatario: 'Ana',
            remedio: 'Ibuprofeno',
            dosis_esperadas: 1,
            dosis_dadas: 0,
            ignoradas: 0,
            ultima_dosis: null,
          },
        ]),
      ),
    )
    renderApp('/alarmas/activas')
    await userEvent.click(await screen.findByRole('button', { name: 'Editar Primer lunes' }))
    const titulo = screen.getByLabelText('Título')
    await userEvent.clear(titulo)
    await userEvent.type(titulo, 'Primer lunes de Ana')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))
    await waitFor(() => expect(pushed()?.events).toHaveLength(1))
    expect(pushed()?.events[0]).toMatchObject({ type: 'alarma_modificada', v: 2, device_id: 'web' })
    expect(pushed()?.events[0]?.payload).toEqual({
      alarma_id: 'm-edit',
      titulo: 'Primer lunes de Ana',
      tipo: 'remedio',
      hora: '09:00',
      repeticion: { freq: 'MONTHLY', interval: 1, byday: ['1MO'] },
      destinatario: 'Ana',
      remedio: 'Ibuprofeno',
    })
  })

  it('Eliminar empuja alarma_desactivada con motivo usuario', async () => {
    const pushed = capturePush()
    renderApp('/alarmas/activas')
    await userEvent.click(await screen.findByRole('button', { name: 'Eliminar Vitaminas' }))
    expect(screen.getByText(/historial de dosis se conserva/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar eliminación' }))
    await waitFor(() => expect(pushed()?.events).toHaveLength(1))
    expect(pushed()?.events[0]).toMatchObject({
      type: 'alarma_desactivada',
      v: 1,
      device_id: 'web',
      payload: { alarma_id: 'a1', motivo: 'usuario' },
    })
  })

  it('muestra el mensaje del 400 y no lo esconde', async () => {
    server.use(
      http.post('*/sync/push', () =>
        HttpResponse.json(
          { error: 'events[0]: payload requires property "tipo"' },
          { status: 400 },
        ),
      ),
    )
    renderApp('/alarmas/activas')
    await screen.findByRole('table', { name: 'Alarmas activas' })
    await userEvent.click(screen.getByRole('button', { name: 'Nueva alarma' }))
    await userEvent.type(screen.getByLabelText('Título'), 'Cierre')
    await userEvent.click(screen.getByRole('button', { name: 'Crear' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'events[0]: payload requires property "tipo"',
    )
  })

  it('importa JSON en un solo push, un alarma_creada por ítem', async () => {
    const pushed = capturePush()
    renderApp('/alarmas/activas')
    await screen.findByRole('table', { name: 'Alarmas activas' })
    const file = new File(
      [
        JSON.stringify({
          formato: 'cauce.alarmas',
          version: 1,
          alarmas: [
            { titulo: 'A', tipo: 'otro', hora: '08:00', repeticion: null },
            {
              titulo: 'B',
              tipo: 'reunion',
              hora: '10:00',
              repeticion: { freq: 'WEEKLY', byday: ['MO'] },
            },
          ],
        }),
      ],
      'alarmas.json',
      { type: 'application/json' },
    )
    await userEvent.upload(screen.getByLabelText('Importar JSON'), file)
    await waitFor(() => expect(pushed()?.events).toHaveLength(2))
    const events = pushed()?.events ?? []
    expect(events.map((event) => event.type)).toEqual(['alarma_creada', 'alarma_creada'])
    expect(events.every((event) => event.v === 2 && event.device_id === 'web')).toBe(true)
    expect(events[0]?.payload).toMatchObject({ titulo: 'A', repeticion: null })
    expect(events[1]?.payload).toMatchObject({
      titulo: 'B',
      repeticion: { freq: 'WEEKLY', byday: ['MO'] },
    })
    expect(events[0]?.payload.alarma_id).toMatch(UUID_V7)
    expect(events[0]?.payload.alarma_id).not.toBe(events[1]?.payload.alarma_id)
  })

  it('importa un .ics y empuja el evento traducido', async () => {
    const pushed = capturePush()
    renderApp('/alarmas/activas')
    await screen.findByRole('table', { name: 'Alarmas activas' })
    const file = new File(
      [
        [
          'BEGIN:VCALENDAR',
          'BEGIN:VEVENT',
          'SUMMARY:Primer lunes',
          'DTSTART:20261102T090000',
          'RRULE:FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1',
          'CATEGORIES:otro',
          'END:VEVENT',
          'END:VCALENDAR',
        ].join('\n'),
      ],
      'alarmas.ics',
      { type: 'text/calendar' },
    )
    await userEvent.upload(screen.getByLabelText('Importar ICS'), file)
    await waitFor(() => expect(pushed()?.events).toHaveLength(1))
    expect(pushed()?.events[0]?.payload).toMatchObject({
      titulo: 'Primer lunes',
      tipo: 'otro',
      hora: '09:00',
      repeticion: { freq: 'MONTHLY', byday: ['1MO'] },
    })
  })

  it('un evento .ics inválido se ve en la UI y no frena al resto', async () => {
    const pushed = capturePush()
    renderApp('/alarmas/activas')
    await screen.findByRole('table', { name: 'Alarmas activas' })
    const file = new File(
      [
        [
          'BEGIN:VCALENDAR',
          'BEGIN:VEVENT',
          'SUMMARY:Zona rara',
          'DTSTART;TZID=Mars Standard Time:20261102T090000',
          'END:VEVENT',
          'BEGIN:VEVENT',
          'SUMMARY:Válida',
          'DTSTART:20261102T090000',
          'RRULE:FREQ=DAILY',
          'END:VEVENT',
          'END:VCALENDAR',
        ].join('\n'),
      ],
      'alarmas.ics',
      { type: 'text/calendar' },
    )
    await userEvent.upload(screen.getByLabelText('Importar ICS'), file)
    expect(await screen.findByText(/TZID desconocido/)).toBeInTheDocument()
    await waitFor(() => expect(pushed()?.events).toHaveLength(1))
    expect(pushed()?.events[0]?.payload).toMatchObject({ titulo: 'Válida', hora: '09:00' })
  })

  it('deshabilita exportar y editar hasta que cargue la adherencia', async () => {
    let resolveP2: (value: Response) => void = () => {}
    server.use(
      http.get(
        URL_P2,
        () =>
          new Promise<Response>((resolve) => {
            resolveP2 = resolve
          }),
      ),
    )
    renderApp('/alarmas/activas')
    await screen.findByRole('table', { name: 'Alarmas activas' })
    const exportarJson = screen.getByRole('button', { name: 'Exportar JSON' })
    const exportarIcs = screen.getByRole('button', { name: 'Exportar ICS' })
    const editar = screen.getByRole('button', { name: 'Editar Vitaminas' })
    expect(exportarJson).toBeDisabled()
    expect(exportarIcs).toBeDisabled()
    expect(editar).toBeDisabled()
    expect(screen.getByText(/Cargando adherencia/)).toBeInTheDocument()

    resolveP2(HttpResponse.json([]))
    await waitFor(() => expect(exportarJson).toBeEnabled())
    expect(exportarIcs).toBeEnabled()
    expect(editar).toBeEnabled()
  })
})
