/** Zona IANA del navegador; viaja como `params.tz` a las proyecciones que la piden. */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone
}

const dateTime = new Intl.DateTimeFormat('es', { dateStyle: 'medium', timeStyle: 'short' })

/** RFC 3339 UTC → fecha y hora local legible. Solo presentación. */
export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso))
}

const clock = new Intl.DateTimeFormat('es', { hour: '2-digit', minute: '2-digit', hour12: false })

/** RFC 3339 UTC → HH:MM en hora local. Solo presentación. */
export function formatClock(iso: string): string {
  return clock.format(new Date(iso))
}
