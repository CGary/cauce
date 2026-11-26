import { http, HttpResponse } from 'msw'

/** Mock de `/sync/devices` para el modo mock y los tests: un teléfono que sincronizó hace poco. */
export const syncHandlers = [
  http.get('*/sync/devices', () => {
    const now = new Date()
    const ago = (min: number) => new Date(now.getTime() - min * 60_000).toISOString()
    return HttpResponse.json({
      now: now.toISOString(),
      server_seq: 100,
      devices: [
        {
          device_id: 'phone-1',
          last_push_at: ago(12),
          last_pull_at: ago(3),
          pulled_through_seq: 100,
        },
      ],
    })
  }),
]
