/** Zona IANA del navegador; viaja como `params.tz` a las proyecciones que la piden. */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone
}

const dateTime = new Intl.DateTimeFormat('es', { dateStyle: 'medium', timeStyle: 'short' })

/** RFC 3339 UTC → fecha y hora local legible. Solo presentación. */
export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso))
}
