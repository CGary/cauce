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

export const V_ALARMA_CREADA = 2
export const V_ALARMA_MODIFICADA = 2
export const V_ALARMA_DESACTIVADA = 1

export type FreqForm = 'una_vez' | Freq
export type MonthlyMode = 'bymonthday' | 'byday'
export type LimitMode = 'none' | 'count' | 'until'

export interface AlarmaFormState {
  titulo: string
  tipo: TipoAlarma
  hora: string
  freq: FreqForm
  interval: string
  limit: LimitMode
  count: string
  until: string
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
    freq: 'una_vez',
    interval: '',
    limit: 'none',
    count: '',
    until: '',
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

export function activaToForm(
  row: AlarmaActiva,
  extra?: { destinatario?: string; remedio?: string },
): AlarmaFormState {
  const form = emptyForm()
  form.titulo = row.titulo
  form.tipo = isTipo(row.tipo) ? row.tipo : 'otro'
  form.hora = row.hora
  form.destinatario = extra?.destinatario ?? ''
  form.remedio = extra?.remedio ?? ''
  const rep = row.repeticion
  if (!rep || !isFreq(rep.freq)) return form
  form.freq = rep.freq
  form.interval = rep.interval !== undefined ? String(rep.interval) : ''
  if (rep.count !== undefined) {
    form.limit = 'count'
    form.count = String(rep.count)
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
  if (rep.byday && rep.byday.length > 0) bits.push(rep.byday.join(', '))
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
  if (entry.destinatario !== undefined) out.destinatario = entry.destinatario
  if (entry.remedio !== undefined) out.remedio = entry.remedio
  return out
}

export function exportBulk(alarmas: BulkAlarma[]): string {
  return `${JSON.stringify({ formato: 'cauce.alarmas', version: 1, alarmas: alarmas.map(cleanBulk) }, null, 2)}\n`
}

export function rowsToBulk(
  rows: AlarmaActiva[],
  extras: { alarma_id: string; destinatario: string; remedio: string }[],
): BulkAlarma[] {
  const byId = new Map(extras.map((row) => [row.alarma_id, row]))
  const out: BulkAlarma[] = []
  for (const row of rows) {
    const tipo = isTipo(row.tipo) ? row.tipo : 'otro'
    const repeticion = row.repeticion === null ? null : parseRepeticion(row.repeticion)
    if (typeof repeticion === 'string') continue
    const entry: BulkAlarma = { titulo: row.titulo, tipo, hora: row.hora, repeticion }
    const extra = byId.get(row.alarma_id)
    if (tipo === 'remedio' && extra) {
      entry.destinatario = extra.destinatario
      entry.remedio = extra.remedio
    }
    out.push(entry)
  }
  return out
}
