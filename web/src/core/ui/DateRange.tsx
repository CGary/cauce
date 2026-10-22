export interface DateRangeValue {
  from: string
  to: string
}

/** Rango de fechas locales (YYYY-MM-DD). Controlado: el estado vive en la URL de la vista. */
export function DateRange({
  value,
  onChange,
}: {
  value: DateRangeValue
  onChange: (value: DateRangeValue) => void
}) {
  const input = 'rounded-md border border-stone-300 bg-white px-2 py-1 text-sm'
  return (
    <fieldset className="flex flex-wrap items-center gap-2 text-sm">
      <legend className="sr-only">Rango de fechas</legend>
      <label className="flex items-center gap-1">
        Desde
        <input
          type="date"
          className={input}
          value={value.from}
          max={value.to || undefined}
          onChange={(e) => onChange({ ...value, from: e.target.value })}
        />
      </label>
      <label className="flex items-center gap-1">
        Hasta
        <input
          type="date"
          className={input}
          value={value.to}
          min={value.from || undefined}
          onChange={(e) => onChange({ ...value, to: e.target.value })}
        />
      </label>
    </fieldset>
  )
}
