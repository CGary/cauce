import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { formatDateTime } from '../../../core/time'
import { Card, Page, QueryState, SectionHeader, Table, type Column } from '../../../core/ui'
import { historialQuery } from '../api'
import type { HistorialDosis } from '../types'

const columns: Column<HistorialDosis>[] = [
  { header: 'Fecha', render: (r) => formatDateTime(r.ts) },
  { header: 'Destinatario', render: (r) => r.destinatario },
  { header: 'Remedio', render: (r) => r.remedio },
]

/** P3 `historial_dosis`; el destinatario viaja como param de la proyección. */
export default function HistorialView() {
  const [search, setSearch] = useSearchParams()
  const destinatario = search.get('destinatario') ?? ''
  const query = useQuery(historialQuery({ destinatario: destinatario || undefined }))

  return (
    <Page title="Historial de dosis">
      <Card className="flex flex-col gap-4">
        <SectionHeader title="Dosis dadas" description="Las más recientes primero.">
          <form
            className="flex items-center gap-2 text-sm"
            onSubmit={(e) => {
              e.preventDefault()
              const value = String(new FormData(e.currentTarget).get('destinatario') ?? '').trim()
              setSearch(value ? { destinatario: value } : {})
            }}
          >
            <label htmlFor="historial-destinatario">Destinatario</label>
            <input
              id="historial-destinatario"
              name="destinatario"
              defaultValue={destinatario}
              key={destinatario}
              placeholder="Todos"
              className="w-36 rounded-md border border-stone-300 bg-white px-2 py-1"
            />
            <button
              type="submit"
              className="rounded-md bg-teal-700 px-3 py-1 font-medium text-white hover:bg-teal-800"
            >
              Filtrar
            </button>
          </form>
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
          {(rows) => (
            <Table
              caption="Historial de dosis"
              columns={columns}
              rows={rows}
              rowKey={(r) => `${r.ts}|${r.destinatario}|${r.remedio}`}
            />
          )}
        </QueryState>
      </Card>
    </Page>
  )
}
