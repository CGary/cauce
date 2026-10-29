import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { formatDateTime } from '../../../core/time'
import {
  Button,
  Card,
  Field,
  Page,
  QueryState,
  SectionHeader,
  Table,
  inputClass,
  type Column,
} from '../../../core/ui'
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
    <Page
      title="Historial de dosis"
      description="Cada dosis registrada como dada, con la fecha en tu zona horaria."
    >
      <Card className="flex flex-col gap-5">
        <SectionHeader title="Dosis dadas" description="Las más recientes primero.">
          <form
            className="flex items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              const value = String(new FormData(e.currentTarget).get('destinatario') ?? '').trim()
              setSearch(value ? { destinatario: value } : {})
            }}
          >
            <Field label="Destinatario" className="w-full sm:w-52">
              {(c) => (
                <input
                  {...c}
                  name="destinatario"
                  defaultValue={destinatario}
                  key={destinatario}
                  placeholder="Todos"
                  className={inputClass}
                />
              )}
            </Field>
            <Button type="submit" variant="primary">
              Filtrar
            </Button>
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
