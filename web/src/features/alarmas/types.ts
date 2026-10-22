// Formas de salida de las proyecciones de alarmas, campo por campo.
// Fuente: schema/features/alarmas/SPEC.md §4. Todo datetime es RFC 3339 en UTC ("...Z").

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
