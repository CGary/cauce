import { useState } from 'react'
import { Button, Field, inputClass } from '../../../core/ui'
import { avisoPayload, initialForm, type AvisoForm as FormState, type AvisoModo } from '../model'
import type { Reunion } from '../types'

export function AvisoForm({
  reunion,
  pending,
  onSubmit,
  onCancel,
}: {
  reunion: Reunion
  pending: boolean
  onSubmit: (payload: Record<string, unknown>) => void
  onCancel: () => void
}) {
  const [form, setForm] = useState<FormState>(() => initialForm(reunion))
  const [error, setError] = useState<string | null>(null)

  const modos: { value: AvisoModo; label: string }[] = [
    reunion.todo_el_dia
      ? { value: 'hora', label: 'A una hora' }
      : { value: 'minutos_antes', label: 'Minutos antes' },
    { value: 'apagado', label: 'Apagado' },
  ]

  return (
    <form
      aria-label={`Aviso de ${reunion.titulo}`}
      className="flex flex-col gap-3 sm:flex-row sm:items-end"
      onSubmit={(event) => {
        event.preventDefault()
        const result = avisoPayload(reunion.serie_id, form)
        if (!result.ok) {
          setError(result.error)
          return
        }
        setError(null)
        onSubmit(result.payload)
      }}
    >
      <Field label="Aviso">
        {(control) => (
          <select
            {...control}
            className={inputClass}
            value={form.modo}
            onChange={(e) => setForm({ ...form, modo: e.target.value as AvisoModo })}
          >
            {modos.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        )}
      </Field>
      {form.modo === 'minutos_antes' && (
        <Field label="Minutos antes" error={error}>
          {(control) => (
            <input
              {...control}
              type="text"
              inputMode="numeric"
              className={inputClass}
              value={form.minutos}
              onChange={(e) => setForm({ ...form, minutos: e.target.value })}
            />
          )}
        </Field>
      )}
      {form.modo === 'hora' && (
        <Field label="Hora" error={error}>
          {(control) => (
            <input
              {...control}
              type="time"
              className={inputClass}
              value={form.hora}
              onChange={(e) => setForm({ ...form, hora: e.target.value })}
            />
          )}
        </Field>
      )}
      <div className="flex gap-2">
        <Button type="submit" variant="primary" pending={pending}>
          Guardar aviso
        </Button>
        <Button onClick={onCancel}>Cancelar</Button>
      </div>
    </form>
  )
}
