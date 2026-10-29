import { alarmasHandlers } from '../features/alarmas/mocks'
import { calendarioGoogleHandlers } from '../features/calendario_google/mocks'

export const handlers = [...alarmasHandlers, ...calendarioGoogleHandlers]
