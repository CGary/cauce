/** Estado de carga: esqueleto que anuncia «Cargando…» una sola vez a lector de pantalla. */
export function Spinner({ label = 'Cargando…' }: { label?: string }) {
  return (
    <div role="status" className="flex flex-col gap-3 py-2">
      <span className="sr-only">{label}</span>
      <div aria-hidden="true" className="flex flex-col gap-3">
        {[0, 1, 2].map((i) => (
          <div
            key={i}
            className="h-12 animate-pulse rounded-control bg-surface-2 motion-reduce:animate-none"
            style={{ opacity: 1 - i * 0.2 }}
          />
        ))}
      </div>
    </div>
  )
}
