import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { server } from '../mocks/node'
import { buildEvent, pushEvents, PushError, usePushEvents, uuidv7 } from './push'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

const envelopes = [
  buildEvent('alarma_creada', { nombre: 'a' }),
  buildEvent('alarma_modificada', {}, { v: 2 }),
]

describe('uuidv7', () => {
  it('tiene formato RFC 9562, versión 7 y variante 10x', () => {
    const id = uuidv7()
    expect(id).toMatch(UUID_RE)
    expect(id[14]).toBe('7')
    expect(['8', '9', 'a', 'b']).toContain(id[19])
  })

  it('ordena lexicográficamente con el tiempo: ids consecutivos quedan en orden', () => {
    const base = Date.now()
    const now = vi.spyOn(Date, 'now').mockReturnValue(base)
    const a = uuidv7()
    const b = uuidv7()
    expect(a < b).toBe(true)
    now.mockReturnValue(base + 5)
    const c = uuidv7()
    expect(b < c).toBe(true)
    // El timestamp de 48 bits están en los primeros bytes (12 hex chars).
    expect(BigInt('0x' + c.replaceAll('-', '').slice(0, 12))).toBe(BigInt(base + 5))
    now.mockRestore()
  })
})

describe('buildEvent', () => {
  it('arma el sobre completo según envelope.v1.json con device_id "web"', () => {
    const ev = buildEvent('alarma_creada', { nombre: 'Lunes' }, { ts: '2026-10-23T10:00:00Z', userId: 'u1' })
    expect(Object.keys(ev).sort()).toEqual(
      ['device_id', 'id', 'payload', 'ts', 'type', 'user_id', 'v'].sort(),
    )
    expect(ev.id).toMatch(UUID_RE)
    expect(ev.device_id).toBe('web')
    expect(ev.user_id).toBe('u1')
    expect(ev.v).toBe(1)
    expect(ev.ts).toBe('2026-10-23T10:00:00Z')
    expect(ev.payload).toEqual({ nombre: 'Lunes' })
  })

  it('por defecto toma el reloj del navegador en formato date-time', () => {
    const ev = buildEvent('alarma_desactivada', { motivo: 'usuario' })
    expect(ev.ts).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/)
  })
})

describe('pushEvents', () => {
  it('hace POST /sync/push con { events: [...envelopes] } y devuelve el resultado', async () => {
    let body: { events: unknown[] } | undefined
    server.use(
      http.post('*/sync/push', async ({ request }) => {
        body = (await request.json()) as { events: unknown[] }
        return HttpResponse.json({ accepted: 2, duplicates: 0 })
      }),
    )
    const result = await pushEvents(envelopes)
    expect(result).toEqual({ accepted: 2, duplicates: 0 })
    expect(body).toEqual({ events: envelopes })
  })

  it('lanza PushError con el mensaje de validación del 400', async () => {
    server.use(
      http.post('*/sync/push', () =>
        HttpResponse.json({ error: 'events[0]: payload requires property "tipo"' }, { status: 400 }),
      ),
    )
    const error = await pushEvents(envelopes).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(PushError)
    expect(error).toMatchObject({
      name: 'PushError',
      status: 400,
      message: 'events[0]: payload requires property "tipo"',
    })
  })
  it('lanza PushError en cualquier otro estado no-2xx', async () => {
    server.use(http.post('*/sync/push', () => new HttpResponse(null, { status: 503 })))
    await expect(pushEvents(envelopes)).rejects.toMatchObject({ name: 'PushError', status: 503 })
  })

  it('lanza PushError 0 si no hay respuesta', async () => {
    server.use(http.post('*/sync/push', () => HttpResponse.error()))
    await expect(pushEvents(envelopes)).rejects.toMatchObject({ name: 'PushError', status: 0 })
  })
})

describe('usePushEvents', () => {
  it('invalida las queries de proyección para que se relea lo calculado server-side', async () => {
    let projection = [{ n: 1 }]
    server.use(
      http.post('*/sync/push', () => HttpResponse.json({ accepted: 1, duplicates: 0 })),
      http.get('*/api/projections/f/p', () => HttpResponse.json(projection)),
    )
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    const { result } = renderHook(
      () => {
        const query = useQuery({
          queryKey: ['projection', 'f', 'p'],
          queryFn: () => fetch('/api/projections/f/p').then((r) => r.json()),
        })
        const mutation = usePushEvents()
        return { query, mutation }
      },
      { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> },
    )
    await waitFor(() => expect(result.current.query.data).toEqual([{ n: 1 }]))

    // El "servidor" ahora calcula otra proyección (siempre a la respuestas del GET).
    projection = [{ n: 2 }]
    await act(async () => {
      await expect(result.current.mutation.mutateAsync(envelopes)).resolves.toEqual({
        accepted: 1,
        duplicates: 0,
      })
    })
    // La invalidación forzó la relectura: devuelve lo nuevo del servidor.
    await waitFor(() => expect(result.current.query.data).toEqual([{ n: 2 }]))
  })

  it('no reintenta un 400 del servidor: una sola petición y el PushError aflora', async () => {
    let calls = 0
    server.use(
      http.post('*/sync/push', () => {
        calls += 1
        return HttpResponse.json({ error: 'user_id inválido' }, { status: 400 })
      }),
    )
    // Sin `retry: false` en las mutations: el retry del hook es el que se ejercita.
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    const { result } = renderHook(() => usePushEvents(), {
      wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    })

    act(() => {
      result.current.mutate(envelopes)
    })

    await waitFor(() => expect(result.current.error).toBeInstanceOf(PushError), { timeout: 10_000 })
    expect(result.current.error).toMatchObject({ status: 400, message: 'user_id inválido' })
    expect(calls).toBe(1)
  })
})
