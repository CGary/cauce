export type AvisoOrigen = 'google' | 'cauce' | 'defecto'

export interface Aviso {
  origen: AvisoOrigen
  minutos_antes?: number
  hora?: string
  apagado?: true
}

/** Fila de P1 `reuniones_proximas`. */
export interface Reunion {
  reunion_id: string
  serie_id: string
  cuenta: string
  titulo: string
  inicio: string
  fin: string
  todo_el_dia: boolean
  aviso: Aviso
  aviso_en: string | null
}

export interface ReunionesParams {
  tz: string
}
