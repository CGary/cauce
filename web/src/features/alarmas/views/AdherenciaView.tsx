import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { browserTimeZone } from '../../../core/time'
import {
  Card,
  Chip,
  Field,
  Meter,
  Page,
  QueryState,
  SectionHeader,
  Table,
  inputClass,
  type Column,
} from '../../../core/ui'
import { adherenciaQuery } from '../api'
import { formatFechaHora } from '../format'
import { useDocumentTitle } from '../title'
import type { AdherenciaTratamiento } from '../types'

/** Presentación: dadas sobre esperadas, con umbrales solo para elegir el tono de la barra. */
function Adherencia({ row }: { row: AdherenciaTratamiento }) {
  if (row.dosis_esperadas <= 0) return <span className="text-muted">Sin dosis esperadas</span>
  const pct = (row.dosis_dadas / row.dosis_esperadas) * 100
  return (
    <Meter
      value={pct}
      tone={pct >= UMBRAL_AL_DIA ? 'good' : pct >= UMBRAL_REVISAR ? 'warn' : 'bad'}
      label={`Adherencia de ${row.destinatario}, ${row.remedio}`}
    />
  )
}

/** Umbrales de la barra, solo de presentación: el servidor entrega conteos, no un estado (SPEC §8). */
const UMBRAL_AL_DIA = 90
const UMBRAL_REVISAR = 70

const columns: Column<AdherenciaTratamiento>[] = [
  {
    header: 'Destinatario',
    render: (r) => (
      <Link
        to={{
          pathname: '/alarmas/historial',
          search: `?destinatario=${encodeURIComponent(r.destinatario)}`,
        }}
        className="font-medium text-primary-ink underline underline-offset-4"
        aria-label={`${r.destinatario}: ver historial de dosis`}
      >
        {r.destinatario}
      </Link>
    ),
  },
  { header: 'Remedio', render: (r) => r.remedio },
  { header: 'Adherencia', render: (r) => <Adherencia row={r} /> },
  { header: 'Esperadas', align: 'right', render: (r) => r.dosis_esperadas },
  { header: 'Dadas', align: 'right', render: (r) => r.dosis_dadas },
  {
    header: 'Ignoradas',
    align: 'right',
    render: (r) => <Chip tone={r.ignoradas > 0 ? 'warn' : 'neutral'}>{r.ignoradas}</Chip>,
  },
  {
    header: 'Última dosis',
    render: (r) => (r.ultima_dosis ? formatFechaHora(r.ultima_dosis) : '—'),
  },
]

/** P2 `adherencia_tratamiento`, con filtro por destinatario. */
export default function AdherenciaView() {
  const [search, setSearch] = useSearchParams()
  const destinatario = search.get('destinatario') ?? ''
  useDocumentTitle('Adherencia por tratamiento')
  const query = useQuery(adherenciaQuery({ tz: browserTimeZone() }))

  return (
    <Page
      title="Adherencia por tratamiento"
      description="Cuántas dosis esperadas se dieron, por destinatario y remedio."
    >
      <QueryState
        query={query}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          title: 'Todavía no hay tratamientos',
          description: 'Aparecen cuando se crea una alarma de tipo remedio.',
        }}
      >
        {(rows) => {
          // Filtro de presentación: P2 no recibe destinatario como param (SPEC §8, agente).
          const destinatarios = [...new Set(rows.map((r) => r.destinatario))].sort()
          const visibles = destinatario ? rows.filter((r) => r.destinatario === destinatario) : rows
          return (
            <Card className="flex flex-col gap-5">
              <SectionHeader
                title="Tratamientos"
                description="Dosis esperadas hasta ahora, dadas e ignoradas."
              >
                <Field label="Destinatario" className="w-full sm:w-56">
                  {(c) => (
                    <select
                      {...c}
                      className={inputClass}
                      value={destinatario}
                      onChange={(e) =>
                        setSearch(e.target.value ? { destinatario: e.target.value } : {})
                      }
                    >
                      <option value="">Todos</option>
                      {destinatarios.map((d) => (
                        <option key={d} value={d}>
                          {d}
                        </option>
                      ))}
                    </select>
                  )}
                </Field>
              </SectionHeader>
              <p className="text-body font-medium tabular-nums">
                {visibles.length} {visibles.length === 1 ? 'tratamiento' : 'tratamientos'} ·{' '}
                {visibles.reduce((n, r) => n + r.dosis_dadas, 0)} dosis dadas de{' '}
                {visibles.reduce((n, r) => n + r.dosis_esperadas, 0)} esperadas ·{' '}
                {visibles.reduce((n, r) => n + r.ignoradas, 0)} ignoradas
              </p>
              <p className="text-caption text-muted">
                Al día: {UMBRAL_AL_DIA} % o más · Revisar: {UMBRAL_REVISAR}–{UMBRAL_AL_DIA - 1} % ·
                Baja: menos de {UMBRAL_REVISAR} %. El servidor entrega solo los conteos; los
                umbrales son de esta consola.
              </p>
              <Table
                caption="Adherencia por tratamiento"
                columns={columns}
                rows={visibles}
                rowKey={(r) => r.alarma_id}
              />
            </Card>
          )
        }}
      </QueryState>
    </Page>
  )
}
