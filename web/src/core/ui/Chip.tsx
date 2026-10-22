import type { ReactNode } from 'react'

const TONES = {
  neutral: 'bg-stone-100 text-stone-700',
  good: 'bg-emerald-100 text-emerald-800',
  warn: 'bg-amber-100 text-amber-900',
  bad: 'bg-red-100 text-red-800',
} as const

export function Chip({
  tone = 'neutral',
  children,
}: {
  tone?: keyof typeof TONES
  children: ReactNode
}) {
  return (
    <span
      className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium tabular-nums ${TONES[tone]}`}
    >
      {children}
    </span>
  )
}
