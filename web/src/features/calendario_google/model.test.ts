import { describe, expect, it } from 'vitest'
import { MAX_MINUTOS_ANTES, avisoPayload } from './model'

const form = (minutos: string) => ({ modo: 'minutos_antes' as const, minutos, hora: '08:00' })

describe('avisoPayload', () => {
  it('acepta de 0 hasta el tope de 4 semanas', () => {
    expect(MAX_MINUTOS_ANTES).toBe(40320)
    expect(avisoPayload('s1', form('0'))).toEqual({
      ok: true,
      payload: { serie_id: 's1', minutos_antes: 0 },
    })
    expect(avisoPayload('s1', form('40320'))).toEqual({
      ok: true,
      payload: { serie_id: 's1', minutos_antes: 40320 },
    })
  })

  it('rechaza valores por encima del tope o fuera de los enteros seguros', () => {
    expect(avisoPayload('s1', form('40321')).ok).toBe(false)
    expect(avisoPayload('s1', form('99999999999999999999'))).toEqual({
      ok: false,
      error: 'Los minutos van de 0 a 40320 (4 semanas)',
    })
  })
})
