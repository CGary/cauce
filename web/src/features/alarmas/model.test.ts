import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  MAX_COUNT,
  MAX_INTERVAL,
  activaToForm,
  desactivadaPayload,
  dosesForDays,
  emptyForm,
  exportBulk,
  formToPayload,
  isScheduleLocked,
  parseBulk,
  parseRepeticion,
  remedioCorrectionPayload,
  repeticionResumen,
  rowsToBulk,
  validateCorrection,
  validateForm,
  type AlarmaFormState,
} from './model'
import type { AlarmaActiva } from './types'

const TZ = 'America/La_Paz'

function form(partial: Partial<AlarmaFormState> = {}): AlarmaFormState {
  return { ...emptyForm(), ...partial }
}

// validateForm rechaza fechas pasadas con el reloj del navegador: se fija para que las
// fechas de los casos (nov 2026) sean futuras siempre.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-20T12:00:00Z'))
})
afterEach(() => {
  vi.useRealTimers()
})

describe('validateForm: lo que no puede sonar no se guarda', () => {
  const now = new Date('2026-11-19T15:00:00Z')

  it('una puntual con fecha y hora pasadas es un error de fecha', () => {
    expect(validateForm(form({ titulo: 'X', fecha: '2026-11-18', hora: '09:00' }), now)).toMatch(
      /^La fecha y la hora ya pasaron/,
    )
    expect(() => formToPayload(form({ titulo: 'X', fecha: '2026-11-18' }), 'a1', now)).toThrow()
    // Sin fecha es «desde ahora»: nunca está en el pasado.
    expect(validateForm(form({ titulo: 'X', hora: '00:01' }), now)).toBeNull()
    expect(validateForm(form({ titulo: 'X', fecha: '2026-11-25', hora: '09:00' }), now)).toBeNull()
  })

  it('«Hasta» anterior a la fecha de inicio o ya pasado es un error de Hasta', () => {
    const base = { titulo: 'X', freq: 'DAILY' as const, limit: 'until' as const }
    expect(validateForm(form({ ...base, fecha: '2026-12-10', until: '2026-12-01' }), now)).toBe(
      'Hasta no puede ser anterior a la fecha de inicio',
    )
    expect(validateForm(form({ ...base, until: '2026-11-01' }), now)).toMatch(/^Hasta ya pasó/)
    expect(
      validateForm(form({ ...base, fecha: '2026-12-01', until: '2026-12-01' }), now),
    ).toBeNull()
  })

  it('«cada N horas» sin opción elegida es un error, no cada 1 h', () => {
    expect(validateForm(form({ titulo: 'X', freq: 'HOURLY', interval: '' }))).toMatch(
      /^El intervalo es obligatorio/,
    )
  })

  it('un HOURLY sin interval en P1 se edita como cada 1 h explícito', () => {
    const row: AlarmaActiva = {
      alarma_id: 'h0',
      titulo: 'Agua',
      tipo: 'otro',
      hora: '08:00',
      repeticion: { freq: 'HOURLY' },
      proximo_disparo: '2026-11-02T12:00:00Z',
    }
    const state = activaToForm(row, undefined, TZ)
    expect(state.interval).toBe('1')
    expect(formToPayload(state, 'h0').repeticion).toEqual({ freq: 'HOURLY', interval: 1 })
  })
})

describe('topes numéricos', () => {
  it('cantidad hasta MAX_COUNT, intervalo hasta MAX_INTERVAL', () => {
    expect(MAX_COUNT).toBe(1000)
    expect(MAX_INTERVAL).toBe(365)
    const daily = { titulo: 'X', freq: 'DAILY' as const }
    expect(validateForm(form({ ...daily, limit: 'count', count: '1000' }))).toBeNull()
    expect(validateForm(form({ ...daily, limit: 'count', count: '1001' }))).toMatch(/^La cantidad/)
    expect(validateForm(form({ ...daily, limit: 'count', count: '99999999999999999999' }))).toMatch(
      /^La cantidad/,
    )
    expect(validateForm(form({ ...daily, interval: '365' }))).toBeNull()
    expect(validateForm(form({ ...daily, interval: '366' }))).toMatch(/^El intervalo/)
    expect(validateForm(form({ ...daily, interval: '99999999999999999999' }))).toMatch(
      /^El intervalo/,
    )
  })

  it('«por días» que da más de MAX_COUNT tomas es un error de días', () => {
    expect(
      validateForm(form({ titulo: 'X', freq: 'HOURLY', interval: '1', limit: 'days', dias: '42' })),
    ).toMatch(/^Los días dan 1008 tomas/)
  })

  it('la carga en bloque (y el .ics, que pasa por aquí) rechaza valores fuera de tope', () => {
    expect(parseRepeticion({ freq: 'DAILY', count: 1001 })).toMatch(/^count tiene que ser/)
    expect(parseRepeticion({ freq: 'DAILY', count: 1e20 })).toMatch(/^count tiene que ser/)
    expect(parseRepeticion({ freq: 'DAILY', interval: 366 })).toMatch(/^interval tiene que ser/)
    expect(parseRepeticion({ freq: 'DAILY', count: 1000, interval: 365 })).toEqual({
      freq: 'DAILY',
      count: 1000,
      interval: 365,
    })
    const file = JSON.stringify({
      formato: 'cauce.alarmas',
      version: 1,
      alarmas: [
        {
          titulo: 'A',
          tipo: 'otro',
          hora: '08:00',
          repeticion: { freq: 'DAILY', count: 99999999999999999999 },
        },
      ],
    })
    expect(parseBulk(file)).toEqual({
      ok: false,
      error: 'Ítem 1: count tiene que ser un entero entre 1 y 1000',
    })
  })
})

describe('repeticionResumen', () => {
  it('un HOURLY dice cada cuántas horas', () => {
    expect(repeticionResumen({ freq: 'HOURLY', interval: 8 })).toBe('Cada 8 horas')
    expect(repeticionResumen({ freq: 'HOURLY', interval: 1 })).toBe('Cada hora')
    expect(repeticionResumen({ freq: 'HOURLY' })).toBe('Cada hora')
  })

  it('con `restantes` de P1 muestra lo que queda, no el count original', () => {
    expect(repeticionResumen({ freq: 'HOURLY', interval: 8, count: 15 }, 7)).toBe(
      'Cada 8 horas · quedan 7',
    )
    expect(repeticionResumen({ freq: 'DAILY', count: 15 }, 1)).toBe('Diaria · queda 1')
    expect(repeticionResumen({ freq: 'DAILY', count: 15 })).toBe('Diaria · 15 veces')
  })
})

describe('formToPayload', () => {
  it('una vez no lleva campos de repetición', () => {
    expect(formToPayload(form({ titulo: 'Llamar', hora: '07:30' }), 'a1')).toEqual({
      alarma_id: 'a1',
      titulo: 'Llamar',
      tipo: 'otro',
      hora: '07:30',
      repeticion: null,
    })
  })

  it('sin fecha de inicio, la clave `fecha` no viaja', () => {
    const payload = formToPayload(form({ titulo: 'Llamar', hora: '07:30' }), 'a1')
    expect(Object.keys(payload)).not.toContain('fecha')
    expect(payload.fecha).toBeUndefined()
  })

  it('con fecha de inicio, viaja como `fecha` (AAAA-MM-DD)', () => {
    expect(
      formToPayload(
        form({ titulo: 'Revisar facturación', hora: '16:00', fecha: '2026-11-14' }),
        'a1',
      ),
    ).toEqual({
      alarma_id: 'a1',
      titulo: 'Revisar facturación',
      tipo: 'otro',
      hora: '16:00',
      fecha: '2026-11-14',
      repeticion: null,
    })
  })

  it('una fecha de inicio inválida no pasa la validación', () => {
    expect(validateForm(form({ titulo: 'Llamar', fecha: '14-11-2026' }))).toBe(
      'La fecha de inicio tiene que ser una fecha AAAA-MM-DD',
    )
  })

  it('remedio horario es estado completo, con interval y count', () => {
    expect(
      formToPayload(
        form({
          titulo: 'Amoxicilina Juan',
          tipo: 'remedio',
          hora: '08:00',
          freq: 'HOURLY',
          interval: '8',
          limit: 'count',
          count: '15',
          destinatario: 'Juan',
          remedio: 'Amoxicilina',
        }),
        'a1',
      ),
    ).toEqual({
      alarma_id: 'a1',
      titulo: 'Amoxicilina Juan',
      tipo: 'remedio',
      hora: '08:00',
      repeticion: { freq: 'HOURLY', interval: 8, count: 15 },
      destinatario: 'Juan',
      remedio: 'Amoxicilina',
    })
  })

  it('semanal ordena byday y no mezcla con límite vacío', () => {
    expect(
      formToPayload(
        form({
          titulo: 'Reunión',
          tipo: 'reunion',
          hora: '10:00',
          freq: 'WEEKLY',
          weekdays: ['WE', 'MO'],
        }),
        'a1',
      ),
    ).toEqual({
      alarma_id: 'a1',
      titulo: 'Reunión',
      tipo: 'reunion',
      hora: '10:00',
      repeticion: { freq: 'WEEKLY', byday: ['MO', 'WE'] },
    })
  })

  it('mensual día del mes, incluido 31 y -1', () => {
    expect(
      formToPayload(
        form({ titulo: 'Cierre', freq: 'MONTHLY', hora: '09:00', bymonthday: '31' }),
        'm1',
      ).repeticion,
    ).toEqual({ freq: 'MONTHLY', bymonthday: 31 })
    expect(
      formToPayload(
        form({ titulo: 'Fin de mes', freq: 'MONTHLY', hora: '20:00', bymonthday: '-1' }),
        'm2',
      ).repeticion,
    ).toEqual({ freq: 'MONTHLY', bymonthday: -1 })
  })

  it('mensual por ordinal 1MO y -1FR con interval', () => {
    expect(
      formToPayload(
        form({
          titulo: 'Primer lunes',
          freq: 'MONTHLY',
          hora: '09:00',
          monthlyMode: 'byday',
          ordinal: '1',
          ordinalDay: 'MO',
        }),
        'm3',
      ).repeticion,
    ).toEqual({ freq: 'MONTHLY', byday: ['1MO'] })
    expect(
      formToPayload(
        form({
          titulo: 'Último viernes',
          freq: 'MONTHLY',
          hora: '18:00',
          interval: '2',
          monthlyMode: 'byday',
          ordinal: '-1',
          ordinalDay: 'FR',
          limit: 'count',
          count: '3',
        }),
        'm4',
      ).repeticion,
    ).toEqual({ freq: 'MONTHLY', interval: 2, byday: ['-1FR'], count: 3 })
  })

  it('diaria con until no incluye count', () => {
    expect(
      formToPayload(
        form({
          titulo: 'Vitaminas',
          freq: 'DAILY',
          hora: '08:00',
          limit: 'until',
          until: '2026-12-31',
        }),
        'a1',
      ).repeticion,
    ).toEqual({ freq: 'DAILY', until: '2026-12-31' })
  })

  it('"cada 8 h por 5 días" se guarda como count 15', () => {
    expect(
      formToPayload(
        form({ titulo: 'Amoxicilina', freq: 'HOURLY', interval: '8', limit: 'days', dias: '5' }),
        'a1',
      ).repeticion,
    ).toEqual({ freq: 'HOURLY', interval: 8, count: 15 })
  })

  it('por días redondea hacia arriba si el intervalo no divide al día', () => {
    expect(dosesForDays('2', '5')).toBe(10)
  })

  it('editar un HOURLY cuyo count cubre días enteros lo muestra como días', () => {
    const row: AlarmaActiva = {
      alarma_id: 'h1',
      titulo: 'Amoxicilina',
      tipo: 'otro',
      hora: '08:00',
      repeticion: { freq: 'HOURLY', interval: 8, count: 15 },
      proximo_disparo: '2026-11-02T12:00:00Z',
    }
    const state = activaToForm(row, undefined, TZ)
    expect(state).toMatchObject({ limit: 'days', dias: '5', interval: '8' })
    expect(formToPayload(state, 'h1').repeticion).toEqual(row.repeticion)
    const odd = activaToForm(
      { ...row, repeticion: { freq: 'HOURLY', interval: 8, count: 4 } },
      undefined,
      TZ,
    )
    expect(odd).toMatchObject({ limit: 'count', count: '4' })
  })

  it('editar una alarma que no es remedio rellena el formulario desde P1 y vuelve a emitir el estado completo', () => {
    const row: AlarmaActiva = {
      alarma_id: 'm-edit',
      titulo: 'Primer lunes',
      tipo: 'otro',
      hora: '09:00',
      repeticion: { freq: 'MONTHLY', interval: 1, byday: ['1MO'] },
      proximo_disparo: '2026-11-02T13:00:00Z',
    }
    const state = activaToForm(row, undefined, TZ)
    state.titulo = 'Primer lunes del mes'
    expect(formToPayload(state, row.alarma_id)).toEqual({
      alarma_id: 'm-edit',
      titulo: 'Primer lunes del mes',
      tipo: 'otro',
      hora: '09:00',
      // Fuera de un remedio la web reancla en la fecha local de `proximo_disparo` (SPEC §8).
      fecha: '2026-11-02',
      repeticion: { freq: 'MONTHLY', interval: 1, byday: ['1MO'] },
    })
  })

  it('editar una puntual recupera su fecha de inicio', () => {
    const row: AlarmaActiva = {
      alarma_id: 'p1',
      titulo: 'Revisar facturación con Lucas',
      tipo: 'reunion',
      hora: '16:00',
      repeticion: null,
      proximo_disparo: '2026-11-14T20:00:00Z',
    }
    const state = activaToForm(row, undefined, TZ)
    expect(state.fecha).toBe('2026-11-14')
    expect(formToPayload(state, row.alarma_id)).toMatchObject({ fecha: '2026-11-14' })
  })

  it('editar una serie la reancla en el próximo disparo y su count pasa a restantes', () => {
    const row: AlarmaActiva = {
      alarma_id: 's1',
      titulo: 'Vitaminas',
      tipo: 'otro',
      hora: '08:00',
      repeticion: { freq: 'DAILY', count: 15 },
      proximo_disparo: '2026-11-02T12:00:00Z',
      restantes: 8,
    }
    const state = activaToForm(row, undefined, TZ)
    expect(state.fecha).toBe('2026-11-02')
    expect(state.limit).toBe('count')
    expect(state.count).toBe('8')
    expect(formToPayload(state, row.alarma_id)).toMatchObject({
      fecha: '2026-11-02',
      repeticion: { freq: 'DAILY', count: 8 },
    })
  })

  it('sin `restantes` el count de la serie no cambia', () => {
    const row: AlarmaActiva = {
      alarma_id: 's2',
      titulo: 'Vitaminas',
      tipo: 'otro',
      hora: '08:00',
      repeticion: { freq: 'DAILY', count: 15 },
      proximo_disparo: '2026-11-02T12:00:00Z',
    }
    expect(activaToForm(row, undefined, TZ).count).toBe('15')
  })

  it('editar un HOURLY ancla la hora en el próximo disparo, no en la hora original', () => {
    // 08:00 cada 8 h: el próximo disparo es a las 16:00 de hoy (12:00Z → 16:00 local).
    const row: AlarmaActiva = {
      alarma_id: 'h2',
      titulo: 'Amoxicilina',
      tipo: 'otro',
      hora: '08:00',
      repeticion: { freq: 'HOURLY', interval: 8, count: 15 },
      proximo_disparo: '2026-11-02T20:00:00Z',
      restantes: 8,
    }
    const state = activaToForm(row, undefined, TZ)
    expect(state.hora).toBe('16:00')
    expect(state.fecha).toBe('2026-11-02')
    expect(state.count).toBe('8')
    expect(formToPayload(state, row.alarma_id)).toMatchObject({
      hora: '16:00',
      fecha: '2026-11-02',
      repeticion: { freq: 'HOURLY', interval: 8, count: 8 },
    })
  })

  it('editar un HOURLY que dispara a medianoche no salta esa dosis', () => {
    // 08:00 cada 8 h: el próximo disparo es a las 00:00 de mañana (04:00Z → 00:00 local).
    const row: AlarmaActiva = {
      alarma_id: 'h3',
      titulo: 'Amoxicilina',
      tipo: 'otro',
      hora: '08:00',
      repeticion: { freq: 'HOURLY', interval: 8, count: 15 },
      proximo_disparo: '2026-11-03T04:00:00Z',
      restantes: 8,
    }
    const state = activaToForm(row, undefined, TZ)
    expect(state.hora).toBe('00:00')
    expect(state.fecha).toBe('2026-11-03')
    expect(formToPayload(state, row.alarma_id)).toMatchObject({
      hora: '00:00',
      fecha: '2026-11-03',
      repeticion: { freq: 'HOURLY', interval: 8, count: 8 },
    })
  })

  it('editar un DAILY no cambia la hora original', () => {
    const row: AlarmaActiva = {
      alarma_id: 's3',
      titulo: 'Vitaminas',
      tipo: 'otro',
      hora: '08:00',
      repeticion: { freq: 'DAILY', count: 15 },
      proximo_disparo: '2026-11-02T20:00:00Z',
      restantes: 8,
    }
    expect(activaToForm(row, undefined, TZ).hora).toBe('08:00')
  })
})

describe('alarma_desactivada', () => {
  it('Eliminar es motivo usuario, no un borrado', () => {
    expect(desactivadaPayload('a1')).toEqual({ alarma_id: 'a1', motivo: 'usuario' })
  })
})

describe('cauce.alarmas v1', () => {
  const alarmas = [
    {
      titulo: 'Amoxicilina Juan',
      tipo: 'remedio' as const,
      hora: '08:00',
      repeticion: { freq: 'HOURLY' as const, interval: 8, count: 15 },
      destinatario: 'Juan',
      remedio: 'Amoxicilina',
    },
    {
      titulo: 'Reunión de equipo',
      tipo: 'reunion' as const,
      hora: '10:00',
      repeticion: { freq: 'WEEKLY' as const, byday: ['MO', 'WE'] },
    },
    {
      titulo: 'Cierre',
      tipo: 'otro' as const,
      hora: '09:00',
      repeticion: { freq: 'MONTHLY' as const, bymonthday: -1 },
    },
  ]

  it('exportar e importar vuelve a los mismos ítems', () => {
    const parsed = parseBulk(exportBulk(alarmas))
    expect(parsed).toEqual({ ok: true, alarmas })
  })

  it('rechaza un formato distinto sin producir ítems', () => {
    expect(parseBulk('{"formato":"otro","version":1,"alarmas":[]}')).toEqual({
      ok: false,
      error: 'formato debe ser cauce.alarmas',
    })
  })

  it('acepta y emite la fecha de inicio opcional', () => {
    const conFecha = [
      {
        titulo: 'Cierre',
        tipo: 'otro' as const,
        hora: '09:00',
        fecha: '2026-12-31',
        repeticion: null,
      },
    ]
    expect(parseBulk(exportBulk(conFecha))).toEqual({ ok: true, alarmas: conFecha })
  })

  it('rechaza una fecha de inicio inválida', () => {
    const parsed = parseBulk(
      JSON.stringify({
        formato: 'cauce.alarmas',
        version: 1,
        alarmas: [
          { titulo: 'Cierre', tipo: 'otro', hora: '09:00', fecha: '31-12-2026', repeticion: null },
        ],
      }),
    )
    expect(parsed).toEqual({ ok: false, error: 'Ítem 1: fecha inválida' })
  })

  it('arma la exportación desde P1 y completa remedio desde P2', () => {
    const rows: AlarmaActiva[] = [
      {
        alarma_id: 'r1',
        titulo: 'Amoxicilina Juan',
        tipo: 'remedio',
        hora: '08:00',
        repeticion: { freq: 'HOURLY', interval: 8, count: 15 },
        proximo_disparo: '2026-10-20T12:00:00Z',
        restantes: 8,
      },
    ]
    expect(
      rowsToBulk(
        rows,
        [
          {
            alarma_id: 'r1',
            destinatario: 'Juan',
            remedio: 'Amoxicilina',
          },
        ],
        TZ,
      ),
    ).toEqual([
      {
        titulo: 'Amoxicilina Juan',
        tipo: 'remedio',
        hora: '08:00',
        fecha: '2026-10-20',
        repeticion: { freq: 'HOURLY', interval: 8, count: 8 },
        destinatario: 'Juan',
        remedio: 'Amoxicilina',
      },
    ])
  })

  it('la exportación no incluye las reuniones de Google', () => {
    const rows: AlarmaActiva[] = [
      {
        alarma_id: 'gcal:r1',
        titulo: 'Reunión de equipo',
        tipo: 'reunion',
        hora: '09:30',
        repeticion: null,
        proximo_disparo: '2026-11-02T13:30:00Z',
      },
    ]
    expect(rowsToBulk(rows, [], TZ)).toEqual([])
  })

  it('la exportación ancla un HOURLY en la hora del próximo disparo', () => {
    // 08:00 cada 8 h: el próximo disparo es a las 16:00 de hoy (20:00Z → 16:00 local).
    const rows: AlarmaActiva[] = [
      {
        alarma_id: 'r2',
        titulo: 'Amoxicilina',
        tipo: 'otro',
        hora: '08:00',
        repeticion: { freq: 'HOURLY', interval: 8, count: 15 },
        proximo_disparo: '2026-11-02T20:00:00Z',
        restantes: 8,
      },
    ]
    expect(rowsToBulk(rows, [], TZ)).toEqual([
      {
        titulo: 'Amoxicilina',
        tipo: 'otro',
        hora: '16:00',
        fecha: '2026-11-02',
        repeticion: { freq: 'HOURLY', interval: 8, count: 8 },
      },
    ])
  })

  it('la exportación de un HOURLY que dispara a medianoche no salta esa dosis', () => {
    // 08:00 cada 8 h: el próximo disparo es a las 00:00 de mañana (04:00Z → 00:00 local).
    const rows: AlarmaActiva[] = [
      {
        alarma_id: 'r3',
        titulo: 'Amoxicilina',
        tipo: 'otro',
        hora: '08:00',
        repeticion: { freq: 'HOURLY', interval: 8, count: 15 },
        proximo_disparo: '2026-11-03T04:00:00Z',
        restantes: 8,
      },
    ]
    expect(rowsToBulk(rows, [], TZ)).toEqual([
      {
        titulo: 'Amoxicilina',
        tipo: 'otro',
        hora: '00:00',
        fecha: '2026-11-03',
        repeticion: { freq: 'HOURLY', interval: 8, count: 8 },
      },
    ])
  })

  it('la exportación de una serie que no es HOURLY conserva la hora original', () => {
    const rows: AlarmaActiva[] = [
      {
        alarma_id: 'r4',
        titulo: 'Vitaminas',
        tipo: 'otro',
        hora: '08:00',
        repeticion: { freq: 'DAILY', count: 15 },
        proximo_disparo: '2026-11-02T20:00:00Z',
        restantes: 8,
      },
    ]
    expect(rowsToBulk(rows, [], TZ)).toEqual([
      {
        titulo: 'Vitaminas',
        tipo: 'otro',
        hora: '08:00',
        fecha: '2026-11-02',
        repeticion: { freq: 'DAILY', count: 8 },
      },
    ])
  })
})

describe('corrección de un remedio (SPEC 2.2)', () => {
  // Remedio cada 8 h × 15 a mitad de tratamiento: P1 trae su `fecha` original y `restantes`.
  const conFecha: AlarmaActiva = {
    alarma_id: 'c1',
    titulo: 'Kalmx Bruno',
    tipo: 'remedio',
    hora: '08:00',
    fecha: '2026-11-11',
    repeticion: { freq: 'HOURLY', interval: 8, count: 15 },
    proximo_disparo: '2026-11-13T12:00:00Z',
    restantes: 9,
  }
  const sinFecha: AlarmaActiva = {
    alarma_id: 's1',
    titulo: 'Dorvex Elsa',
    tipo: 'remedio',
    hora: '06:00',
    repeticion: { freq: 'HOURLY', interval: 8, count: 15 },
    proximo_disparo: '2026-11-13T18:00:00Z',
    restantes: 8,
  }

  it('solo un remedio propio bloquea el calendario', () => {
    expect(isScheduleLocked(conFecha)).toBe(true)
    expect(isScheduleLocked({ ...conFecha, tipo: 'otro' })).toBe(false)
    expect(isScheduleLocked({ ...conFecha, tipo: 'reunion' })).toBe(false)
  })

  it('el formulario muestra el calendario tal como está, sin reanclar', () => {
    const form = activaToForm(conFecha, { destinatario: 'Brno', remedio: 'Kalmx' }, TZ)
    expect(form.fecha).toBe('2026-11-11')
    expect(form.hora).toBe('08:00')
    // count 15 × 8 h = 5 días: el original, no `restantes`.
    expect(form.limit).toBe('days')
    expect(form.dias).toBe('5')
    expect(activaToForm(sinFecha, undefined, TZ).fecha).toBe('')
  })

  it('el evento reenvía fecha, hora y repetición de la fila y corrige solo los textos', () => {
    const form = activaToForm(conFecha, { destinatario: 'Brno', remedio: 'Kalmx' }, TZ)
    // Aunque alguien toque el calendario del estado del formulario, no viaja.
    form.hora = '10:00'
    form.fecha = '2026-12-01'
    form.dias = '2'
    form.titulo = ' Kalmix Bruno '
    form.destinatario = 'Bruno'
    form.remedio = 'Kalmix'
    expect(remedioCorrectionPayload(conFecha, form)).toEqual({
      alarma_id: 'c1',
      titulo: 'Kalmix Bruno',
      tipo: 'remedio',
      hora: '08:00',
      fecha: '2026-11-11',
      repeticion: { freq: 'HOURLY', interval: 8, count: 15 },
      destinatario: 'Bruno',
      remedio: 'Kalmix',
    })
  })

  it('sin fecha en la fila, el evento no lleva fecha', () => {
    const form = activaToForm(sinFecha, { destinatario: 'Elza', remedio: 'Dorvex' }, TZ)
    form.destinatario = 'Elsa'
    const payload = remedioCorrectionPayload(sinFecha, form)
    expect(payload).not.toHaveProperty('fecha')
    expect(payload).toMatchObject({ hora: '06:00', repeticion: { count: 15 } })
  })

  it('una fecha de inicio pasada no frena la corrección', () => {
    const vieja: AlarmaActiva = {
      ...conFecha,
      fecha: '2025-01-10',
      repeticion: { freq: 'DAILY', until: '2025-06-01' },
    }
    const form = activaToForm(vieja, { destinatario: 'Bruno', remedio: 'Kalmix' }, TZ)
    expect(validateForm(form)).not.toBeNull()
    expect(validateCorrection(form)).toBeNull()
    expect(remedioCorrectionPayload(vieja, form)).toMatchObject({
      fecha: '2025-01-10',
      repeticion: { freq: 'DAILY', until: '2025-06-01' },
    })
  })

  it('los textos siguen validándose', () => {
    const form = activaToForm(conFecha, { destinatario: '', remedio: 'Kalmix' }, TZ)
    expect(validateCorrection(form)).toMatch(/^El destinatario/)
    expect(() => remedioCorrectionPayload(conFecha, form)).toThrow(/destinatario/)
    expect(() => remedioCorrectionPayload({ ...conFecha, tipo: 'otro' }, form)).toThrow()
  })
})
