export function Spinner({ label = 'Cargando…' }: { label?: string }) {
  return (
    <div role="status" className="flex items-center gap-2 py-6 text-sm text-stone-600">
      <span
        className="size-4 animate-spin rounded-full border-2 border-stone-300 border-t-teal-700 motion-reduce:animate-none"
        aria-hidden="true"
      />
      {label}
    </div>
  )
}
