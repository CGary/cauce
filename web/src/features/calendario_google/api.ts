import { projectionQuery } from '../../core/api'
import type { Reunion, ReunionesParams } from './types'

export const reunionesQuery = (params: ReunionesParams) =>
  projectionQuery<Reunion[]>('calendario_google', 'reuniones_proximas', { tz: params.tz })
