import { projectionQuery } from '../../core/api'
import type {
  AdherenciaParams,
  AdherenciaTratamiento,
  HistorialDosis,
  HistorialParams,
} from './types'

export const adherenciaQuery = (params: AdherenciaParams) =>
  projectionQuery<AdherenciaTratamiento[]>('alarmas', 'adherencia_tratamiento', { tz: params.tz })

export const historialQuery = (params: HistorialParams) =>
  projectionQuery<HistorialDosis[]>('alarmas', 'historial_dosis', {
    destinatario: params.destinatario,
  })
