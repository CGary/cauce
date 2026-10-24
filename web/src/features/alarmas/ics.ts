import { parseRepeticion } from './model'
import type { BulkAlarma, Freq, Repeticion, TipoAlarma, Weekday } from './types'
import { resolveTimeZone } from './windowsZones'

export interface IcsSource extends BulkAlarma {
  alarma_id?: string
  proximo_disparo?: string
}

export interface IcsExportOptions {
  tz: string
  now: string
}

export interface IcsImportError {
  index: number
  summary?: string
  message: string
}

export interface IcsImportResult {
  alarmas: BulkAlarma[]
  errors: IcsImportError[]
}

const WEEKDAYS = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU'] as const
const RRULE_KNOWN = new Set([
  'FREQ',
  'INTERVAL',
  'COUNT',
  'UNTIL',
  'BYDAY',
  'BYMONTHDAY',
  'BYSETPOS',
  'WKST',
])

interface Prop {
  name: string
  params: Record<string, string>
  value: string
}

interface LocalDateTime {
  date: string
  time: string
}

export function exportIcs(alarmas: IcsSource[], options: IcsExportOptions): string {
  const events = alarmas.flatMap((alarma, index) => veventsFor(alarma, index, options))
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Cauce//Alarmas//ES',
    'CALSCALE:GREGORIAN',
    ...events.flat(),
    'END:VCALENDAR',
  ]
  return `${lines.map(fold).join('\r\n')}\r\n`
}

export function importIcs(text: string, options: { tz: string }): IcsImportResult {
  const alarmas: BulkAlarma[] = []
  const errors: IcsImportError[] = []
  const events = extractEvents(unfold(text))
  events.forEach((props, index) => {
    const summary = propValue(props, 'SUMMARY')
    let parsed: BulkAlarma | string
    try {
      parsed = veventToAlarma(props, options.tz)
    } catch (error) {
      parsed = error instanceof Error ? error.message : 'evento inválido'
    }
    if (typeof parsed === 'string') {
      errors.push({
        index: index + 1,
        summary: summary ? unescapeText(summary) : undefined,
        message: parsed,
      })
      return
    }
    alarmas.push(parsed)
  })
  return { alarmas, errors }
}

function veventsFor(alarma: IcsSource, index: number, options: IcsExportOptions): string[][] {
  const anchor = anchorLocal(alarma, options)
  const rep = alarma.repeticion
  if (rep?.freq === 'HOURLY') return hourlyEvents(alarma, index, anchor, rep, options)
  return [renderEvent(alarma, uid(alarma, index), anchor, rep, options)]
}

function hourlyEvents(
  alarma: IcsSource,
  index: number,
  anchor: LocalDateTime,
  rep: Repeticion,
  options: IcsExportOptions,
): string[][] {
  const interval = rep.interval !== undefined && rep.interval >= 1 ? rep.interval : 1
  if (24 % interval === 0) {
    const slots = 24 / interval
    const events: string[][] = []
    for (let slot = 0; slot < slots; slot++) {
      const count = splitCount(rep.count, slots, slot)
      if (count === 0) continue
      const at = addClockHours(anchor.date, anchor.time, slot * interval)
      const daily: Repeticion = { freq: 'DAILY' }
      if (count !== undefined) daily.count = count
      if (rep.until) daily.until = rep.until
      events.push(
        renderEvent(alarma, `${uid(alarma, index)}-${slot}`, at, daily, options, {
          hora: at.time.slice(0, 5),
        }),
      )
    }
    return events
  }
  const events: string[][] = []
  // La serie arranca en el disparo real que trae P1 (`proximo_disparo`), no en
  // `hora`: si la alarma se creó a otra hora, exportar `hora` daría horarios que
  // no suenan nunca.
  let cursor: LocalDateTime = { date: anchor.date, time: anchor.time }
  const windowEnd: LocalDateTime = { date: addDays(anchor.date, 30), time: anchor.time }
  const limit = rep.count ?? 20_000
  for (let n = 0; n < limit; n++) {
    if (rep.until && cursor.date > rep.until) break
    if (!rep.count && !rep.until && compareLocal(cursor, windowEnd) >= 0) break
    events.push(
      renderEvent(alarma, `${uid(alarma, index)}-${n}`, cursor, null, options, {
        hora: cursor.time.slice(0, 5),
      }),
    )
    cursor = addClockHours(cursor.date, cursor.time, interval)
  }
  return events
}

function splitCount(count: number | undefined, slots: number, slot: number): number | undefined {
  if (count === undefined) return undefined
  const base = Math.floor(count / slots)
  return base + (slot < count % slots ? 1 : 0)
}

function renderEvent(
  alarma: IcsSource,
  id: string,
  start: LocalDateTime,
  rep: Repeticion | null,
  options: IcsExportOptions,
  override?: { hora?: string },
): string[] {
  const hora = override?.hora ?? alarma.hora
  const lines = [
    'BEGIN:VEVENT',
    `UID:${id}@cauce`,
    `DTSTAMP:${formatUtcStamp(options.now)}`,
    `DTSTART;TZID=${options.tz}:${formatIcsLocal(start.date, hora)}`,
    `SUMMARY:${escapeText(alarma.titulo)}`,
    `CATEGORIES:${alarma.tipo}`,
  ]
  const description = domainDescription(alarma)
  if (description) lines.push(`DESCRIPTION:${escapeText(description)}`)
  if (alarma.destinatario) lines.push(`X-CAUCE-DESTINATARIO:${escapeText(alarma.destinatario)}`)
  if (alarma.remedio) lines.push(`X-CAUCE-REMEDIO:${escapeText(alarma.remedio)}`)
  const rrule = rep ? rruleOf(rep, options.tz) : null
  if (rrule) lines.push(`RRULE:${rrule}`)
  lines.push(
    'BEGIN:VALARM',
    'ACTION:DISPLAY',
    `DESCRIPTION:${escapeText(alarma.titulo)}`,
    'TRIGGER:PT0M',
    'END:VALARM',
    'END:VEVENT',
  )
  return lines
}

function domainDescription(alarma: IcsSource): string | null {
  const lines: string[] = []
  if (alarma.destinatario) lines.push(`Destinatario: ${alarma.destinatario}`)
  if (alarma.remedio) lines.push(`Remedio: ${alarma.remedio}`)
  return lines.length > 0 ? lines.join('\n') : null
}

function rruleOf(rep: Repeticion, tz: string): string {
  const parts = [`FREQ=${rep.freq}`]
  if (rep.interval !== undefined && rep.interval !== 1) parts.push(`INTERVAL=${rep.interval}`)
  if (rep.byday && rep.byday.length > 0) parts.push(`BYDAY=${rep.byday.join(',')}`)
  if (rep.bymonthday !== undefined) parts.push(`BYMONTHDAY=${rep.bymonthday}`)
  if (rep.count !== undefined) parts.push(`COUNT=${rep.count}`)
  if (rep.until) parts.push(`UNTIL=${untilUtc(rep.until, tz)}`)
  return parts.join(';')
}

function untilUtc(until: string, tz: string): string {
  const instant = wallToUtc(until, '23:59:59', tz)
  return formatUtcStamp(instant.toISOString())
}

function uid(alarma: IcsSource, index: number): string {
  return alarma.alarma_id && alarma.alarma_id.length > 0 ? alarma.alarma_id : `alarma-${index}`
}

function anchorLocal(alarma: IcsSource, options: IcsExportOptions): LocalDateTime {
  if (alarma.proximo_disparo) return formatInZone(new Date(alarma.proximo_disparo), options.tz)
  return formatInZone(new Date(options.now), options.tz)
}

function veventToAlarma(props: Prop[], tz: string): BulkAlarma | string {
  if (props.some((prop) => prop.name === 'EXDATE' || prop.name === 'RDATE')) {
    return 'EXDATE/RDATE está fuera del subconjunto'
  }
  const summary = propValue(props, 'SUMMARY')
  if (!summary) return 'falta SUMMARY'
  const titulo = unescapeText(summary).trim()
  if (titulo.length < 1 || titulo.length > 120) return 'SUMMARY inválido'
  const dtstart = props.find((prop) => prop.name === 'DTSTART')
  if (!dtstart) return 'falta DTSTART'
  const local = dtstartToLocal(dtstart, tz)
  if ('error' in local) return local.error
  const tipo = tipoFromCategories(propValue(props, 'CATEGORIES'))
  const rrule = propValue(props, 'RRULE')
  let repeticion: Repeticion | null = null
  if (rrule) {
    const parsed = rruleToRepeticion(rrule, tz)
    if (typeof parsed === 'string') return parsed
    repeticion = parsed
  }
  const entry: BulkAlarma = { titulo, tipo, hora: local.time.slice(0, 5), repeticion }
  const xDest = propValue(props, 'X-CAUCE-DESTINATARIO')
  const xRem = propValue(props, 'X-CAUCE-REMEDIO')
  const fromDesc = descriptionFields(propValue(props, 'DESCRIPTION'))
  const destinatario = xDest ? unescapeText(xDest) : fromDesc.destinatario
  const remedio = xRem ? unescapeText(xRem) : fromDesc.remedio
  if (destinatario) entry.destinatario = destinatario
  if (remedio) entry.remedio = remedio
  if (tipo === 'remedio' && (!entry.destinatario || !entry.remedio)) {
    return 'una alarma remedio necesita destinatario y remedio'
  }
  const checked = parseRepeticion(entry.repeticion)
  if (typeof checked === 'string') return checked
  entry.repeticion = checked
  return entry
}

function rruleToRepeticion(value: string, tz: string): Repeticion | string {
  const parts = parseRrule(value)
  for (const key of parts.keys()) {
    if (!RRULE_KNOWN.has(key)) return `${key} está fuera del subconjunto`
  }
  const freq = parts.get('FREQ')?.toUpperCase()
  if (!freq) return 'falta FREQ'
  if (freq === 'YEARLY' || !isFreq(freq)) return `${freq} está fuera del subconjunto`
  if (parts.has('COUNT') && parts.has('UNTIL')) return 'COUNT y UNTIL son excluyentes'
  const rep: Repeticion = { freq }
  const interval = optionalInt(parts.get('INTERVAL'))
  if (typeof interval === 'string') return interval
  if (interval !== undefined) rep.interval = interval
  const count = optionalInt(parts.get('COUNT'))
  if (typeof count === 'string') return count
  if (count !== undefined) rep.count = count
  const until = parts.get('UNTIL')
  if (until !== undefined) {
    const date = untilToLocalDate(until, tz)
    if (typeof date === 'string' && date.startsWith('UNTIL')) return date
    if (typeof date !== 'string') return 'UNTIL inválido'
    rep.until = date
  }
  const bysetpos = parts.get('BYSETPOS')
  const byday = parts.get('BYDAY')
  const bymonthday = parts.get('BYMONTHDAY')
  if (freq === 'MONTHLY') {
    const monthly = monthlyRepeticion(rep, byday, bymonthday, bysetpos)
    if (typeof monthly === 'string') return monthly
    return monthly
  }
  if (bysetpos !== undefined) return 'BYSETPOS solo se acepta en la forma mensual de Apple/Outlook'
  if (bymonthday !== undefined) return 'BYMONTHDAY solo vale en MONTHLY'
  if (byday !== undefined) {
    if (freq !== 'WEEKLY') return 'BYDAY solo vale en WEEKLY o MONTHLY'
    const days = byday.split(',').map((day) => day.trim().toUpperCase().replace(/^\+/, ''))
    if (days.length < 1 || days.some((day) => !isWeekday(day))) return 'BYDAY semanal inválido'
    if (new Set(days).size !== days.length) return 'BYDAY tiene días repetidos'
    rep.byday = days
  }
  return rep
}

function monthlyRepeticion(
  rep: Repeticion,
  byday: string | undefined,
  bymonthday: string | undefined,
  bysetpos: string | undefined,
): Repeticion | string {
  if (bymonthday !== undefined && byday !== undefined) return 'BYMONTHDAY y BYDAY son excluyentes'
  if (bymonthday !== undefined) {
    if (bysetpos !== undefined) return 'BYSETPOS no combina con BYMONTHDAY'
    const days = bymonthday.split(',').map((day) => day.trim())
    if (days.length !== 1) return 'MONTHLY admite un solo BYMONTHDAY'
    const day = Number(days[0]?.replace(/^\+/, ''))
    if (!Number.isInteger(day) || day === 0 || day < -1 || day > 31) return 'BYMONTHDAY inválido'
    rep.bymonthday = day
    return rep
  }
  if (byday === undefined) return 'MONTHLY necesita BYMONTHDAY o BYDAY'
  const days = byday.split(',').map((day) => day.trim().toUpperCase().replace(/^\+/, ''))
  if (days.length !== 1) return 'MONTHLY admite un solo BYDAY'
  const day = days[0]
  if (!day) return 'BYDAY inválido'
  if (bysetpos !== undefined) {
    const positions = bysetpos.split(',').map((pos) => pos.trim())
    if (positions.length !== 1) return 'BYSETPOS admite un solo valor'
    const pos = positions[0]
    if (!pos || !/^(-1|[1-5])$/.test(pos)) return 'BYSETPOS fuera de 1..5 o -1'
    if (!isWeekday(day)) return 'la forma BYSETPOS pide un día sin ordinal'
    rep.byday = [`${pos}${day}`]
    return rep
  }
  if (!/^(-1|[1-5])(MO|TU|WE|TH|FR|SA|SU)$/.test(day))
    return 'MONTHLY BYDAY lleva ordinal (1MO..5SU o -1FR)'
  rep.byday = [day]
  return rep
}

function optionalInt(value: string | undefined): number | undefined | string {
  if (value === undefined) return undefined
  if (!/^[1-9]\d*$/.test(value)) return 'INTERVAL/COUNT inválido'
  return Number(value)
}

function untilToLocalDate(value: string, tz: string): string {
  if (/^\d{8}$/.test(value)) return `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}`
  const parsed = parseBasicDateTime(value)
  if (!parsed || parsed.dateOnly) return 'UNTIL inválido'
  if (parsed.utc) return formatInZone(parsed.instant, tz).date
  return parsed.date
}

function dtstartToLocal(prop: Prop, sessionTz: string): LocalDateTime | { error: string } {
  const parsed = parseBasicDateTime(prop.value)
  if (!parsed || parsed.dateOnly) return { error: 'DTSTART sin hora' }
  if (parsed.utc) return formatInZone(parsed.instant, sessionTz)
  const tzid = prop.params.TZID
  if (tzid) {
    // Outlook escribe nombres de zona de Windows: se traducen a IANA. Una zona
    // que no se conoce es un error de ESTE evento, no del archivo entero.
    const zone = resolveTimeZone(tzid)
    if (!zone) return { error: `TZID desconocido: ${tzid}` }
    return formatInZone(wallToUtc(parsed.date, parsed.time, zone), sessionTz)
  }
  return { date: parsed.date, time: parsed.time }
}

function tipoFromCategories(value: string | undefined): TipoAlarma {
  if (!value) return 'otro'
  for (const token of unescapeText(value).split(',')) {
    const tipo = token.trim().toLowerCase()
    if (tipo === 'remedio' || tipo === 'reunion' || tipo === 'otro') return tipo
  }
  return 'otro'
}

function descriptionFields(value: string | undefined): { destinatario?: string; remedio?: string } {
  if (!value) return {}
  const out: { destinatario?: string; remedio?: string } = {}
  for (const line of unescapeText(value).split(/\r?\n/)) {
    const dest = /^Destinatario:\s*(.*)$/.exec(line)
    const rem = /^Remedio:\s*(.*)$/.exec(line)
    if (dest?.[1]) out.destinatario = dest[1]
    if (rem?.[1]) out.remedio = rem[1]
  }
  return out
}

function propValue(props: Prop[], name: string): string | undefined {
  return props.find((prop) => prop.name === name)?.value
}

function extractEvents(lines: string[]): Prop[][] {
  const events: Prop[][] = []
  let current: Prop[] | null = null
  let skip = 0
  for (const line of lines) {
    const prop = parseLine(line)
    if (!prop) continue
    if (current === null) {
      if (prop.name === 'BEGIN' && prop.value.toUpperCase() === 'VEVENT') current = []
      continue
    }
    if (prop.name === 'BEGIN') {
      skip += 1
      continue
    }
    if (prop.name === 'END' && skip > 0) {
      skip -= 1
      continue
    }
    if (prop.name === 'END' && prop.value.toUpperCase() === 'VEVENT') {
      events.push(current)
      current = null
      continue
    }
    if (skip === 0) current.push(prop)
  }
  return events
}

function unfold(text: string): string[] {
  const logical: string[] = []
  for (const line of text.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')) {
    if ((line.startsWith(' ') || line.startsWith('\t')) && logical.length > 0) {
      const prev = logical[logical.length - 1]
      if (prev !== undefined) logical[logical.length - 1] = prev + line.slice(1)
    } else if (line !== '') {
      logical.push(line)
    }
  }
  return logical
}

function parseLine(line: string): Prop | null {
  const colon = indexOfValueColon(line)
  if (colon < 0) return null
  const parts = splitParams(line.slice(0, colon))
  const name = parts[0]?.toUpperCase()
  if (!name) return null
  const params: Record<string, string> = {}
  for (const part of parts.slice(1)) {
    const eq = part.indexOf('=')
    if (eq < 0) continue
    const key = part.slice(0, eq).toUpperCase()
    let val = part.slice(eq + 1)
    if (val.startsWith('"') && val.endsWith('"')) val = val.slice(1, -1)
    params[key] = val
  }
  return { name, params, value: line.slice(colon + 1) }
}

function splitParams(left: string): string[] {
  const out: string[] = []
  let current = ''
  let quoted = false
  for (const ch of left) {
    if (ch === '"') {
      quoted = !quoted
      current += ch
    } else if (ch === ';' && !quoted) {
      out.push(current)
      current = ''
    } else {
      current += ch
    }
  }
  if (current) out.push(current)
  return out
}

function indexOfValueColon(line: string): number {
  let quoted = false
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]
    if (ch === '"') quoted = !quoted
    else if (ch === ':' && !quoted) return i
  }
  return -1
}

function parseRrule(value: string): Map<string, string> {
  const parts = new Map<string, string>()
  for (const piece of value.split(';')) {
    const eq = piece.indexOf('=')
    if (eq < 0) continue
    parts.set(piece.slice(0, eq).trim().toUpperCase(), piece.slice(eq + 1).trim())
  }
  return parts
}

interface BasicDateTime {
  date: string
  time: string
  dateOnly: boolean
  utc: boolean
  instant: Date
}

function parseBasicDateTime(value: string): BasicDateTime | null {
  const match = /^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z)?)?$/.exec(value)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const date = `${match[1]}-${match[2]}-${match[3]}`
  if (!match[4]) {
    return {
      date,
      time: '00:00:00',
      dateOnly: true,
      utc: false,
      instant: new Date(Date.UTC(year, month - 1, day)),
    }
  }
  const hour = Number(match[4])
  const minute = Number(match[5])
  const second = Number(match[6])
  const time = `${match[4]}:${match[5]}:${match[6]}`
  return {
    date,
    time,
    dateOnly: false,
    utc: match[7] === 'Z',
    instant: new Date(Date.UTC(year, month - 1, day, hour, minute, second)),
  }
}

function formatInZone(instant: Date, tz: string): LocalDateTime {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(instant)
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? '00'
  let hour = get('hour')
  if (hour === '24') hour = '00'
  return {
    date: `${get('year')}-${get('month')}-${get('day')}`,
    time: `${hour}:${get('minute')}:${get('second')}`,
  }
}

function offsetMinutes(instant: Date, tz: string): number {
  const local = formatInZone(instant, tz)
  const [year, month, day] = local.date.split('-').map(Number) as [number, number, number]
  const [hour, minute, second] = local.time.split(':').map(Number) as [number, number, number]
  const asUtc = Date.UTC(year, month - 1, day, hour, minute, second)
  return Math.round((asUtc - instant.getTime()) / 60_000)
}

function wallToUtc(date: string, time: string, tz: string): Date {
  const [year, month, day] = date.split('-').map(Number) as [number, number, number]
  const [hour, minute, second] = time.split(':').map(Number) as [number, number, number]
  let utc = Date.UTC(year, month - 1, day, hour, minute, second ?? 0)
  for (let i = 0; i < 3; i++) {
    const next =
      Date.UTC(year, month - 1, day, hour, minute, second ?? 0) -
      offsetMinutes(new Date(utc), tz) * 60_000
    if (next === utc) break
    utc = next
  }
  return new Date(utc)
}

function formatUtcStamp(iso: string): string {
  const date = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${date.getUTCFullYear()}${p(date.getUTCMonth() + 1)}${p(date.getUTCDate())}T${p(date.getUTCHours())}${p(date.getUTCMinutes())}${p(date.getUTCSeconds())}Z`
}

function formatIcsLocal(date: string, time: string): string {
  const [year, month, day] = date.split('-')
  const [hour, minute] = time.split(':')
  return `${year}${month}${day}T${hour}${minute}00`
}

function addClockHours(date: string, time: string, hours: number): LocalDateTime {
  const [year, month, day] = date.split('-').map(Number) as [number, number, number]
  const [hour, minute] = time.split(':').map(Number) as [number, number]
  const total = hour * 60 + minute + Math.round(hours * 60)
  const dayDelta = Math.floor(total / (24 * 60))
  const mins = ((total % (24 * 60)) + 24 * 60) % (24 * 60)
  const shifted = new Date(Date.UTC(year, month - 1, day + dayDelta))
  const p = (n: number) => String(n).padStart(2, '0')
  return {
    date: `${shifted.getUTCFullYear()}-${p(shifted.getUTCMonth() + 1)}-${p(shifted.getUTCDate())}`,
    time: `${p(Math.floor(mins / 60))}:${p(mins % 60)}:00`,
  }
}

function addDays(date: string, days: number): string {
  const [year, month, day] = date.split('-').map(Number) as [number, number, number]
  const shifted = new Date(Date.UTC(year, month - 1, day + days))
  const p = (n: number) => String(n).padStart(2, '0')
  return `${shifted.getUTCFullYear()}-${p(shifted.getUTCMonth() + 1)}-${p(shifted.getUTCDate())}`
}

function compareLocal(a: LocalDateTime, b: LocalDateTime): number {
  return `${a.date}T${a.time}`.localeCompare(`${b.date}T${b.time}`)
}

function escapeText(value: string): string {
  return value
    .replaceAll('\\', '\\\\')
    .replaceAll('\n', '\\n')
    .replaceAll(';', '\\;')
    .replaceAll(',', '\\,')
}

function unescapeText(value: string): string {
  return value.replace(/\\[\\nN;,]/g, (token) => {
    if (token === '\\n' || token === '\\N') return '\n'
    if (token === '\\;') return ';'
    if (token === '\\,') return ','
    if (token === '\\\\') return '\\'
    return token
  })
}

function fold(line: string): string {
  const encoder = new TextEncoder()
  if (encoder.encode(line).length <= 75) return line
  const parts: string[] = []
  let current = ''
  let currentBytes = 0
  for (const ch of line) {
    const size = encoder.encode(ch).length
    const max = parts.length === 0 ? 75 : 74
    if (currentBytes + size > max && current !== '') {
      parts.push(current)
      current = ch
      currentBytes = size
    } else {
      current += ch
      currentBytes += size
    }
  }
  if (current) parts.push(current)
  return parts.map((part, i) => (i === 0 ? part : ` ${part}`)).join('\r\n')
}

function isFreq(value: string): value is Freq {
  return value === 'DAILY' || value === 'WEEKLY' || value === 'MONTHLY' || value === 'HOURLY'
}

function isWeekday(value: string): value is Weekday {
  return (WEEKDAYS as readonly string[]).includes(value)
}
