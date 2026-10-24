import { projectionQuery } from '../../core/api'
import type {
  ActivasParams,
  AdherenciaParams,
  AdherenciaTratamiento,
  AlarmaActiva,
  HistorialDosis,
  HistorialParams,
} from './types'

export const activasQuery = (params: ActivasParams) =>
  projectionQuery<AlarmaActiva[]>('alarmas', 'alarmas_activas', { tz: params.tz })

export const adherenciaQuery = (params: AdherenciaParams) =>
  projectionQuery<AdherenciaTratamiento[]>('alarmas', 'adherencia_tratamiento', { tz: params.tz })

export const historialQuery = (params: HistorialParams) =>
  projectionQuery<HistorialDosis[]>('alarmas', 'historial_dosis', {
    destinatario: params.destinatario,
  })
