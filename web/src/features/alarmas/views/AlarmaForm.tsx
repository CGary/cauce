import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Button, Field, inputClass } from '../../../core/ui'
import type { AlarmaFormState } from '../model'
import type { AlarmaPayload } from '../types'
import {
  HOURLY_PRESETS,
  WEEKDAY_LABEL,
  WEEKDAYS,
  dosesForDays,
  formToPayload,
  validateForm,
} from '../model'

type FieldKey =
  | 'titulo'
  | 'hora'
  | 'destinatario'
  | 'remedio'
  | 'interval'
  | 'count'
  | 'until'
  | 'dias'
  | 'bymonthday'
  | 'ordinal'

/** Asocia el mensaje de validateForm con el campo que lo causó, para mostrarlo en línea. */
function fieldOf(message: string): FieldKey | null {
  const prefixes: [string, FieldKey][] = [
    ['El título', 'titulo'],
    ['La hora', 'hora'],
    ['El destinatario', 'destinatario'],
    ['El remedio', 'remedio'],
    ['El intervalo', 'interval'],
    ['La cantidad', 'count'],
    ['Hasta', 'until'],
    ['Los días', 'dias'],
    ['El día del mes', 'bymonthday'],
    ['Elegí', 'ordinal'],
  ]
  return prefixes.find(([prefix]) => message.startsWith(prefix))?.[1] ?? null
}

function Choice({ children }: { children: ReactNode }) {
  return <label className="flex min-h-11 items-center gap-3 text-body">{children}</label>
}

const checkClass = 'size-5 flex-none accent-primary'

/** Opción de un grupo segmentado: radio real (teclado y lector de pantalla), con aspecto de botón. */
const segmentClass =
  'flex min-h-11 min-w-14 cursor-pointer items-center justify-center rounded-control border border-line-strong bg-surface px-3 text-body font-medium tabular-nums text-ink has-checked:border-primary has-checked:bg-primary has-checked:text-on-primary has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-primary'

const INTERVAL_HINT: Record<'DAILY' | 'WEEKLY' | 'MONTHLY', string> = {
  DAILY: 'Cada cuántos días. Vacío es 1.',
  WEEKLY: 'Cada cuántas semanas. Vacío es 1.',
  MONTHLY: 'Cada cuántos meses. Vacío es 1.',
}

function dosesHint(dias: string, interval: string): string | undefined {
  const count = dosesForDays(dias, interval)
  return count === null ? undefined : `${count} ${count === 1 ? 'toma' : 'tomas'} en total.`
}

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
  const [otherHours, setOtherHours] = useState(
    initial.freq === 'HOURLY' && !(HOURLY_PRESETS as readonly string[]).includes(initial.interval),
  )
  const [error, setError] = useState<string | null>(null)
  const formRef = useRef<HTMLFormElement>(null)
  const set = <K extends keyof AlarmaFormState>(key: K, value: AlarmaFormState[K]) =>
    setForm((prev) => ({ ...prev, [key]: value }))

  const errorField = error ? fieldOf(error) : null
  const errorFor = (key: FieldKey) => (errorField === key ? error : null)

  useEffect(() => {
    if (error) formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
  }, [error])

  return (
    <form
      ref={formRef}
      noValidate
      aria-label={mode === 'crear' ? 'Nueva alarma' : 'Editar alarma'}
      className="flex flex-col gap-5"
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
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Título" error={errorFor('titulo')} className="sm:col-span-2">
          {(c) => (
            <input
              {...c}
              className={inputClass}
              value={form.titulo}
              maxLength={120}
              required
              onChange={(event) => set('titulo', event.target.value)}
            />
          )}
        </Field>
        <Field label="Tipo">
          {(c) => (
            <select
              {...c}
              className={inputClass}
              value={form.tipo}
              onChange={(event) => {
                const tipo = event.target.value as AlarmaFormState['tipo']
                // Un remedio casi siempre es "cada N horas": se propone cada 8 h si aún no hay repetición.
                if (tipo === 'remedio' && form.freq === 'una_vez') {
                  setForm((prev) => ({ ...prev, tipo, freq: 'HOURLY', interval: '8' }))
                  setOtherHours(false)
                } else {
                  set('tipo', tipo)
                }
              }}
            >
              <option value="remedio">Remedio</option>
              <option value="reunion">Reunión</option>
              <option value="otro">Otro</option>
            </select>
          )}
        </Field>
        <Field
          label="Hora"
          hint={form.freq === 'HOURLY' ? 'La primera toma.' : undefined}
          error={errorFor('hora')}
        >
          {(c) => (
            <input
              {...c}
              className={inputClass}
              type="time"
              step={60}
              value={form.hora}
              onChange={(event) => set('hora', event.target.value)}
            />
          )}
        </Field>
        <Field label="Repetición">
          {(c) => (
            <select
              {...c}
              className={inputClass}
              value={form.freq}
              onChange={(event) => {
                const freq = event.target.value as AlarmaFormState['freq']
                setForm((prev) => ({
                  ...prev,
                  freq,
                  limit: freq !== 'HOURLY' && prev.limit === 'days' ? 'none' : prev.limit,
                }))
              }}
            >
              <option value="una_vez">Una vez</option>
              <option value="DAILY">Diaria</option>
              <option value="WEEKLY">Semanal</option>
              <option value="MONTHLY">Mensual</option>
              <option value="HOURLY">Cada N horas</option>
            </select>
          )}
        </Field>
      </div>
      {form.freq === 'HOURLY' && (
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1.5 text-caption font-medium">Cada</legend>
          <div className="flex flex-wrap gap-2">
            {HOURLY_PRESETS.map((hours) => (
              <label key={hours} className={segmentClass}>
                <input
                  type="radio"
                  name="hourly-interval"
                  className="sr-only"
                  checked={!otherHours && form.interval === hours}
                  onChange={() => {
                    setOtherHours(false)
                    set('interval', hours)
                  }}
                />
                {hours} h
              </label>
            ))}
            <label className={segmentClass}>
              <input
                type="radio"
                name="hourly-interval"
                className="sr-only"
                checked={otherHours}
                onChange={() => setOtherHours(true)}
              />
              Otro
            </label>
          </div>
          {otherHours && (
            <Field label="Horas" error={errorFor('interval')} className="w-36">
              {(c) => (
                <input
                  {...c}
                  className={inputClass}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  value={form.interval}
                  placeholder="1"
                  onChange={(event) => set('interval', event.target.value)}
                />
              )}
            </Field>
          )}
          {!otherHours && errorField === 'interval' && (
            <p className="text-caption font-medium text-danger-ink">⚠ {error}</p>
          )}
        </fieldset>
      )}
      {form.freq !== 'una_vez' && (
        <div className="grid gap-4 sm:grid-cols-2">
          {form.freq !== 'HOURLY' && (
            <Field label="Intervalo" hint={INTERVAL_HINT[form.freq]} error={errorFor('interval')}>
              {(c) => (
                <input
                  {...c}
                  className={inputClass}
                  type="number"
                  min={1}
                  value={form.interval}
                  placeholder="1"
                  onChange={(event) => set('interval', event.target.value)}
                />
              )}
            </Field>
          )}
          <Field label={form.freq === 'HOURLY' ? 'Duración' : 'Límite'}>
            {(c) => (
              <select
                {...c}
                className={inputClass}
                value={form.limit}
                onChange={(event) => set('limit', event.target.value as AlarmaFormState['limit'])}
              >
                <option value="none">Sin fin</option>
                {form.freq === 'HOURLY' && <option value="days">Por días</option>}
                <option value="count">
                  {form.freq === 'HOURLY' ? 'Cantidad de tomas' : 'Cantidad'}
                </option>
                <option value="until">Hasta una fecha</option>
              </select>
            )}
          </Field>
          {form.limit === 'days' && form.freq === 'HOURLY' && (
            <Field label="Días" hint={dosesHint(form.dias, form.interval)} error={errorFor('dias')}>
              {(c) => (
                <input
                  {...c}
                  className={inputClass}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  value={form.dias}
                  placeholder="5"
                  onChange={(event) => set('dias', event.target.value)}
                />
              )}
            </Field>
          )}
          {form.limit === 'count' && (
            <Field label="Cantidad" error={errorFor('count')}>
              {(c) => (
                <input
                  {...c}
                  className={inputClass}
                  type="number"
                  min={1}
                  value={form.count}
                  onChange={(event) => set('count', event.target.value)}
                />
              )}
            </Field>
          )}
          {form.limit === 'until' && (
            <Field label="Hasta" hint="Incluye ese día." error={errorFor('until')}>
              {(c) => (
                <input
                  {...c}
                  className={inputClass}
                  type="date"
                  value={form.until}
                  onChange={(event) => set('until', event.target.value)}
                />
              )}
            </Field>
          )}
        </div>
      )}
      {form.freq === 'WEEKLY' && (
        <fieldset className="flex flex-wrap gap-x-5 gap-y-1">
          <legend className="mb-1 text-caption font-medium">Días</legend>
          {WEEKDAYS.map((day) => (
            <Choice key={day}>
              <input
                type="checkbox"
                className={checkClass}
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
            </Choice>
          ))}
        </fieldset>
      )}
      {form.freq === 'MONTHLY' && (
        <fieldset className="flex flex-col gap-1 rounded-control border border-line p-4">
          <legend className="px-1 text-caption font-medium">Mensual</legend>
          <p className="text-caption text-muted">Un mes sin ese día se salta.</p>
          <Choice>
            <input
              type="radio"
              name="monthly-mode"
              className={checkClass}
              checked={form.monthlyMode === 'bymonthday'}
              onChange={() => set('monthlyMode', 'bymonthday')}
            />
            Día del mes
          </Choice>
          {form.monthlyMode === 'bymonthday' && (
            <div className="flex flex-wrap items-end gap-4 pb-2 pl-8">
              <Field label="Día" error={errorFor('bymonthday')} className="w-28">
                {(c) => (
                  <input
                    {...c}
                    className={inputClass}
                    type="number"
                    min={1}
                    max={31}
                    disabled={form.bymonthday === '-1'}
                    value={form.bymonthday === '-1' ? '' : form.bymonthday}
                    onChange={(event) => set('bymonthday', event.target.value)}
                  />
                )}
              </Field>
              <Choice>
                <input
                  type="checkbox"
                  className={checkClass}
                  checked={form.bymonthday === '-1'}
                  onChange={(event) => set('bymonthday', event.target.checked ? '-1' : '1')}
                />
                Último día
              </Choice>
            </div>
          )}
          <Choice>
            <input
              type="radio"
              name="monthly-mode"
              className={checkClass}
              checked={form.monthlyMode === 'byday'}
              onChange={() => set('monthlyMode', 'byday')}
            />
            Día de la semana
          </Choice>
          {form.monthlyMode === 'byday' && (
            <div className="grid gap-4 pb-2 pl-8 sm:grid-cols-2">
              <Field label="Ordinal" error={errorFor('ordinal')}>
                {(c) => (
                  <select
                    {...c}
                    className={inputClass}
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
                )}
              </Field>
              <Field label="Día">
                {(c) => (
                  <select
                    {...c}
                    className={inputClass}
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
                )}
              </Field>
            </div>
          )}
        </fieldset>
      )}
      {form.tipo === 'remedio' && (
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Destinatario" error={errorFor('destinatario')}>
            {(c) => (
              <input
                {...c}
                className={inputClass}
                value={form.destinatario}
                maxLength={60}
                onChange={(event) => set('destinatario', event.target.value)}
              />
            )}
          </Field>
          <Field label="Remedio" error={errorFor('remedio')}>
            {(c) => (
              <input
                {...c}
                className={inputClass}
                value={form.remedio}
                maxLength={120}
                onChange={(event) => set('remedio', event.target.value)}
              />
            )}
          </Field>
        </div>
      )}
      {error && errorField === null && (
        <p className="text-caption font-medium text-danger-ink">⚠ {error}</p>
      )}
      <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
        <Button onClick={onCancel}>Cancelar</Button>
        <Button type="submit" variant="primary" pending={pending}>
          {mode === 'crear' ? 'Crear' : 'Guardar'}
        </Button>
      </div>
    </form>
  )
}
