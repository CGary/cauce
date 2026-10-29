import { http, HttpResponse } from 'msw'
// Datos del mock = valores `expected` de los fixtures del contrato, para que la UI vea las formas reales.
import activas from '../../../../schema/features/alarmas/fixtures/alarmas_activas/caso_01_diaria.json'
import gcal from '../../../../schema/features/alarmas/fixtures/alarmas_activas/caso_11_reunion_google.json'
import adherencia from '../../../../schema/features/alarmas/fixtures/adherencia_tratamiento/caso_02_con_ignoradas.json'
import historial from '../../../../schema/features/alarmas/fixtures/historial_dosis/caso_01_filtro_destinatario.json'
import type { AdherenciaTratamiento, AlarmaActiva, HistorialDosis } from './types'

const activasRows = activas.expected satisfies AlarmaActiva[]
// Una reunión de Google (2.10): P1 la agrega como entrada de solo lectura.
const gcalRows = gcal.expected.filter((row) => row.alarma_id.startsWith('gcal:')) satisfies
  AlarmaActiva[]
const adherenciaRows = adherencia.expected satisfies AdherenciaTratamiento[]
const historialRows = historial.expected satisfies HistorialDosis[]

export const alarmasHandlers = [
  http.get('*/api/projections/alarmas/alarmas_activas', () =>
    HttpResponse.json([...activasRows, ...gcalRows]),
  ),
  http.get('*/api/projections/alarmas/adherencia_tratamiento', () =>
    HttpResponse.json(adherenciaRows),
  ),
  http.get('*/api/projections/alarmas/historial_dosis', ({ request }) => {
    const destinatario = new URL(request.url).searchParams.get('destinatario')
    return HttpResponse.json(
      destinatario ? historialRows.filter((r) => r.destinatario === destinatario) : historialRows,
    )
  }),
]
