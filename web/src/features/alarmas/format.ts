// Formatos de presentación de alarmas. Solo texto: ninguna regla de proyección vive aquí.

/** Orden de la semana RFC 5545 con su nombre abreviado en español. */
const DAYS: [string, string][] = [
  ['MO', 'lun'],
  ['TU', 'mar'],
  ['WE', 'mié'],
  ['TH', 'jue'],
  ['FR', 'vie'],
  ['SA', 'sáb'],
  ['SU', 'dom'],
]
const DAY_INDEX = new Map(DAYS.map(([code], index) => [code, index]))

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1)
}

function joinList(items: string[]): string {
  if (items.length <= 1) return items.join('')
  return `${items.slice(0, -1).join(', ')} y ${items[items.length - 1]}`
}

/**
 * Días de `byday` en español: «Lun a vie», «Lun, mié y vie», «Todos los días».
 * Tres o más días seguidos se resumen como rango; los ordinales mensuales («1MO», «-1FR») se
 * escriben «1.º lun» y «último vie» y no se agrupan.
 */
export function formatByday(byday: string[]): string {
  const plain: number[] = []
  const ordinals: string[] = []
  for (const raw of byday) {
    const match = /^([+-]?\d+)?([A-Z]{2})$/.exec(raw)
    const index = match ? DAY_INDEX.get(match[2] ?? '') : undefined
    if (!match || index === undefined) {
      ordinals.push(raw)
      continue
    }
    const name = DAYS[index]?.[1] ?? raw
    if (match[1] === undefined) plain.push(index)
    else {
      const n = Number(match[1])
      ordinals.push(n === -1 ? `último ${name}` : `${n}.º ${name}`)
    }
  }
  const days = [...new Set(plain)].sort((a, b) => a - b)
  const parts: string[] = []
  if (days.length === 7) parts.push('todos los días')
  else {
    for (let i = 0; i < days.length;) {
      let j = i
      while (j + 1 < days.length && (days[j + 1] ?? 0) === (days[j] ?? 0) + 1) j++
      const name = (k: number) => DAYS[days[k] ?? 0]?.[1] ?? ''
      if (j - i >= 2) {
        parts.push(`${name(i)} a ${name(j)}`)
        i = j + 1
      } else {
        parts.push(name(i))
        i++
      }
    }
  }
  return capitalize(joinList([...parts, ...ordinals]))
}

const HORA = /^(\d{1,2}):(\d{2})$/

/** «7:01» → «07:01». Lo que no parece una hora se devuelve tal cual. */
export function formatHora(hora: string): string {
  const match = HORA.exec(hora.trim())
  if (!match) return hora
  return `${(match[1] ?? '').padStart(2, '0')}:${match[2] ?? ''}`
}

const fechaHora = new Intl.DateTimeFormat('es', {
  day: 'numeric',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})
const fechaHoraAnio = new Intl.DateTimeFormat('es', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

/** RFC 3339 → «30 oct, 08:15» (con año si no es el actual); siempre 24 h de dos dígitos. */
export function formatFechaHora(iso: string, now: Date = new Date()): string {
  const date = new Date(iso)
  const format = date.getFullYear() === now.getFullYear() ? fechaHora : fechaHoraAnio
  return format.format(date)
}

const horaLocal = new Intl.DateTimeFormat('es', {
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

/** RFC 3339 → «08:15» en la zona del navegador. */
export function formatHoraLocal(iso: string): string {
  return horaLocal.format(new Date(iso))
}

const dayKey = new Intl.DateTimeFormat('en-CA', { dateStyle: 'short' })

/** Fecha local AAAA-MM-DD de un instante; clave para agrupar por día. */
export function localDay(iso: string): string {
  return dayKey.format(new Date(iso))
}

const dayHeading = new Intl.DateTimeFormat('es', { weekday: 'long', day: 'numeric', month: 'long' })

/** «jueves 30 de octubre» a partir de AAAA-MM-DD. */
export function formatDia(day: string): string {
  const [y, m, d] = day.split('-').map(Number)
  return dayHeading.format(new Date(y ?? 1970, (m ?? 1) - 1, d ?? 1))
}

/** Agrupa filas ya ordenadas (más recientes primero) por día local, conservando el orden. */
export function groupByDay<T>(rows: T[], tsOf: (row: T) => string): { dia: string; rows: T[] }[] {
  const groups: { dia: string; rows: T[] }[] = []
  for (const row of rows) {
    const dia = localDay(tsOf(row))
    const last = groups[groups.length - 1]
    if (last && last.dia === dia) last.rows.push(row)
    else groups.push({ dia, rows: [row] })
  }
  return groups
}
