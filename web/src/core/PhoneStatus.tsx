import {
  phones,
  usePending,
  usePendingResolver,
  useDevices,
  type DeviceSync,
  type DevicesResponse,
} from './devices'
import { formatAgo, useNow } from './sync'
import { formatClock } from './time'

type Tone = 'success' | 'warning' | 'danger'

const GREEN_MS = 30 * 60_000
const AMBER_MS = 6 * 60 * 60_000

const TONE_CLASS: Record<Tone, string> = {
  success: 'border-success bg-success-soft text-success-ink',
  warning: 'border-warning/60 bg-warning-soft text-warning-ink',
  danger: 'border-danger bg-danger-soft text-danger-ink',
}
const TONE_ICON: Record<Tone, string> = { success: '●', warning: '▲', danger: '✕' }

function latest(values: (string | null)[]): number | null {
  let best: number | null = null
  for (const v of values) {
    if (v === null) continue
    const t = Date.parse(v)
    if (best === null || t > best) best = t
  }
  return best
}

/** Verde < 30 min, ámbar < 6 h, rojo si no o nunca. `age` en ms desde el último contacto, o null. */
export function toneFor(age: number | null): Tone {
  if (age === null) return 'danger'
  if (age < GREEN_MS) return 'success'
  if (age < AMBER_MS) return 'warning'
  return 'danger'
}

/** Resumen de todos los teléfonos: último push y último pull. */
export function summarize(devices: DeviceSync[]) {
  return {
    push: latest(devices.map((d) => d.last_push_at)),
    pull: latest(devices.map((d) => d.last_pull_at)),
  }
}

/**
 * Referencia de «ahora» a partir del reloj del servidor, para no depender de que el reloj del
 * navegador coincida con el del teléfono/servidor: `now` del servidor + lo transcurrido desde la lectura.
 */
function referenceNow(data: DevicesResponse, updatedAt: number, clientNow: number): number {
  return Date.parse(data.now) + Math.max(0, clientNow - updatedAt)
}

/** Tarjeta «Teléfono»: cuándo subió y bajó por última vez. Verde/ámbar/rojo según el último contacto. */
export function PhoneCard() {
  const devices = useDevices()
  const clientNow = useNow()
  const base =
    'inline-flex w-fit max-w-full flex-wrap items-center gap-x-3 gap-y-1 rounded-control border px-3 py-2 text-caption font-medium'

  if (devices.data === undefined) {
    return (
      <section
        aria-label="Teléfono"
        className={`${base} border-line bg-surface-2 text-muted`}
        data-tone="none"
      >
        <span className="font-semibold">Teléfono</span>
        <span>{devices.isError ? 'No se pudo leer el estado' : 'Consultando…'}</span>
      </section>
    )
  }

  const now = referenceNow(devices.data, devices.dataUpdatedAt, clientNow)
  const { push, pull } = summarize(phones(devices.data))
  const last = push === null ? pull : pull === null ? push : Math.max(push, pull)
  const tone = toneFor(last === null ? null : Math.max(0, now - last))
  const ago = (t: number | null) => (t === null ? 'nunca' : formatAgo(t, now))
  return (
    <section aria-label="Teléfono" className={`${base} ${TONE_CLASS[tone]}`} data-tone={tone}>
      <span className="font-semibold">
        <span aria-hidden="true">{TONE_ICON[tone]} </span>Teléfono
      </span>
      {last === null ? (
        <span>Nunca ha sincronizado</span>
      ) : (
        <span>
          Subió {ago(push)} · Bajó {ago(pull)}
        </span>
      )}
    </section>
  )
}

/** Aviso tras un push de la web: «aún no lo recibe» hasta que un teléfono baje ese cambio. */
export function PhoneNotice() {
  const devices = useDevices()
  const pending = usePending()
  usePendingResolver(devices.data)
  if (!pending) return null
  if (pending.receivedAt !== undefined) {
    return (
      <p
        role="status"
        className="rounded-control border border-success bg-success-soft px-3 py-2 text-caption font-medium text-success-ink"
      >
        <span aria-hidden="true">✓ </span>En el teléfono desde {formatClock(pending.receivedAt)}
      </p>
    )
  }
  return (
    <p
      role="status"
      className="rounded-control border border-warning/60 bg-warning-soft px-3 py-2 text-caption font-medium text-warning-ink"
    >
      <span aria-hidden="true">⏳ </span>El teléfono aún no lo recibe
    </p>
  )
}
