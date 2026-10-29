import { describe, expect, it } from 'vitest'
import { exportIcs, importIcs, type IcsSource } from './ics'

const tz = 'America/La_Paz'
const now = '2026-10-23T15:00:00Z'

function source(partial: IcsSource): IcsSource {
  return partial
}

describe('ics', () => {
  it('ida y vuelta de diaria, semanal, mensual y una vez', () => {
    const alarmas: IcsSource[] = [
      source({
        alarma_id: 'a1',
        titulo: 'Vitaminas, noche',
        tipo: 'otro',
        hora: '08:00',
        repeticion: { freq: 'DAILY', interval: 2, until: '2026-12-31' },
        proximo_disparo: '2026-10-24T12:00:00Z',
        fecha: '2026-10-24',
      }),
      source({
        alarma_id: 'a2',
        titulo: 'Reunión de equipo',
        tipo: 'reunion',
        hora: '10:00',
        repeticion: { freq: 'WEEKLY', byday: ['MO', 'WE'] },
        proximo_disparo: '2026-10-26T14:00:00Z',
        fecha: '2026-10-26',
      }),
      source({
        alarma_id: 'a3',
        titulo: 'Último día',
        tipo: 'otro',
        hora: '09:00',
        repeticion: { freq: 'MONTHLY', bymonthday: -1 },
        proximo_disparo: '2026-10-31T13:00:00Z',
        fecha: '2026-10-31',
      }),
      source({
        alarma_id: 'a4',
        titulo: 'Primer lunes',
        tipo: 'otro',
        hora: '09:00',
        repeticion: { freq: 'MONTHLY', byday: ['1MO'], count: 3 },
        proximo_disparo: '2026-11-02T13:00:00Z',
        restantes: 3,
        fecha: '2026-11-02',
      }),
      source({
        alarma_id: 'a5',
        titulo: 'Una vez',
        tipo: 'otro',
        hora: '07:30',
        repeticion: null,
        proximo_disparo: '2026-10-24T11:30:00Z',
        fecha: '2026-10-24',
      }),
      source({
        alarma_id: 'a6',
        titulo: 'Amoxicilina Juan',
        tipo: 'remedio',
        hora: '08:00',
        repeticion: { freq: 'DAILY' },
        destinatario: 'Juan',
        remedio: 'Amoxicilina',
        proximo_disparo: '2026-10-24T12:00:00Z',
        fecha: '2026-10-24',
      }),
    ]
    const text = exportIcs(alarmas, { tz, now })
    expect(text).toContain('RRULE:FREQ=MONTHLY;BYDAY=1MO;COUNT=3')
    expect(text).toContain('RRULE:FREQ=MONTHLY;BYMONTHDAY=-1')
    expect(text).toContain('UNTIL=20270101T035959Z')
    expect(text).toContain('BEGIN:VALARM')
    expect(text).toContain('TRIGGER:PT0M')
    expect(text).toContain('X-CAUCE-DESTINATARIO:Juan')
    expect(text).toContain('X-CAUCE-REMEDIO:Amoxicilina')
    expect(text).not.toContain('BYSETPOS')
    expect(text).toContain('DTSTART;TZID=America/La_Paz:20261026T100000')
    const parsed = importIcs(text, { tz })
    expect(parsed.errors).toEqual([])
    expect(parsed.alarmas).toEqual(
      alarmas.map(({ titulo, tipo, hora, repeticion, destinatario, remedio, fecha }) => ({
        titulo,
        tipo,
        hora,
        // La fecha local de DTSTART viaja como `fecha` (alarma_creada.v3).
        fecha,
        repeticion,
        ...(destinatario ? { destinatario } : {}),
        ...(remedio ? { remedio } : {}),
      })),
    )
  })

  it('traduce BYDAY+BYSETPOS de Apple/Outlook y acepta HOURLY', () => {
    const text = [
      'BEGIN:VCALENDAR',
      'VERSION:2.0',
      'BEGIN:VEVENT',
      'SUMMARY:Último viernes',
      'DTSTART;TZID=America/La_Paz:20261127T180000',
      'RRULE:FREQ=MONTHLY;BYDAY=FR;BYSETPOS=-1',
      'CATEGORIES:otro',
      'END:VEVENT',
      'BEGIN:VEVENT',
      'SUMMARY:Cada 8h',
      'DTSTART:20261020T080000Z',
      'RRULE:FREQ=HOURLY;INTERVAL=8;COUNT=15',
      'END:VEVENT',
      'BEGIN:VEVENT',
      'SUMMARY:Semanal Google',
      'DTSTART;TZID=America/La_Paz:20261026T100000',
      'RRULE:FREQ=WEEKLY;WKST=SU;BYDAY=MO,WE',
      'END:VEVENT',
      'END:VCALENDAR',
    ].join('\r\n')
    const parsed = importIcs(text, { tz })
    expect(parsed.errors).toEqual([])
    expect(parsed.alarmas.map((alarma) => alarma.repeticion)).toEqual([
      { freq: 'MONTHLY', byday: ['-1FR'] },
      { freq: 'HOURLY', interval: 8, count: 15 },
      { freq: 'WEEKLY', byday: ['MO', 'WE'] },
    ])
    expect(parsed.alarmas[1]?.hora).toBe('04:00')
  })

  it('un evento inválido no frena al resto', () => {
    const text = [
      'BEGIN:VCALENDAR',
      'BEGIN:VEVENT',
      'SUMMARY:Anual',
      'DTSTART:20261023T080000',
      'RRULE:FREQ=YEARLY',
      'END:VEVENT',
      'BEGIN:VEVENT',
      'SUMMARY:Con excepción',
      'DTSTART:20261023T080000',
      'RRULE:FREQ=DAILY',
      'EXDATE:20261024T080000',
      'END:VEVENT',
      'BEGIN:VEVENT',
      'SUMMARY:Dos días de mes',
      'DTSTART:20261023T080000',
      'RRULE:FREQ=MONTHLY;BYMONTHDAY=1,15',
      'END:VEVENT',
      'BEGIN:VEVENT',
      'SUMMARY:Count y until',
      'DTSTART:20261023T080000',
      'RRULE:FREQ=DAILY;COUNT=3;UNTIL=20261231T000000Z',
      'END:VEVENT',
      'BEGIN:VEVENT',
      'SUMMARY:Válida',
      'DTSTART:20261023T093000',
      'RRULE:FREQ=DAILY;INTERVAL=1',
      'END:VEVENT',
      'END:VCALENDAR',
    ].join('\n')
    const parsed = importIcs(text, { tz })
    expect(parsed.alarmas).toEqual([
      {
        titulo: 'Válida',
        tipo: 'otro',
        hora: '09:30',
        fecha: '2026-10-23',
        repeticion: { freq: 'DAILY', interval: 1 },
      },
    ])
    expect(parsed.errors.map((error) => error.index)).toEqual([1, 2, 3, 4])
  })

  it('la fecha local de DTSTART viaja como `fecha`', () => {
    const text = [
      'BEGIN:VCALENDAR',
      'BEGIN:VEVENT',
      'SUMMARY:Revisar facturación',
      'DTSTART:20261114T160000',
      'CATEGORIES:reunion',
      'END:VEVENT',
      'BEGIN:VEVENT',
      'SUMMARY:En UTC',
      'DTSTART:20261114T200000Z',
      'CATEGORIES:otro',
      'END:VEVENT',
      'END:VCALENDAR',
    ].join('\n')
    const parsed = importIcs(text, { tz })
    expect(parsed.errors).toEqual([])
    expect(parsed.alarmas.map((alarma) => [alarma.hora, alarma.fecha])).toEqual([
      ['16:00', '2026-11-14'],
      ['16:00', '2026-11-14'],
    ])
  })

  it('usa COUNT=restantes para una alarma con count a medio consumir', () => {
    const text = exportIcs(
      [
        source({
          alarma_id: 'c1',
          titulo: 'Amoxicilina Juan',
          tipo: 'remedio',
          hora: '08:00',
          repeticion: { freq: 'DAILY', count: 15 },
          destinatario: 'Juan',
          remedio: 'Amoxicilina',
          proximo_disparo: '2026-10-22T12:00:00Z',
          restantes: 8,
        }),
      ],
      { tz, now },
    )
    expect(text).toContain('RRULE:FREQ=DAILY;COUNT=8')
    expect(text).not.toContain('COUNT=15')
  })

  it('HOURLY que divide a 24 se parte en VEVENTs diarios y reparte COUNT', () => {
    const text = exportIcs(
      [
        source({
          alarma_id: 'h1',
          titulo: 'Cada 8h',
          tipo: 'remedio',
          hora: '08:00',
          repeticion: { freq: 'HOURLY', interval: 8, count: 15 },
          destinatario: 'Juan',
          remedio: 'Amoxicilina',
          proximo_disparo: '2026-10-20T12:00:00Z',
          restantes: 15,
        }),
      ],
      { tz, now },
    )
    expect(text).not.toContain('HOURLY')
    expect(text).not.toContain('BYSETPOS')
    const parsed = importIcs(text, { tz })
    expect(parsed.errors).toEqual([])
    expect(parsed.alarmas.map((alarma) => alarma.hora)).toEqual(['08:00', '16:00', '00:00'])
    expect(
      parsed.alarmas.every(
        (alarma) => alarma.repeticion?.freq === 'DAILY' && alarma.repeticion.count === 5,
      ),
    ).toBe(true)
    expect(
      parsed.alarmas.every(
        (alarma) => alarma.destinatario === 'Juan' && alarma.remedio === 'Amoxicilina',
      ),
    ).toBe(true)
  })

  it('COUNT no divisible se reparte en los primeros horarios', () => {
    const text = exportIcs(
      [
        source({
          alarma_id: 'h2',
          titulo: 'Dos tomas',
          tipo: 'otro',
          hora: '08:00',
          repeticion: { freq: 'HOURLY', interval: 8, count: 2 },
          proximo_disparo: '2026-10-20T12:00:00Z',
          restantes: 2,
        }),
      ],
      { tz, now },
    )
    const parsed = importIcs(text, { tz })
    expect(parsed.alarmas.map((alarma) => [alarma.hora, alarma.repeticion?.count])).toEqual([
      ['08:00', 1],
      ['16:00', 1],
    ])
  })

  it('HOURLY que no divide a 24, sin fin, exporta 30 días sin RRULE', () => {
    const text = exportIcs(
      [
        source({
          alarma_id: 'h3',
          titulo: 'Cada 7h',
          tipo: 'otro',
          hora: '08:00',
          repeticion: { freq: 'HOURLY', interval: 7 },
          proximo_disparo: '2026-10-23T12:00:00Z',
        }),
      ],
      { tz, now },
    )
    expect(text).not.toContain('RRULE')
    const parsed = importIcs(text, { tz })
    expect(parsed.errors).toEqual([])
    expect(parsed.alarmas).toHaveLength(103)
    expect(parsed.alarmas[0]?.hora).toBe('08:00')
    expect(parsed.alarmas.every((alarma) => alarma.repeticion === null)).toBe(true)
  })

  it('HOURLY arranca en la hora local de proximo_disparo, no en `hora`', () => {
    // probe: interval 5, hora 08:00, próximo disparo 10:00.
    // Exportar `hora` daba 08:00, 13:00, 18:00, 23:00: horarios que no suenan.
    const text = exportIcs(
      [
        source({
          alarma_id: 'h4',
          titulo: 'Cada 5h',
          tipo: 'otro',
          hora: '08:00',
          repeticion: { freq: 'HOURLY', interval: 5, count: 4 },
          proximo_disparo: '2026-10-23T14:00:00Z',
          restantes: 4,
        }),
      ],
      { tz, now },
    )
    expect(text).toContain('DTSTART;TZID=America/La_Paz:20261023T100000')
    expect(text).not.toContain('20261023T080000')
    const parsed = importIcs(text, { tz })
    expect(parsed.errors).toEqual([])
    expect(parsed.alarmas.map((alarma) => alarma.hora)).toEqual([
      '10:00',
      '15:00',
      '20:00',
      '01:00',
    ])
  })

  it('traduce el TZID de Windows de Outlook a IANA', () => {
    const text = [
      'BEGIN:VCALENDAR',
      'BEGIN:VEVENT',
      'SUMMARY:Reunión en Paris',
      'DTSTART;TZID=Romance Standard Time:20261026T100000',
      'CATEGORIES:reunion',
      'END:VEVENT',
      'BEGIN:VEVENT',
      'SUMMARY:En La Paz',
      'DTSTART;TZID=SA Western Standard Time:20261026T080000',
      'CATEGORIES:otro',
      'END:VEVENT',
      'END:VCALENDAR',
    ].join('\r\n')
    const parsed = importIcs(text, { tz })
    expect(parsed.errors).toEqual([])
    // Paris (UTC+1 en esa fecha) 10:00 → 05:00 en La Paz (UTC-4); La Paz 08:00 → 08:00.
    expect(parsed.alarmas.map((alarma) => alarma.hora)).toEqual(['05:00', '08:00'])
  })

  it('un TZID desconocido da error solo para ese evento', () => {
    const text = [
      'BEGIN:VCALENDAR',
      'BEGIN:VEVENT',
      'SUMMARY:Zona rara',
      'DTSTART;TZID=Mars Standard Time:20261026T100000',
      'END:VEVENT',
      'BEGIN:VEVENT',
      'SUMMARY:Válida',
      'DTSTART:20261023T093000',
      'RRULE:FREQ=DAILY;INTERVAL=1',
      'END:VEVENT',
      'END:VCALENDAR',
    ].join('\n')
    const parsed = importIcs(text, { tz })
    expect(parsed.alarmas).toEqual([
      {
        titulo: 'Válida',
        tipo: 'otro',
        hora: '09:30',
        fecha: '2026-10-23',
        repeticion: { freq: 'DAILY', interval: 1 },
      },
    ])
    expect(parsed.errors).toHaveLength(1)
    expect(parsed.errors[0]?.index).toBe(1)
    expect(parsed.errors[0]?.message).toContain('TZID desconocido')
    expect(parsed.errors[0]?.summary).toBe('Zona rara')
  })

  it('lee destinatario desde DESCRIPTION si faltan las X-', () => {
    const text = [
      'BEGIN:VCALENDAR',
      'BEGIN:VEVENT',
      'SUMMARY:Toma',
      'DTSTART:20261023T080000',
      'CATEGORIES:remedio',
      'DESCRIPTION:Destinatario: Juan\\nRemedio: Amoxicilina',
      'END:VEVENT',
      'END:VCALENDAR',
    ].join('\n')
    expect(importIcs(text, { tz }).alarmas).toEqual([
      {
        titulo: 'Toma',
        tipo: 'remedio',
        hora: '08:00',
        fecha: '2026-10-23',
        repeticion: null,
        destinatario: 'Juan',
        remedio: 'Amoxicilina',
      },
    ])
  })
})
