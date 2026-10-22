import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../mocks/node'
import { ApiError, fetchProjection, projectionUrl, shouldRetry } from './api'

describe('projectionUrl', () => {
  it('arma /api/projections/{feature}/{name} y omite params vacíos', () => {
    const url = projectionUrl('alarmas', 'historial_dosis', {
      destinatario: 'Juan',
      tz: undefined,
      x: '',
    })
    expect(url.pathname).toBe('/api/projections/alarmas/historial_dosis')
    expect([...url.searchParams]).toEqual([['destinatario', 'Juan']])
  })
})

describe('fetchProjection', () => {
  it('devuelve el JSON tipado', async () => {
    server.use(http.get('*/api/projections/f/p', () => HttpResponse.json([{ a: 1 }])))
    await expect(fetchProjection<{ a: number }[]>('f', 'p')).resolves.toEqual([{ a: 1 }])
  })

  it('lanza ApiError con el status HTTP', async () => {
    server.use(http.get('*/api/projections/f/p', () => new HttpResponse(null, { status: 503 })))
    await expect(fetchProjection('f', 'p')).rejects.toMatchObject({ name: 'ApiError', status: 503 })
  })

  it('lanza ApiError 0 si no hay respuesta', async () => {
    server.use(http.get('*/api/projections/f/p', () => HttpResponse.error()))
    await expect(fetchProjection('f', 'p')).rejects.toMatchObject({ status: 0 })
  })
})

describe('shouldRetry', () => {
  it('no reintenta 4xx y sí 5xx o red, hasta 2 veces', () => {
    expect(shouldRetry(0, new ApiError(404, ''))).toBe(false)
    expect(shouldRetry(0, new ApiError(500, ''))).toBe(true)
    expect(shouldRetry(1, new ApiError(0, ''))).toBe(true)
    expect(shouldRetry(2, new ApiError(500, ''))).toBe(false)
  })
})
