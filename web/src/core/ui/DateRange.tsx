import { inputClass } from './Field'

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
  return (
    <fieldset className="flex flex-wrap items-end gap-3 text-caption">
      <legend className="sr-only">Rango de fechas</legend>
      <label className="flex flex-col gap-1.5 font-medium">
        Desde
        <input
          type="date"
          className={inputClass}
          value={value.from}
          max={value.to || undefined}
          onChange={(e) => onChange({ ...value, from: e.target.value })}
        />
      </label>
      <label className="flex flex-col gap-1.5 font-medium">
        Hasta
        <input
          type="date"
          className={inputClass}
          value={value.to}
          min={value.from || undefined}
          onChange={(e) => onChange({ ...value, to: e.target.value })}
        />
      </label>
    </fieldset>
  )
}
