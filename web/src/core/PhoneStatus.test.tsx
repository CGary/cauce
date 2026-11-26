import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { lazy } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { server } from '../mocks/node'
import { renderApp } from '../test/render'
import type { DevicesResponse } from './devices'
import type { FeatureModule } from './feature'
import { buildEvent, usePushEvents } from './push'
import { formatClock } from './time'

function Push() {
  const push = usePushEvents()
  return <button onClick={() => push.mutate([buildEvent('alarma_modificada', {})])}>Empujar</button>
}

const features: FeatureModule[] = [
  {
    id: 'demo',
    title: 'Demo',
    routes: [{ path: 'uno', title: 'Uno', Component: lazy(async () => ({ default: Push })) }],
  },
]

const NOW = Date.now()
const ago = (min: number) => new Date(NOW - min * 60_000).toISOString()

function devicesBody(overrides: Partial<DevicesResponse> = {}): DevicesResponse {
  return { now: new Date(NOW).toISOString(), server_seq: 100, devices: [], ...overrides }
}

function serveDevices(body: () => DevicesResponse) {
  server.use(http.get('*/sync/devices', () => HttpResponse.json(body())))
}

afterEach(() => {
  vi.useRealTimers()
})

describe('Tarjeta Teléfono', () => {
  it.each([
    ['verde', 3, 'success'],
    ['ámbar', 90, 'warning'],
    ['rojo', 600, 'danger'],
  ])('%s según el último contacto (%i min)', async (_name, minutes, tone) => {
    serveDevices(() =>
      devicesBody({
        devices: [
          {
            device_id: 'RMX5070',
            last_push_at: ago(minutes + 5),
            last_pull_at: ago(minutes),
            pulled_through_seq: 100,
          },
        ],
      }),
    )
    renderApp('/demo/uno', features)
    const card = await screen.findByRole('region', { name: 'Teléfono' })
    await waitFor(() => expect(card).toHaveAttribute('data-tone', tone))
    expect(card).toHaveTextContent(/Subió hace .* · Bajó hace /)
  })

  it('rojo y "nunca" si no hay teléfono; web y cli no cuentan', async () => {
    serveDevices(() =>
      devicesBody({
        devices: [
          {
            device_id: 'cli',
            last_push_at: ago(1),
            last_pull_at: null,
            pulled_through_seq: null,
          },
          { device_id: 'web', last_push_at: ago(1), last_pull_at: ago(1), pulled_through_seq: 1 },
        ],
      }),
    )
    renderApp('/demo/uno', features)
    const card = await screen.findByRole('region', { name: 'Teléfono' })
    await waitFor(() => expect(card).toHaveAttribute('data-tone', 'danger'))
    expect(card).toHaveTextContent('Nunca ha sincronizado')
  })

  it('dice que no pudo leer el estado si el servidor falla', async () => {
    server.use(http.get('*/sync/devices', () => new HttpResponse(null, { status: 404 })))
    renderApp('/demo/uno', features)
    expect(await screen.findByText('No se pudo leer el estado')).toBeInTheDocument()
  })
})

describe('Aviso tras un push', () => {
  it('pasa de pendiente a recibido cuando el teléfono baja el cambio', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const pulledAt = ago(0)
    let pulledThrough = 100
    let pushed = false
    server.use(
      http.post('*/sync/push', () => {
        pushed = true
        return HttpResponse.json({ accepted: 1, duplicates: 0, server_seq: 105 })
      }),
      http.get('*/sync/devices', () =>
        HttpResponse.json(
          devicesBody({
            server_seq: pushed ? 105 : 100,
            devices: [
              {
                device_id: 'RMX5070',
                last_push_at: ago(2),
                last_pull_at: pulledAt,
                pulled_through_seq: pulledThrough,
              },
            ],
          }),
        ),
      ),
    )
    renderApp('/demo/uno', features)
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    await screen.findByRole('region', { name: 'Teléfono' })
    expect(screen.queryByText(/aún no lo recibe/)).not.toBeInTheDocument()

    await user.click(await screen.findByRole('button', { name: 'Empujar' }))
    expect(await screen.findByText(/El teléfono aún no lo recibe/)).toBeInTheDocument()

    // El teléfono baja el cambio; el sondeo de 5 s lo detecta.
    pulledThrough = 105
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_100)
    })
    expect(
      await screen.findByText(`En el teléfono desde ${formatClock(pulledAt)}`, { exact: false }),
    ).toBeInTheDocument()
    expect(screen.queryByText(/aún no lo recibe/)).not.toBeInTheDocument()
  })

  it('un servidor sin server_seq no deja aviso pendiente', async () => {
    server.use(
      http.post('*/sync/push', () => HttpResponse.json({ accepted: 1, duplicates: 0 })),
      http.get('*/sync/devices', () => HttpResponse.json(devicesBody())),
    )
    renderApp('/demo/uno', features)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Empujar' }))
    await screen.findByRole('region', { name: 'Teléfono' })
    expect(screen.queryByText(/aún no lo recibe/)).not.toBeInTheDocument()
  })
})
