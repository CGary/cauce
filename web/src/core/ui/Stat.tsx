export function Stat({
  label,
  value,
  hint,
}: {
  label: string
  value: string | number
  hint?: string
}) {
  return (
    <div className="flex flex-col gap-0.5 rounded-control bg-primary-soft px-4 py-3 text-primary-ink sm:w-fit sm:min-w-40">
      <span className="text-caption font-medium">{label}</span>
      <span className="text-title font-semibold tabular-nums">{value}</span>
      {hint && <span className="text-caption">{hint}</span>}
    </div>
  )
}
