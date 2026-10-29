import { describe, expect, it } from 'vitest'
import {
  activaToForm,
  desactivadaPayload,
  dosesForDays,
  emptyForm,
  exportBulk,
  formToPayload,
  parseBulk,
  rowsToBulk,
  type AlarmaFormState,
} from './model'
import type { AlarmaActiva } from './types'

function form(partial: Partial<AlarmaFormState> = {}): AlarmaFormState {
  return { ...emptyForm(), ...partial }
}

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
    const state = activaToForm(row)
    expect(state).toMatchObject({ limit: 'days', dias: '5', interval: '8' })
    expect(formToPayload(state, 'h1').repeticion).toEqual(row.repeticion)
    const odd = activaToForm({ ...row, repeticion: { freq: 'HOURLY', interval: 8, count: 4 } })
    expect(odd).toMatchObject({ limit: 'count', count: '4' })
  })

  it('editar rellena el formulario desde P1 y P2 y vuelve a emitir el estado completo', () => {
    const row: AlarmaActiva = {
      alarma_id: 'm-edit',
      titulo: 'Primer lunes',
      tipo: 'remedio',
      hora: '09:00',
      repeticion: { freq: 'MONTHLY', interval: 1, byday: ['1MO'] },
      proximo_disparo: '2026-11-02T13:00:00Z',
    }
    const state = activaToForm(row, { destinatario: 'Ana', remedio: 'Ibuprofeno' })
    state.titulo = 'Primer lunes de Ana'
    expect(formToPayload(state, row.alarma_id)).toEqual({
      alarma_id: 'm-edit',
      titulo: 'Primer lunes de Ana',
      tipo: 'remedio',
      hora: '09:00',
      repeticion: { freq: 'MONTHLY', interval: 1, byday: ['1MO'] },
      destinatario: 'Ana',
      remedio: 'Ibuprofeno',
    })
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

  it('arma la exportación desde P1 y completa remedio desde P2', () => {
    const rows: AlarmaActiva[] = [
      {
        alarma_id: 'r1',
        titulo: 'Amoxicilina Juan',
        tipo: 'remedio',
        hora: '08:00',
        repeticion: { freq: 'HOURLY', interval: 8, count: 15 },
        proximo_disparo: '2026-10-20T12:00:00Z',
      },
    ]
    expect(
      rowsToBulk(rows, [
        {
          alarma_id: 'r1',
          destinatario: 'Juan',
          remedio: 'Amoxicilina',
        },
      ]),
    ).toEqual([
      {
        titulo: 'Amoxicilina Juan',
        tipo: 'remedio',
        hora: '08:00',
        repeticion: { freq: 'HOURLY', interval: 8, count: 15 },
        destinatario: 'Juan',
        remedio: 'Amoxicilina',
      },
    ])
  })
})
