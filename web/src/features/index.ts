import type { FeatureModule } from '../core/feature'
import { alarmas } from './alarmas'

/** Registro explícito, en el orden de la navegación (igual que en Android). */
export const FEATURES: FeatureModule[] = [alarmas]
