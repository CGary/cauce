import { useState } from 'react'
import type { AlarmaFormState } from '../model'
import type { AlarmaPayload } from '../types'
import { WEEKDAY_LABEL, WEEKDAYS, formToPayload, validateForm } from '../model'

const field = 'rounded-md border border-stone-300 bg-white px-2 py-1 text-sm'
const labelClass = 'flex flex-col gap-1 text-sm'

export function AlarmaForm({
  mode,
  alarmaId,
  initial,
  pending,
  onSubmit,
  onCancel,
}: {
  mode: 'crear' | 'editar'
  alarmaId: string
  initial: AlarmaFormState
  pending: boolean
  onSubmit: (payload: AlarmaPayload) => void
  onCancel: () => void
}) {
  const [form, setForm] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const set = <K extends keyof AlarmaFormState>(key: K, value: AlarmaFormState[K]) =>
    setForm((prev) => ({ ...prev, [key]: value }))

  return (
    <form
      aria-label={mode === 'crear' ? 'Nueva alarma' : 'Editar alarma'}
      className="flex flex-col gap-3"
      onSubmit={(event) => {
        event.preventDefault()
        const message = validateForm(form)
        if (message) {
          setError(message)
          return
        }
        setError(null)
        onSubmit(formToPayload(form, alarmaId))
      }}
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <label className={labelClass}>
          Título
          <input
            className={field}
            value={form.titulo}
            maxLength={120}
            required
            onChange={(event) => set('titulo', event.target.value)}
          />
        </label>
        <label className={labelClass}>
          Tipo
          <select
            className={field}
            value={form.tipo}
            onChange={(event) => set('tipo', event.target.value as AlarmaFormState['tipo'])}
          >
            <option value="remedio">Remedio</option>
            <option value="reunion">Reunión</option>
            <option value="otro">Otro</option>
          </select>
        </label>
        <label className={labelClass}>
          Hora
          <input
            className={field}
            value={form.hora}
            inputMode="numeric"
            placeholder="08:00"
            onChange={(event) => set('hora', event.target.value)}
          />
        </label>
        <label className={labelClass}>
          Repetición
          <select
            className={field}
            value={form.freq}
            onChange={(event) => set('freq', event.target.value as AlarmaFormState['freq'])}
          >
            <option value="una_vez">Una vez</option>
            <option value="DAILY">Diaria</option>
            <option value="WEEKLY">Semanal</option>
            <option value="MONTHLY">Mensual</option>
            <option value="HOURLY">Cada hora</option>
          </select>
        </label>
      </div>
      {form.freq !== 'una_vez' && (
        <div className="grid gap-3 sm:grid-cols-2">
          <label className={labelClass}>
            Intervalo
            <input
              className={field}
              type="number"
              min={1}
              value={form.interval}
              placeholder="1"
              onChange={(event) => set('interval', event.target.value)}
            />
          </label>
          <label className={labelClass}>
            Límite
            <select
              className={field}
              value={form.limit}
              onChange={(event) => set('limit', event.target.value as AlarmaFormState['limit'])}
            >
              <option value="none">Sin fin</option>
              <option value="count">Cantidad</option>
              <option value="until">Hasta</option>
            </select>
          </label>
          {form.limit === 'count' && (
            <label className={labelClass}>
              Cantidad
              <input
                className={field}
                type="number"
                min={1}
                value={form.count}
                onChange={(event) => set('count', event.target.value)}
              />
            </label>
          )}
          {form.limit === 'until' && (
            <label className={labelClass}>
              Hasta
              <input
                className={field}
                value={form.until}
                placeholder="2026-12-31"
                onChange={(event) => set('until', event.target.value)}
              />
            </label>
          )}
        </div>
      )}
      {form.freq === 'WEEKLY' && (
        <fieldset className="flex flex-wrap gap-3 text-sm">
          <legend className="text-sm">Días</legend>
          {WEEKDAYS.map((day) => (
            <label key={day} className="flex items-center gap-1">
              <input
                type="checkbox"
                checked={form.weekdays.includes(day)}
                onChange={(event) =>
                  set(
                    'weekdays',
                    event.target.checked
                      ? [...form.weekdays, day]
                      : form.weekdays.filter((item) => item !== day),
                  )
                }
              />
              {WEEKDAY_LABEL[day]}
            </label>
          ))}
        </fieldset>
      )}
      {form.freq === 'MONTHLY' && (
        <fieldset className="flex flex-col gap-3 text-sm">
          <legend className="text-sm">Mensual</legend>
          <p className="text-stone-600">Un mes sin ese día se salta.</p>
          <label className="flex items-center gap-2">
            <input
              type="radio"
              name="monthly-mode"
              checked={form.monthlyMode === 'bymonthday'}
              onChange={() => set('monthlyMode', 'bymonthday')}
            />
            Día del mes
          </label>
          {form.monthlyMode === 'bymonthday' && (
            <div className="flex flex-wrap items-center gap-3">
              <label className={labelClass}>
                Día
                <input
                  className={`${field} w-20`}
                  type="number"
                  min={1}
                  max={31}
                  disabled={form.bymonthday === '-1'}
                  value={form.bymonthday === '-1' ? '' : form.bymonthday}
                  onChange={(event) => set('bymonthday', event.target.value)}
                />
              </label>
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={form.bymonthday === '-1'}
                  onChange={(event) => set('bymonthday', event.target.checked ? '-1' : '1')}
                />
                Último día
              </label>
            </div>
          )}
          <label className="flex items-center gap-2">
            <input
              type="radio"
              name="monthly-mode"
              checked={form.monthlyMode === 'byday'}
              onChange={() => set('monthlyMode', 'byday')}
            />
            Día de la semana
          </label>
          {form.monthlyMode === 'byday' && (
            <div className="flex flex-wrap gap-3">
              <label className={labelClass}>
                Ordinal
                <select
                  className={field}
                  value={form.ordinal}
                  onChange={(event) => set('ordinal', event.target.value)}
                >
                  <option value="1">1.º</option>
                  <option value="2">2.º</option>
                  <option value="3">3.º</option>
                  <option value="4">4.º</option>
                  <option value="5">5.º</option>
                  <option value="-1">Último</option>
                </select>
              </label>
              <label className={labelClass}>
                Día
                <select
                  className={field}
                  value={form.ordinalDay}
                  onChange={(event) =>
                    set('ordinalDay', event.target.value as AlarmaFormState['ordinalDay'])
                  }
                >
                  {WEEKDAYS.map((day) => (
                    <option key={day} value={day}>
                      {WEEKDAY_LABEL[day]}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          )}
        </fieldset>
      )}
      {form.tipo === 'remedio' && (
        <div className="grid gap-3 sm:grid-cols-2">
          <label className={labelClass}>
            Destinatario
            <input
              className={field}
              value={form.destinatario}
              maxLength={60}
              onChange={(event) => set('destinatario', event.target.value)}
            />
          </label>
          <label className={labelClass}>
            Remedio
            <input
              className={field}
              value={form.remedio}
              maxLength={120}
              onChange={(event) => set('remedio', event.target.value)}
            />
          </label>
        </div>
      )}
      {error && <p className="text-sm text-red-800">{error}</p>}
      <div className="flex gap-2">
        <button
          type="submit"
          disabled={pending}
          className="rounded-md bg-teal-700 px-3 py-1.5 text-sm font-medium text-white hover:bg-teal-800 disabled:opacity-50"
        >
          {mode === 'crear' ? 'Crear' : 'Guardar'}
        </button>
        <button
          type="button"
          className="rounded-md border border-stone-300 bg-white px-3 py-1.5 text-sm"
          onClick={onCancel}
        >
          Cancelar
        </button>
      </div>
    </form>
  )
}
