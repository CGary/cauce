// Formas de salida de las proyecciones de alarmas, campo por campo.
// Fuente: schema/features/alarmas/SPEC.md §4. Todo datetime es RFC 3339 en UTC ("...Z").

export type TipoAlarma = 'remedio' | 'reunion' | 'otro'
export type Freq = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'HOURLY'
export type Weekday = 'MO' | 'TU' | 'WE' | 'TH' | 'FR' | 'SA' | 'SU'

/** Subconjunto de RRULE de alarma_creada.v2 / alarma_modificada.v2. */
export interface Repeticion {
  freq: Freq
  interval?: number
  byday?: string[]
  bymonthday?: number
  count?: number
  until?: string
}

/** Repetición tal como la devuelve P1 (freq abierto: el JSON del servidor no es un literal). */
export interface ProyeccionRepeticion {
  freq: string
  interval?: number
  byday?: string[]
  bymonthday?: number
  count?: number
  until?: string
}

/** P1 `alarmas_activas`. SPEC §4 P1. */
export interface AlarmaActiva {
  alarma_id: string
  titulo: string
  tipo: string
  hora: string
  repeticion: ProyeccionRepeticion | null
  proximo_disparo: string
}

/** Params de P1. `now` lo pone el servidor; ver SPEC §8 (agente). */
export interface ActivasParams {
  tz: string
}

/** Payload completo de alarma_creada.v2 / alarma_modificada.v2. */
export interface AlarmaPayload {
  alarma_id: string
  titulo: string
  tipo: TipoAlarma
  hora: string
  repeticion: Repeticion | null
  destinatario?: string
  remedio?: string
}

/** Ítem de `cauce.alarmas` v1 (SPEC §5). Sin alarma_id: se genera al importar. */
export interface BulkAlarma {
  titulo: string
  tipo: TipoAlarma
  hora: string
  repeticion: Repeticion | null
  destinatario?: string
  remedio?: string
}

/** P2 `adherencia_tratamiento` — una fila por alarma de tipo `remedio` (desactivadas incluidas), ordenada por `alarma_id`. SPEC §4 P2. */
export interface AdherenciaTratamiento {
  alarma_id: string
  destinatario: string
  remedio: string
  /** Disparos del calendario con instante ≤ now. */
  dosis_esperadas: number
  /** `programada_para` distintos con al menos un `dosis_dada`. */
  dosis_dadas: number
  /** `programada_para` distintos con `accion = ignorada` y sin dosis. */
  ignoradas: number
  /** Mayor `ts` de `dosis_dada`, o null. */
  ultima_dosis: string | null
}

/** Params de P2 (SPEC §4 P2). `now` lo pone el servidor; ver SPEC §8 (agente). */
export interface AdherenciaParams {
  tz: string
}

/** P3 `historial_dosis` — ordenada por `ts` desc, una entrada por (`alarma_id`, `programada_para`). SPEC §4 P3. */
export interface HistorialDosis {
  ts: string
  destinatario: string
  remedio: string
}

/** Params de P3 (SPEC §4 P3). */
export interface HistorialParams {
  destinatario?: string
}
