import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { browserTimeZone, formatDateTime } from '../../../core/time'
import {
  Card,
  Chip,
  Page,
  QueryState,
  SectionHeader,
  Stat,
  Table,
  type Column,
} from '../../../core/ui'
import { adherenciaQuery } from '../api'
import type { AdherenciaTratamiento } from '../types'

const columns: Column<AdherenciaTratamiento>[] = [
  { header: 'Destinatario', render: (r) => r.destinatario },
  { header: 'Remedio', render: (r) => r.remedio },
  { header: 'Esperadas', align: 'right', render: (r) => r.dosis_esperadas },
  { header: 'Dadas', align: 'right', render: (r) => r.dosis_dadas },
  {
    header: 'Ignoradas',
    align: 'right',
    render: (r) => <Chip tone={r.ignoradas > 0 ? 'warn' : 'neutral'}>{r.ignoradas}</Chip>,
  },
  {
    header: 'Última dosis',
    render: (r) => (r.ultima_dosis ? formatDateTime(r.ultima_dosis) : '—'),
  },
]

/** P2 `adherencia_tratamiento`, con filtro por destinatario. */
export default function AdherenciaView() {
  const [search, setSearch] = useSearchParams()
  const destinatario = search.get('destinatario') ?? ''
  const query = useQuery(adherenciaQuery({ tz: browserTimeZone() }))

  return (
    <Page title="Adherencia por tratamiento">
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
            <Card className="flex flex-col gap-4">
              <SectionHeader
                title="Tratamientos"
                description="Dosis esperadas hasta ahora, dadas e ignoradas."
              >
                <label className="flex items-center gap-2 text-sm">
                  Destinatario
                  <select
                    id="filtro-destinatario"
                    className="rounded-md border border-stone-300 bg-white px-2 py-1"
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
                </label>
              </SectionHeader>
              <Stat label="Tratamientos" value={visibles.length} />
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
