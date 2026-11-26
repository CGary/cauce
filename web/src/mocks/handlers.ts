import { syncHandlers } from '../core/mocks'
import { alarmasHandlers } from '../features/alarmas/mocks'
import { calendarioGoogleHandlers } from '../features/calendario_google/mocks'

export const handlers = [...syncHandlers, ...alarmasHandlers, ...calendarioGoogleHandlers]
