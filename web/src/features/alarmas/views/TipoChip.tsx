const TIPOS = {
  remedio: {
    label: 'Remedio',
    cls: 'bg-success-soft text-success-ink',
    // Cápsula
    path: 'M7.5 4.5a4 4 0 0 1 5.7 0l1.3 1.3a4 4 0 0 1 0 5.7l-5 5a4 4 0 0 1-5.7 0L2.5 15.2a4 4 0 0 1 0-5.7zM6 11l4-4',
  },
  reunion: {
    label: 'Reunión',
    cls: 'bg-info-soft text-info-ink',
    // Calendario
    path: 'M4 5h12v11H4zM4 8.5h12M7 3v3M13 3v3',
  },
  otro: {
    label: 'Otro',
    cls: 'bg-surface-2 text-ink',
    // Campana
    path: 'M5 14V9a5 5 0 0 1 10 0v5l1.5 1.5h-13zM8.5 17.5h3',
  },
} as const

/** Tipo de alarma con icono y color propios; la palabra acompaña al icono. */
export function TipoChip({ tipo }: { tipo: string }) {
  const t = TIPOS[tipo as keyof typeof TIPOS] ?? { ...TIPOS.otro, label: tipo }
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-caption font-medium ${t.cls}`}
    >
      <svg
        aria-hidden="true"
        viewBox="0 0 20 20"
        className="size-4 flex-none"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d={t.path} />
      </svg>
      {t.label}
    </span>
  )
}
