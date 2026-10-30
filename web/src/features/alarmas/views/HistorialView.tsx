import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { browserTimeZone } from '../../../core/time'
import { Button, Card, Field, Page, QueryState, SectionHeader, inputClass } from '../../../core/ui'
import { adherenciaQuery, historialQuery } from '../api'
import { formatDia, formatHoraLocal, groupByDay } from '../format'
import { useDocumentTitle } from '../title'

/** Filas visibles de a tandas: el historial crece sin límite. */
const PAGE_SIZE = 50

/** P3 `historial_dosis`; el destinatario viaja como param de la proyección. */
export default function HistorialView() {
  useDocumentTitle('Historial de dosis')
  const [search, setSearch] = useSearchParams()
  const destinatario = search.get('destinatario') ?? ''
  const query = useQuery(historialQuery({ destinatario: destinatario || undefined }))
  // Mismas opciones que en Adherencia: los destinatarios que conoce la proyección P2.
  const adherencia = useQuery(adherenciaQuery({ tz: browserTimeZone() }))
  const options = [...new Set((adherencia.data ?? []).map((r) => r.destinatario))].sort()
  if (destinatario && !options.includes(destinatario)) options.push(destinatario)
  const [shown, setShown] = useState(PAGE_SIZE)

  return (
    <Page
      title="Historial de dosis"
      description="Cada dosis registrada como dada, con la fecha en tu zona horaria."
    >
      <Card className="flex flex-col gap-5">
        <SectionHeader title="Dosis dadas" description="Las más recientes primero.">
          <Field label="Destinatario" className="w-full sm:w-56">
            {(c) => (
              <select
                {...c}
                className={inputClass}
                value={destinatario}
                onChange={(e) => {
                  setShown(PAGE_SIZE)
                  setSearch(e.target.value ? { destinatario: e.target.value } : {})
                }}
              >
                <option value="">Todos</option>
                {options.map((d) => (
                  <option key={d} value={d}>
                    {d}
                  </option>
                ))}
              </select>
            )}
          </Field>
        </SectionHeader>
        <QueryState
          query={query}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            title: destinatario
              ? `Sin dosis para ${destinatario}`
              : 'Todavía no hay dosis registradas',
            description: 'Se registran al tocar «Hecho» en una alarma de tipo remedio.',
          }}
        >
          {(rows) => {
            const visibles = rows.slice(0, shown)
            return (
              <div className="flex flex-col gap-5">
                {groupByDay(visibles, (r) => r.ts).map((group) => (
                  <section key={group.dia} aria-label={formatDia(group.dia)}>
                    <h3 className="pb-1 text-caption font-semibold capitalize text-muted">
                      {formatDia(group.dia)}
                    </h3>
                    <ul className="flex flex-col divide-y divide-line border-y border-line">
                      {group.rows.map((r) => (
                        <li
                          key={`${r.ts}|${r.destinatario}|${r.remedio}`}
                          className="flex flex-wrap items-baseline gap-x-4 gap-y-0.5 px-1 py-3"
                        >
                          <span className="font-semibold tabular-nums">
                            {formatHoraLocal(r.ts)}
                          </span>
                          <span>{r.destinatario}</span>
                          <span className="text-muted">{r.remedio}</span>
                        </li>
                      ))}
                    </ul>
                  </section>
                ))}
                {rows.length > shown && (
                  <Button onClick={() => setShown((n) => n + PAGE_SIZE)}>
                    Cargar más ({rows.length - shown} restantes)
                  </Button>
                )}
              </div>
            )
          }}
        </QueryState>
      </Card>
    </Page>
  )
}
