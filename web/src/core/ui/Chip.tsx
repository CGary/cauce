import type { ReactNode } from 'react'

const TONES = {
  neutral: { cls: 'bg-surface-2 text-ink', glyph: '○' },
  good: { cls: 'bg-success-soft text-success-ink', glyph: '●' },
  warn: { cls: 'bg-warning-soft text-warning-ink', glyph: '▲' },
  bad: { cls: 'bg-danger-soft text-danger-ink', glyph: '■' },
  info: { cls: 'bg-info-soft text-info-ink', glyph: '◆' },
} as const

/** El glifo cambia de forma con el tono: la señal no depende solo del color. */
export function Chip({
  tone = 'neutral',
  children,
}: {
  tone?: keyof typeof TONES
  children: ReactNode
}) {
  const t = TONES[tone]
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-caption font-medium tabular-nums ${t.cls}`}
    >
      <span aria-hidden="true" className="text-[0.625rem] leading-none">
        {t.glyph}
      </span>
      {children}
    </span>
  )
}
