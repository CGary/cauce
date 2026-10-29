import { http, HttpResponse } from 'msw'
// Datos del mock = valores `expected` de los fixtures del contrato.
import hereda from '../../../../schema/features/calendario_google/fixtures/reuniones_proximas/caso_01_hereda_recordatorio.json'
import todoElDia from '../../../../schema/features/calendario_google/fixtures/reuniones_proximas/caso_05_todo_el_dia_08.json'
import apagado from '../../../../schema/features/calendario_google/fixtures/reuniones_proximas/caso_07_aviso_apagado.json'
import type { Reunion } from './types'

export const reunionesRows: Reunion[] = [
  ...hereda.expected,
  ...apagado.expected,
  ...todoElDia.expected,
] as Reunion[]

export const calendarioGoogleHandlers = [
  http.get('*/api/projections/calendario_google/reuniones_proximas', () =>
    HttpResponse.json(reunionesRows),
  ),
]
