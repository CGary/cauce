import { describe, expect, it } from 'vitest'
import { formatByday, formatHora, groupByDay, localDay } from './format'
import { repeticionResumen } from './model'

describe('formatByday', () => {
  it('resume rangos de tres o más días', () => {
    expect(formatByday(['MO', 'TU', 'WE', 'TH', 'FR'])).toBe('Lun a vie')
    expect(formatByday(['TU', 'WE', 'TH'])).toBe('Mar a jue')
  })

  it('lista días sueltos con «y» al final, sin importar el orden', () => {
    expect(formatByday(['FR', 'MO', 'WE'])).toBe('Lun, mié y vie')
    expect(formatByday(['MO', 'TU'])).toBe('Lun y mar')
    expect(formatByday(['SU'])).toBe('Dom')
  })

  it('combina rango y sueltos, y reconoce la semana completa', () => {
    expect(formatByday(['MO', 'TU', 'WE', 'FR'])).toBe('Lun a mié y vie')
    expect(formatByday(['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU'])).toBe('Todos los días')
  })

  it('escribe los ordinales mensuales', () => {
    expect(formatByday(['1MO'])).toBe('1.º lun')
    expect(formatByday(['-1FR'])).toBe('Último vie')
  })

  it('se usa en el resumen de repetición', () => {
    expect(repeticionResumen({ freq: 'WEEKLY', byday: ['MO', 'WE', 'FR'] })).toBe(
      'Semanal · Lun, mié y vie',
    )
  })
})

describe('formatHora', () => {
  it('completa a dos dígitos', () => {
    expect(formatHora('7:01')).toBe('07:01')
    expect(formatHora('08:15')).toBe('08:15')
    expect(formatHora('23:59')).toBe('23:59')
    expect(formatHora('mañana')).toBe('mañana')
  })
})

describe('groupByDay', () => {
  it('agrupa filas consecutivas del mismo día local y conserva el orden', () => {
    const at = (h: number, d: number) => new Date(2026, 9, d, h, 0).toISOString()
    const rows = [at(20, 25), at(8, 25), at(22, 24)].map((ts) => ({ ts }))
    const groups = groupByDay(rows, (r) => r.ts)
    expect(groups.map((g) => g.rows.length)).toEqual([2, 1])
    expect(groups[0]?.dia).toBe(localDay(at(20, 25)))
    expect(groups[0]?.dia).toBe('2026-10-25')
  })
})
