import type { Aviso, Reunion } from './types'

export const V_AVISO_REUNION_EDITADO = 1

export type AvisoModo = 'minutos_antes' | 'hora' | 'apagado'

const ORIGEN = { google: 'Google', cauce: 'cauce', defecto: 'por defecto' } as const

/** El aviso vigente en palabras: «10 min antes · Google», «08:00 · por defecto», «Apagado · cauce». */
export function avisoTexto(aviso: Aviso): string {
  const origen = ORIGEN[aviso.origen]
  if (aviso.apagado) return `Apagado · ${origen}`
  if (aviso.hora !== undefined) return `${aviso.hora} · ${origen}`
  const min = aviso.minutos_antes ?? 0
  return `${min === 0 ? 'Al inicio' : `${min} min antes`} · ${origen}`
}

/** Fecha local (YYYY-MM-DD) de la reunión: en las de todo el día es la parte de fecha de `inicio` (UTC). */
export function diaLocal(row: Pick<Reunion, 'inicio' | 'todo_el_dia'>): string {
  if (row.todo_el_dia) return row.inicio.slice(0, 10)
  return new Date(row.inicio).toLocaleDateString('en-CA')
}

export function formatDia(day: string): string {
  const [y, m, d] = day.split('-').map(Number)
  return new Intl.DateTimeFormat('es', { weekday: 'long', day: 'numeric', month: 'long' }).format(
    new Date(y ?? 1970, (m ?? 1) - 1, d ?? 1),
  )
}

export function formatHora(iso: string): string {
  return new Intl.DateTimeFormat('es', { hour: '2-digit', minute: '2-digit', hour12: false }).format(
    new Date(iso),
  )
}

export function agruparPorDia(rows: Reunion[]): { dia: string; rows: Reunion[] }[] {
  const groups = new Map<string, Reunion[]>()
  for (const row of rows) {
    const key = diaLocal(row)
    const list = groups.get(key)
    if (list) list.push(row)
    else groups.set(key, [row])
  }
  return [...groups].map(([dia, list]) => ({ dia, rows: list }))
}

export interface AvisoForm {
  modo: AvisoModo
  minutos: string
  hora: string
}

export function initialForm(row: Reunion): AvisoForm {
  const a = row.aviso
  return {
    modo: a.apagado ? 'apagado' : row.todo_el_dia ? 'hora' : 'minutos_antes',
    minutos: String(a.minutos_antes ?? 10),
    hora: a.hora ?? '08:00',
  }
}

const HORA = /^([01][0-9]|2[0-3]):[0-5][0-9]$/

/** Valida el formulario y arma el payload con exactamente una de las tres claves. */
export function avisoPayload(
  serieId: string,
  form: AvisoForm,
): { ok: true; payload: Record<string, unknown> } | { ok: false; error: string } {
  if (form.modo === 'apagado') return { ok: true, payload: { serie_id: serieId, apagado: true } }
  if (form.modo === 'hora') {
    if (!HORA.test(form.hora)) return { ok: false, error: 'Usá una hora válida (HH:MM)' }
    return { ok: true, payload: { serie_id: serieId, hora: form.hora } }
  }
  if (!/^\d+$/.test(form.minutos.trim())) {
    return { ok: false, error: 'Los minutos son un entero de 0 o más' }
  }
  return { ok: true, payload: { serie_id: serieId, minutos_antes: Number(form.minutos.trim()) } }
}
