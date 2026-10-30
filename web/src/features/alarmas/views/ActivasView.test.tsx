import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { browserTimeZone } from '../../../core/time'
import { server } from '../../../mocks/node'
import { renderApp } from '../../../test/render'
import { localDate } from '../model'

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

/** Captura el archivo que se descarga (jsdom no implementa createObjectURL). */
function captureDownload() {
  const blobs: Blob[] = []
  const originalCreate = Object.getOwnPropertyDescriptor(URL, 'createObjectURL')
  const originalClick = HTMLAnchorElement.prototype.click
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    value: (blob: Blob) => {
      blobs.push(blob)
      return 'blob:cauce'
    },
  })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: () => {} })
  // El click del ancla dispararía una navegación que jsdom no implementa.
  HTMLAnchorElement.prototype.click = function () {}
  return {
    blobs,
    restore() {
      if (originalCreate) Object.defineProperty(URL, 'createObjectURL', originalCreate)
      else Reflect.deleteProperty(URL, 'createObjectURL')
      Reflect.deleteProperty(URL, 'revokeObjectURL')
      HTMLAnchorElement.prototype.click = originalClick
    },
  }
}

function blobText(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(blob)
  })
}

const ALARMA_PROPIA = {
  alarma_id: 'a1',
  titulo: 'Vitaminas',
  tipo: 'otro',
  hora: '08:00',
  repeticion: { freq: 'DAILY' },
  proximo_disparo: '2026-10-22T12:00:00Z',
}

const REUNION_GOOGLE = {
  alarma_id: 'gcal:r1',
  titulo: 'Reunión de equipo',
  tipo: 'reunion',
  hora: '09:30',
  repeticion: null,
  proximo_disparo: '2026-11-02T13:30:00Z',
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

  it('crear mensual día del mes -1 empuja alarma_creada v3 con el estado completo', async () => {
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
    expect(event).toMatchObject({ type: 'alarma_creada', v: 3, device_id: 'web', user_id: 'u1' })
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

  it('sin fecha de inicio, el payload no lleva la clave `fecha`', async () => {
    const pushed = capturePush()
    renderApp('/alarmas/activas')
    await screen.findByRole('table', { name: 'Alarmas activas' })
    await userEvent.click(screen.getByRole('button', { name: 'Nueva alarma' }))
    await userEvent.type(screen.getByLabelText('Título'), 'Llamar al banco')
    expect(screen.getByLabelText('Fecha de inicio')).toHaveValue('')
    await userEvent.click(screen.getByRole('button', { name: 'Crear' }))
    await waitFor(() => expect(pushed()?.events).toHaveLength(1))
    const payload = pushed()?.events[0]?.payload ?? {}
    expect(Object.keys(payload)).not.toContain('fecha')
  })

  it('con fecha de inicio, empuja alarma_creada v3 con `fecha`', async () => {
    const pushed = capturePush()
    renderApp('/alarmas/activas')
    await screen.findByRole('table', { name: 'Alarmas activas' })
    await userEvent.click(screen.getByRole('button', { name: 'Nueva alarma' }))
    await userEvent.type(screen.getByLabelText('Título'), 'Revisar facturación')
    fireEvent.change(screen.getByLabelText('Fecha de inicio'), { target: { value: '2026-11-14' } })
    await userEvent.click(screen.getByRole('button', { name: 'Crear' }))
    await waitFor(() => expect(pushed()?.events).toHaveLength(1))
    const event = pushed()?.events[0]
    expect(event).toMatchObject({ type: 'alarma_creada', v: 3, device_id: 'web' })
    expect(event?.payload).toMatchObject({
      titulo: 'Revisar facturación',
      hora: '08:00',
      fecha: '2026-11-14',
      repeticion: null,
    })
  })

  it('crear remedio propone cada 8 h y "por días" se convierte en count', async () => {
    const pushed = capturePush()
    renderApp('/alarmas/activas')
    await screen.findByRole('table', { name: 'Alarmas activas' })
    await userEvent.click(screen.getByRole('button', { name: 'Nueva alarma' }))
    await userEvent.type(screen.getByLabelText('Título'), 'Amoxicilina Juan')
    await userEvent.selectOptions(screen.getByLabelText('Tipo'), 'remedio')
    expect(screen.getByLabelText('Repetición')).toHaveValue('HOURLY')
    expect(screen.getByRole('radio', { name: '8 h' })).toBeChecked()
    await userEvent.click(screen.getByRole('radio', { name: '6 h' }))
    await userEvent.selectOptions(screen.getByLabelText('Duración'), 'days')
    await userEvent.type(screen.getByLabelText('Días'), '5')
    expect(screen.getByText('20 tomas en total.')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Destinatario'), 'Juan')
    await userEvent.type(screen.getByLabelText('Remedio'), 'Amoxicilina')
    await userEvent.click(screen.getByRole('button', { name: 'Crear' }))
    await waitFor(() => expect(pushed()?.events).toHaveLength(1))
    expect(pushed()?.events[0]?.payload).toMatchObject({
      tipo: 'remedio',
      hora: '08:00',
      repeticion: { freq: 'HOURLY', interval: 6, count: 20 },
    })
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

  it('editar empuja alarma_modificada v3 con el estado completo, no un diff', async () => {
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
    expect(pushed()?.events[0]).toMatchObject({ type: 'alarma_modificada', v: 3, device_id: 'web' })
    expect(pushed()?.events[0]?.payload).toEqual({
      alarma_id: 'm-edit',
      titulo: 'Primer lunes de Ana',
      tipo: 'remedio',
      hora: '09:00',
      // P1 no trae `fecha`: se rellena con la fecha local del próximo disparo.
      fecha: localDate('2026-11-02T13:00:00Z', browserTimeZone()),
      repeticion: { freq: 'MONTHLY', interval: 1, byday: ['1MO'] },
      destinatario: 'Ana',
      remedio: 'Ibuprofeno',
    })
  })

  it('editar una serie la reancla en el próximo disparo y su count pasa a restantes', async () => {
    const pushed = capturePush()
    server.use(
      http.get(URL_P1, () =>
        HttpResponse.json([
          {
            alarma_id: 's1',
            titulo: 'Vitaminas',
            tipo: 'otro',
            hora: '08:00',
            repeticion: { freq: 'DAILY', count: 15 },
            proximo_disparo: '2026-11-02T12:00:00Z',
            restantes: 8,
          },
        ]),
      ),
    )
    renderApp('/alarmas/activas')
    await userEvent.click(await screen.findByRole('button', { name: 'Editar Vitaminas' }))
    expect(screen.getByLabelText('Fecha de inicio')).toHaveValue(
      localDate('2026-11-02T12:00:00Z', browserTimeZone()),
    )
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))
    await waitFor(() => expect(pushed()?.events).toHaveLength(1))
    expect(pushed()?.events[0]?.payload).toMatchObject({
      fecha: localDate('2026-11-02T12:00:00Z', browserTimeZone()),
      repeticion: { freq: 'DAILY', count: 8 },
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
    expect(events.every((event) => event.v === 3 && event.device_id === 'web')).toBe(true)
    expect(events[0]?.payload).toMatchObject({ titulo: 'A', repeticion: null })
    expect(events[1]?.payload).toMatchObject({
      titulo: 'B',
      repeticion: { freq: 'WEEKLY', byday: ['MO'] },
    })
    expect(events[0]?.payload.alarma_id).toMatch(UUID_V7)
    expect(events[0]?.payload.alarma_id).not.toBe(events[1]?.payload.alarma_id)
  })

  it('importa JSON con `fecha` y la empuja en el payload', async () => {
    const pushed = capturePush()
    renderApp('/alarmas/activas')
    await screen.findByRole('table', { name: 'Alarmas activas' })
    const file = new File(
      [
        JSON.stringify({
          formato: 'cauce.alarmas',
          version: 1,
          alarmas: [
            {
              titulo: 'Cierre',
              tipo: 'otro',
              hora: '09:00',
              fecha: '2026-12-31',
              repeticion: null,
            },
          ],
        }),
      ],
      'alarmas.json',
      { type: 'application/json' },
    )
    await userEvent.upload(screen.getByLabelText('Importar JSON'), file)
    await waitFor(() => expect(pushed()?.events).toHaveLength(1))
    expect(pushed()?.events[0]?.payload).toMatchObject({
      titulo: 'Cierre',
      fecha: '2026-12-31',
    })
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
      // La fecha local de DTSTART viaja como `fecha`.
      fecha: '2026-11-02',
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

  it('las reuniones de Google son de solo lectura: no se editan ni se eliminan', async () => {
    server.use(http.get(URL_P1, () => HttpResponse.json([ALARMA_PROPIA, REUNION_GOOGLE])))
    renderApp('/alarmas/activas')
    const table = await screen.findByRole('table', { name: 'Alarmas activas' })
    expect(within(table).getByText('Google Calendar · se edita en Reuniones')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Editar Reunión de equipo' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Eliminar Reunión de equipo' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Editar Vitaminas' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Eliminar Vitaminas' })).toBeInTheDocument()
  })

  it('las reuniones de Google no se exportan a JSON ni a .ics', async () => {
    server.use(http.get(URL_P1, () => HttpResponse.json([ALARMA_PROPIA, REUNION_GOOGLE])))
    const download = captureDownload()
    try {
      renderApp('/alarmas/activas')
      await screen.findByRole('table', { name: 'Alarmas activas' })
      await waitFor(() => expect(screen.queryByText(/Cargando adherencia/)).not.toBeInTheDocument())
      await userEvent.click(screen.getByRole('button', { name: 'Exportar JSON' }))
      const json = await blobText(download.blobs[0] as Blob)
      expect(json).toContain('Vitaminas')
      expect(json).not.toContain('Reunión de equipo')

      await userEvent.click(screen.getByRole('button', { name: 'Exportar ICS' }))
      const ics = await blobText(download.blobs[1] as Blob)
      expect(ics).toContain('Vitaminas')
      expect(ics).not.toContain('Reunión de equipo')
    } finally {
      download.restore()
    }
  })

  it('con solo reuniones de Google no hay nada que exportar', async () => {
    server.use(http.get(URL_P1, () => HttpResponse.json([REUNION_GOOGLE])))
    renderApp('/alarmas/activas')
    await screen.findByRole('table', { name: 'Alarmas activas' })
    await waitFor(() => expect(screen.queryByText(/Cargando adherencia/)).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Exportar JSON' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Exportar ICS' })).toBeDisabled()
  })

  it('si falla la adherencia, un solo aviso; exportar sigue habilitado y editar no', async () => {
    server.use(
      http.get(URL_P1, () => HttpResponse.json([ALARMA_PROPIA])),
      http.get(URL_P2, () => new HttpResponse(null, { status: 500 })),
    )
    renderApp('/alarmas/activas')
    await screen.findByRole('table', { name: 'Alarmas activas' })
    expect(await screen.findByText(/No se pudo cargar la adherencia/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Exportar JSON' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Exportar ICS' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Editar Vitaminas' })).toBeDisabled()
  })

  it('con la adherencia caída, el .ics conserva COUNT desde restantes de P1', async () => {
    server.use(
      http.get(URL_P1, () =>
        HttpResponse.json([
          {
            ...ALARMA_PROPIA,
            repeticion: { freq: 'DAILY', count: 15 },
            restantes: 9,
          },
        ]),
      ),
      http.get(URL_P2, () => new HttpResponse(null, { status: 500 })),
    )
    const download = captureDownload()
    try {
      renderApp('/alarmas/activas')
      await screen.findByRole('table', { name: 'Alarmas activas' })
      expect(await screen.findByText(/No se pudo cargar la adherencia/)).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Exportar ICS' }))
      const ics = await blobText(download.blobs[0] as Blob)
      expect(ics).toContain('RRULE:FREQ=DAILY;COUNT=9')
    } finally {
      download.restore()
    }
  })

  it('«otro» no muestra chip de tipo y los filtros acotan la tabla', async () => {
    server.use(
      http.get(URL_P1, () =>
        HttpResponse.json([
          ALARMA_PROPIA,
          { ...ALARMA_PROPIA, alarma_id: 'a2', titulo: 'Pastilla', tipo: 'remedio' },
          REUNION_GOOGLE,
        ]),
      ),
    )
    renderApp('/alarmas/activas')
    const table = await screen.findByRole('table', { name: 'Alarmas activas' })
    expect(within(table).getAllByRole('row')).toHaveLength(4)
    expect(within(table).getByText('Remedio')).toBeInTheDocument()
    expect(within(table).queryByText('Otro')).toBeNull()
    expect(document.title).toBe('Alarmas activas · Cauce')
    await userEvent.selectOptions(screen.getByLabelText('Filtrar por origen'), 'google')
    expect(within(table).getAllByRole('row')).toHaveLength(2)
    await userEvent.selectOptions(screen.getByLabelText('Filtrar por origen'), '')
    await userEvent.selectOptions(screen.getByLabelText('Filtrar por tipo'), 'remedio')
    expect(within(table).getAllByRole('row')).toHaveLength(2)
  })
})
