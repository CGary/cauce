const TONES = {
  good: { bar: 'bg-success', ink: 'text-success-ink', label: 'Al día' },
  warn: { bar: 'bg-warning', ink: 'text-warning-ink', label: 'Revisar' },
  bad: { bar: 'bg-danger', ink: 'text-danger-ink', label: 'Baja' },
} as const

/** Porcentaje con barra y palabra: el estado nunca depende solo del color. */
export function Meter({
  value,
  label,
  tone,
}: {
  /** 0..100 */
  value: number
  label: string
  tone: keyof typeof TONES
}) {
  const pct = Math.max(0, Math.min(100, Math.round(value)))
  const t = TONES[tone]
  return (
    <div className="flex min-w-32 flex-col gap-1">
      <div className="flex items-baseline justify-between gap-3">
        <span className="font-semibold tabular-nums">{pct}%</span>
        <span className={`text-caption font-medium ${t.ink}`}>{t.label}</span>
      </div>
      <div
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={pct}
        aria-valuetext={`${pct}% · ${t.label}`}
        className="h-2 overflow-hidden rounded-full bg-surface-2"
      >
        <div className={`h-full rounded-full ${t.bar}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}
