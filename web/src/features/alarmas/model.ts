import { browserTimeZone } from '../../core/time'
import { formatByday } from './format'
import type {
  AlarmaActiva,
  AlarmaPayload,
  BulkAlarma,
  Freq,
  Repeticion,
  TipoAlarma,
  Weekday,
} from './types'

export const WEEKDAYS = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU'] as const

export const WEEKDAY_LABEL: Record<Weekday, string> = {
  MO: 'Lunes',
  TU: 'Martes',
  WE: 'Miércoles',
  TH: 'Jueves',
  FR: 'Viernes',
  SA: 'Sábado',
  SU: 'Domingo',
}

export const V_ALARMA_CREADA = 3
export const V_ALARMA_MODIFICADA = 3
export const V_ALARMA_DESACTIVADA = 1

/** Prefijo de las entradas de P1 que vienen de Google Calendar (`calendario_google`, 2.10). */
const GCAL_PREFIX = 'gcal:'

/** Las reuniones de Google son de solo lectura: no se editan ni se desactivan desde `alarmas`. */
export function isGoogleCalendar(alarmaId: string): boolean {
  return alarmaId.startsWith(GCAL_PREFIX)
}

export type FreqForm = 'una_vez' | Freq
export type MonthlyMode = 'bymonthday' | 'byday'
/** `days` solo existe en HOURLY: se guarda como `count` ("cada 8 h por 5 días" = 15). */
export type LimitMode = 'none' | 'count' | 'until' | 'days'

/** Intervalos típicos de un remedio, en horas. */
export const HOURLY_PRESETS = ['4', '6', '8', '12'] as const

export interface AlarmaFormState {
  titulo: string
  tipo: TipoAlarma
  hora: string
  /** Fecha local del primer disparo (AAAA-MM-DD). Vacía = "desde ahora". */
  fecha: string
  freq: FreqForm
  interval: string
  limit: LimitMode
  count: string
  until: string
  dias: string
  weekdays: Weekday[]
  monthlyMode: MonthlyMode
  bymonthday: string
  ordinal: string
  ordinalDay: Weekday
  destinatario: string
  remedio: string
}

const HORA_RE = /^([01][0-9]|2[0-3]):[0-5][0-9]$/
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/
const WEEKDAY_RE = /^(MO|TU|WE|TH|FR|SA|SU)$/
const ORDINAL_RE = /^(-1|[1-5])(MO|TU|WE|TH|FR|SA|SU)$/

export function emptyForm(): AlarmaFormState {
  return {
    titulo: '',
    tipo: 'otro',
    hora: '08:00',
    fecha: '',
    freq: 'una_vez',
    interval: '',
    limit: 'none',
    count: '',
    until: '',
    dias: '',
    weekdays: [],
    monthlyMode: 'bymonthday',
    bymonthday: '1',
    ordinal: '1',
    ordinalDay: 'MO',
    destinatario: '',
    remedio: '',
  }
}

export function isTipo(value: string): value is TipoAlarma {
  return value === 'remedio' || value === 'reunion' || value === 'otro'
}

export function isFreq(value: string): value is Freq {
  return value === 'DAILY' || value === 'WEEKLY' || value === 'MONTHLY' || value === 'HOURLY'
}

export function isWeekday(value: string): value is Weekday {
  return WEEKDAY_RE.test(value)
}

/**
 * Fecha local (AAAA-MM-DD) de un instante RFC 3339, en la zona de la sesión web.
 * Es la fecha del primer disparo que P1 conoce (`proximo_disparo`).
 */
export function localDate(instant: string, tz: string = browserTimeZone()): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date(instant))
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? '00'
  return `${get('year')}-${get('month')}-${get('day')}`
}

/**
 * Hora local (HH:MM) de un instante RFC 3339, en la zona de la sesión web.
 * Junto con `localDate` (misma zona) reconstruye el instante local de `proximo_disparo`.
 */
export function localTime(instant: string, tz: string = browserTimeZone()): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(instant))
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? '00'
  return `${get('hour')}:${get('minute')}`
}

export function activaToForm(
  row: AlarmaActiva,
  extra?: { destinatario?: string; remedio?: string },
  tz: string = browserTimeZone(),
): AlarmaFormState {
  const form = emptyForm()
  form.titulo = row.titulo
  form.tipo = isTipo(row.tipo) ? row.tipo : 'otro'
  form.hora = row.hora
  // P1 no trae `fecha`: la mejor fecha que conoce es la del próximo disparo. Para
  // una puntual es su fecha; para una serie la reancla en el próximo disparo.
  form.fecha = localDate(row.proximo_disparo, tz)
  form.destinatario = extra?.destinatario ?? ''
  form.remedio = extra?.remedio ?? ''
  const rep = row.repeticion
  if (!rep || !isFreq(rep.freq)) return form
  form.freq = rep.freq
  // Un HOURLY se ancla en la hora del próximo disparo, no en la hora original:
  // con `restantes` como count, anclar más temprano perdería una dosis y anclar
  // más tarde se saltaría otra. Así `fecha` + `hora` es `proximo_disparo`.
  if (rep.freq === 'HOURLY') form.hora = localTime(row.proximo_disparo, tz)
  form.interval = rep.interval !== undefined ? String(rep.interval) : ''
  // Al reanclar en el próximo disparo, la serie conserva solo los disparos que
  // faltan: `restantes` es el `count` nuevo (si no hay `count`, no hay límite).
  const count = rep.count !== undefined ? (row.restantes ?? rep.count) : undefined
  const hours = count !== undefined && rep.freq === 'HOURLY' ? count * (rep.interval ?? 1) : 0
  if (count !== undefined && hours > 0 && hours % 24 === 0) {
    form.limit = 'days'
    form.dias = String(hours / 24)
  } else if (count !== undefined) {
    form.limit = 'count'
    form.count = String(count)
  } else if (rep.until) {
    form.limit = 'until'
    form.until = rep.until
  }
  if (rep.freq === 'WEEKLY' && rep.byday) {
    form.weekdays = rep.byday.filter(isWeekday)
  }
  if (rep.freq === 'MONTHLY') {
    if (rep.bymonthday !== undefined) {
      form.monthlyMode = 'bymonthday'
      form.bymonthday = String(rep.bymonthday)
    } else {
      const token = rep.byday?.[0]
      const parsed = token ? parseOrdinal(token) : null
      if (parsed) {
        form.monthlyMode = 'byday'
        form.ordinal = String(parsed.ordinal)
        form.ordinalDay = parsed.day
      }
    }
  }
  return form
}

export function parseOrdinal(token: string): { ordinal: number; day: Weekday } | null {
  const match = ORDINAL_RE.exec(token)
  const day = match?.[2]
  const ordinal = match?.[1]
  if (!match || ordinal === undefined || !day || !isWeekday(day)) return null
  return { ordinal: Number(ordinal), day }
}

export function normalizeHora(value: string): string | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(value.trim())
  const hh = match?.[1]
  const mm = match?.[2]
  if (!hh || !mm) return null
  const hora = `${hh.padStart(2, '0')}:${mm}`
  return HORA_RE.test(hora) ? hora : null
}

function isDate(value: string): boolean {
  if (!DATE_RE.test(value)) return false
  const [year, month, day] = value.split('-').map(Number) as [number, number, number]
  const date = new Date(Date.UTC(year, month - 1, day))
  return (
    date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
  )
}

function parsePositiveInt(value: string): number | null {
  if (!/^[1-9]\d*$/.test(value.trim())) return null
  return Number(value)
}

export function validateForm(form: AlarmaFormState): string | null {
  const titulo = form.titulo.trim()
  if (titulo.length < 1 || titulo.length > 120)
    return 'El título tiene que tener entre 1 y 120 caracteres'
  if (!normalizeHora(form.hora)) return 'La hora tiene que ser HH:MM'
  if (form.fecha.trim() !== '' && !isDate(form.fecha.trim())) {
    return 'La fecha de inicio tiene que ser una fecha AAAA-MM-DD'
  }
  if (form.tipo === 'remedio') {
    const destinatario = form.destinatario.trim()
    const remedio = form.remedio.trim()
    if (destinatario.length < 1 || destinatario.length > 60) {
      return 'El destinatario tiene que tener entre 1 y 60 caracteres'
    }
    if (remedio.length < 1 || remedio.length > 120)
      return 'El remedio tiene que tener entre 1 y 120 caracteres'
  }
  if (form.freq === 'una_vez') return null
  if (form.interval.trim() !== '' && parsePositiveInt(form.interval) === null) {
    return 'El intervalo tiene que ser un entero ≥ 1'
  }
  if (form.limit === 'count' && parsePositiveInt(form.count) === null) {
    return 'La cantidad tiene que ser un entero ≥ 1'
  }
  if (form.limit === 'days' && form.freq === 'HOURLY' && parsePositiveInt(form.dias) === null) {
    return 'Los días tienen que ser un entero ≥ 1'
  }
  if (form.limit === 'until' && !isDate(form.until.trim()))
    return 'Hasta tiene que ser una fecha YYYY-MM-DD'
  if (form.freq === 'MONTHLY' && form.monthlyMode === 'bymonthday') {
    if (form.bymonthday.trim() === '-1') return null
    const day = Number(form.bymonthday)
    if (!Number.isInteger(day) || day < 1 || day > 31)
      return 'El día del mes tiene que ser 1..31 o último día'
  }
  if (form.freq === 'MONTHLY' && form.monthlyMode === 'byday') {
    if (!/^(-1|[1-5])$/.test(form.ordinal) || !isWeekday(form.ordinalDay)) {
      return 'Elegí un ordinal (1..5 o último) y un día de la semana'
    }
  }
  return null
}

/** Disparos de un HOURLY que cubren `dias` días: 8 h × 5 días = 15. Redondea hacia arriba. */
export function dosesForDays(dias: string, interval: string): number | null {
  const days = parsePositiveInt(dias)
  if (days === null) return null
  const hours = interval.trim() === '' ? 1 : parsePositiveInt(interval)
  if (hours === null) return null
  return Math.ceil((days * 24) / hours)
}

function buildRepeticion(form: AlarmaFormState): Repeticion | null {
  if (form.freq === 'una_vez') return null
  const rep: Repeticion = { freq: form.freq }
  if (form.interval.trim() !== '') {
    const interval = parsePositiveInt(form.interval)
    if (interval !== null) rep.interval = interval
  }
  if (form.limit === 'count') {
    const count = parsePositiveInt(form.count)
    if (count !== null) rep.count = count
  } else if (form.limit === 'days' && form.freq === 'HOURLY') {
    const count = dosesForDays(form.dias, form.interval)
    if (count !== null) rep.count = count
  } else if (form.limit === 'until') {
    rep.until = form.until.trim()
  }
  if (form.freq === 'WEEKLY') {
    const days = WEEKDAYS.filter((day) => form.weekdays.includes(day))
    if (days.length > 0) rep.byday = [...days]
  }
  if (form.freq === 'MONTHLY') {
    if (form.monthlyMode === 'bymonthday') {
      rep.bymonthday = Number(form.bymonthday)
    } else {
      rep.byday = [`${form.ordinal}${form.ordinalDay}`]
    }
  }
  return rep
}

export function formToPayload(form: AlarmaFormState, alarmaId: string): AlarmaPayload {
  const error = validateForm(form)
  if (error) throw new Error(error)
  const payload: AlarmaPayload = {
    alarma_id: alarmaId,
    titulo: form.titulo.trim(),
    tipo: form.tipo,
    hora: normalizeHora(form.hora) ?? form.hora,
    repeticion: buildRepeticion(form),
  }
  // Sin fecha, "desde ahora": la clave no viaja (nunca null ni "").
  if (form.fecha.trim() !== '') payload.fecha = form.fecha.trim()
  if (form.tipo === 'remedio') {
    payload.destinatario = form.destinatario.trim()
    payload.remedio = form.remedio.trim()
  }
  return payload
}

export function payloadRecord(payload: AlarmaPayload): Record<string, unknown> {
  const out: Record<string, unknown> = {
    alarma_id: payload.alarma_id,
    titulo: payload.titulo,
    tipo: payload.tipo,
    hora: payload.hora,
    repeticion: payload.repeticion,
  }
  if (payload.fecha !== undefined) out.fecha = payload.fecha
  if (payload.destinatario !== undefined) out.destinatario = payload.destinatario
  if (payload.remedio !== undefined) out.remedio = payload.remedio
  return out
}

export function desactivadaPayload(alarmaId: string): Record<string, unknown> {
  return { alarma_id: alarmaId, motivo: 'usuario' }
}

export function repeticionResumen(rep: AlarmaActiva['repeticion']): string {
  if (!rep) return 'Una vez'
  const freq = isFreq(rep.freq)
    ? { DAILY: 'Diaria', WEEKLY: 'Semanal', MONTHLY: 'Mensual', HOURLY: 'Cada hora' }[rep.freq]
    : rep.freq
  const bits = [freq]
  if (rep.interval !== undefined && rep.interval !== 1) bits.push(`cada ${rep.interval}`)
  if (rep.bymonthday === -1) bits.push('último día')
  else if (rep.bymonthday !== undefined) bits.push(`día ${rep.bymonthday}`)
  if (rep.byday && rep.byday.length > 0) bits.push(formatByday(rep.byday))
  if (rep.count !== undefined) bits.push(`${rep.count} veces`)
  if (rep.until) bits.push(`hasta ${rep.until}`)
  return bits.join(' · ')
}

const REP_KEYS = new Set(['freq', 'interval', 'byday', 'bymonthday', 'count', 'until'])

export function parseRepeticion(value: unknown): Repeticion | null | string {
  if (value === null) return null
  if (!isRecord(value)) return 'repeticion inválida'
  for (const key of Object.keys(value)) {
    if (!REP_KEYS.has(key)) return `campo no admitido en repeticion: ${key}`
  }
  const freq = value.freq
  if (!isFreqString(freq)) return 'freq fuera del subconjunto'
  const rep: Repeticion = { freq }
  if (value.interval !== undefined) {
    if (!isPositiveInt(value.interval)) return 'interval tiene que ser un entero ≥ 1'
    rep.interval = value.interval
  }
  if (value.count !== undefined && value.until !== undefined) return 'count y until son excluyentes'
  if (value.count !== undefined) {
    if (!isPositiveInt(value.count)) return 'count tiene que ser un entero ≥ 1'
    rep.count = value.count
  }
  if (value.until !== undefined) {
    if (typeof value.until !== 'string' || !isDate(value.until))
      return 'until tiene que ser una fecha YYYY-MM-DD'
    rep.until = value.until
  }
  const hasByday = value.byday !== undefined
  const hasMonthday = value.bymonthday !== undefined
  if (hasByday && hasMonthday) return 'byday y bymonthday son excluyentes'
  if (hasMonthday) {
    if (freq !== 'MONTHLY') return 'bymonthday solo vale en MONTHLY'
    if (!isMonthday(value.bymonthday)) return 'bymonthday tiene que ser 1..31 o -1'
    rep.bymonthday = value.bymonthday
  }
  if (hasByday) {
    if (freq !== 'WEEKLY' && freq !== 'MONTHLY') return 'byday solo vale en WEEKLY o MONTHLY'
    if (!Array.isArray(value.byday) || value.byday.length < 1)
      return 'byday tiene que tener al menos un día'
    if (new Set(value.byday).size !== value.byday.length) return 'byday tiene días repetidos'
    if (freq === 'WEEKLY') {
      const days: Weekday[] = []
      for (const day of value.byday) {
        if (typeof day !== 'string' || !isWeekday(day)) return 'WEEKLY byday no lleva ordinal'
        days.push(day)
      }
      rep.byday = days
    } else {
      if (value.byday.length !== 1) return 'MONTHLY admite un solo byday'
      const token = value.byday[0]
      if (typeof token !== 'string' || !ORDINAL_RE.test(token))
        return 'MONTHLY byday lleva ordinal (1MO..5SU o -1FR)'
      rep.byday = [token]
    }
  }
  if (freq === 'MONTHLY' && !hasByday && !hasMonthday)
    return 'MONTHLY necesita bymonthday o byday con ordinal'
  return rep
}

function isFreqString(value: unknown): value is Freq {
  return typeof value === 'string' && isFreq(value)
}

function isPositiveInt(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1
}

function isMonthday(value: unknown): value is number {
  return (
    typeof value === 'number' &&
    Number.isInteger(value) &&
    value !== 0 &&
    value >= -1 &&
    value <= 31
  )
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function parseBulkEntry(value: unknown, index: number): BulkAlarma | string {
  if (!isRecord(value)) return `Ítem ${index}: no es un objeto`
  const titulo = value.titulo
  const tipo = value.tipo
  const hora = value.hora
  if (typeof titulo !== 'string' || titulo.trim().length < 1 || titulo.length > 120) {
    return `Ítem ${index}: titulo inválido`
  }
  if (typeof tipo !== 'string' || !isTipo(tipo)) return `Ítem ${index}: tipo inválido`
  if (typeof hora !== 'string' || !HORA_RE.test(hora)) return `Ítem ${index}: hora inválida`
  if (!('repeticion' in value)) return `Ítem ${index}: falta repeticion`
  const repeticion = parseRepeticion(value.repeticion)
  if (typeof repeticion === 'string') return `Ítem ${index}: ${repeticion}`
  const entry: BulkAlarma = { titulo, tipo, hora, repeticion }
  if (value.fecha !== undefined) {
    if (typeof value.fecha !== 'string' || !isDate(value.fecha)) {
      return `Ítem ${index}: fecha inválida`
    }
    entry.fecha = value.fecha
  }
  if (tipo === 'remedio') {
    const destinatario = value.destinatario
    const remedio = value.remedio
    if (typeof destinatario !== 'string' || destinatario.length < 1 || destinatario.length > 60) {
      return `Ítem ${index}: falta destinatario`
    }
    if (typeof remedio !== 'string' || remedio.length < 1 || remedio.length > 120) {
      return `Ítem ${index}: falta remedio`
    }
    entry.destinatario = destinatario
    entry.remedio = remedio
  }
  return entry
}

export function parseBulk(
  text: string,
): { ok: true; alarmas: BulkAlarma[] } | { ok: false; error: string } {
  let data: unknown
  try {
    data = JSON.parse(text) as unknown
  } catch {
    return { ok: false, error: 'El JSON no se pudo leer' }
  }
  if (!isRecord(data)) return { ok: false, error: 'El archivo no es un objeto' }
  if (data.formato !== 'cauce.alarmas')
    return { ok: false, error: 'formato debe ser cauce.alarmas' }
  if (data.version !== 1) return { ok: false, error: 'version debe ser 1' }
  if (!Array.isArray(data.alarmas)) return { ok: false, error: 'alarmas debe ser una lista' }
  const alarmas: BulkAlarma[] = []
  for (let i = 0; i < data.alarmas.length; i++) {
    const parsed = parseBulkEntry(data.alarmas[i], i + 1)
    if (typeof parsed === 'string') return { ok: false, error: parsed }
    alarmas.push(parsed)
  }
  return { ok: true, alarmas }
}

export function bulkToPayload(entry: BulkAlarma, alarmaId: string): AlarmaPayload {
  const payload: AlarmaPayload = {
    alarma_id: alarmaId,
    titulo: entry.titulo,
    tipo: entry.tipo,
    hora: entry.hora,
    repeticion: entry.repeticion,
  }
  if (entry.fecha !== undefined) payload.fecha = entry.fecha
  if (entry.destinatario !== undefined) payload.destinatario = entry.destinatario
  if (entry.remedio !== undefined) payload.remedio = entry.remedio
  return payload
}

function cleanBulk(entry: BulkAlarma): BulkAlarma {
  const out: BulkAlarma = {
    titulo: entry.titulo,
    tipo: entry.tipo,
    hora: entry.hora,
    repeticion: entry.repeticion,
  }
  if (entry.fecha !== undefined) out.fecha = entry.fecha
  if (entry.destinatario !== undefined) out.destinatario = entry.destinatario
  if (entry.remedio !== undefined) out.remedio = entry.remedio
  return out
}

export function exportBulk(alarmas: BulkAlarma[]): string {
  return `${JSON.stringify({ formato: 'cauce.alarmas', version: 1, alarmas: alarmas.map(cleanBulk) }, null, 2)}\n`
}

/**
 * Filas de P1 → ítems de `cauce.alarmas`.
 *
 * Las entradas `gcal:` no se exportan: son reuniones de Google, no alarmas de cauce.
 * La `fecha` que se lleva es la local de `proximo_disparo` (lo único que P1 conoce)
 * y el `count` es `restantes`, para que reimportar no repita disparos ya consumidos.
 * Un `HOURLY` lleva además la `hora` local de `proximo_disparo`: con la hora original
 * el ancla no coincidiría con el próximo disparo y se perdería o saltaría una dosis.
 */
export function rowsToBulk(
  rows: AlarmaActiva[],
  extras: { alarma_id: string; destinatario: string; remedio: string }[],
  tz: string = browserTimeZone(),
): BulkAlarma[] {
  const byId = new Map(extras.map((row) => [row.alarma_id, row]))
  const out: BulkAlarma[] = []
  for (const row of rows) {
    if (isGoogleCalendar(row.alarma_id)) continue
    const tipo = isTipo(row.tipo) ? row.tipo : 'otro'
    const repeticion = row.repeticion === null ? null : parseRepeticion(row.repeticion)
    if (typeof repeticion === 'string') continue
    if (repeticion && repeticion.count !== undefined && row.restantes !== undefined) {
      repeticion.count = row.restantes
    }
    const hora = repeticion?.freq === 'HOURLY' ? localTime(row.proximo_disparo, tz) : row.hora
    const entry: BulkAlarma = {
      titulo: row.titulo,
      tipo,
      hora,
      fecha: localDate(row.proximo_disparo, tz),
      repeticion,
    }
    const extra = byId.get(row.alarma_id)
    if (tipo === 'remedio' && extra) {
      entry.destinatario = extra.destinatario
      entry.remedio = extra.remedio
    }
    out.push(entry)
  }
  return out
}
