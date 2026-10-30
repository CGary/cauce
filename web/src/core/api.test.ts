import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../mocks/node'
import {
  ApiError,
  describeError,
  fetchProjection,
  projectionUrl,
  retryDelay,
  shouldRetry,
} from './api'

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
  it('no reintenta 4xx; 5xx hasta 2 veces', () => {
    expect(shouldRetry(0, new ApiError(404, ''))).toBe(false)
    expect(shouldRetry(0, new ApiError(500, ''))).toBe(true)
    expect(shouldRetry(1, new ApiError(500, ''))).toBe(true)
    expect(shouldRetry(2, new ApiError(500, ''))).toBe(false)
  })

  it('red o CORS (status 0, TypeError): un solo reintento', () => {
    expect(shouldRetry(0, new ApiError(0, ''))).toBe(true)
    expect(shouldRetry(1, new ApiError(0, ''))).toBe(false)
    expect(shouldRetry(0, new TypeError('Failed to fetch'))).toBe(true)
    expect(shouldRetry(1, new TypeError('Failed to fetch'))).toBe(false)
  })
})

describe('retryDelay', () => {
  it('crece de forma exponencial con tope de 8 s', () => {
    expect([0, 1, 2, 3, 4, 10].map(retryDelay)).toEqual([1000, 2000, 4000, 8000, 8000, 8000])
  })
})

describe('describeError', () => {
  it('distingue sin red, sin API, acceso, 404 y 5xx', () => {
    expect(describeError(new ApiError(0, ''), true).cause).toBe('sin conexión con la API')
    expect(describeError(new ApiError(0, ''), false).cause).toBe('sin conexión a internet')
    expect(describeError(new TypeError('x'), true).cause).toBe('sin conexión con la API')
    expect(describeError(new ApiError(500, ''), true)).toMatchObject({
      cause: 'la API respondió 500',
      detail: 'HTTP 500',
    })
    expect(describeError(new ApiError(502, ''), true).cause).toBe('la API respondió 502')
    expect(describeError(new ApiError(404, ''), true).cause).toContain('404')
    expect(describeError(new ApiError(403, ''), true).cause).toContain('rechazó el acceso')
    expect(describeError(new Error('boom'), true)).toEqual({
      cause: 'error inesperado',
      detail: 'boom',
    })
  })
})
