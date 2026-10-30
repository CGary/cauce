import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { formatAgo, useNow } from '../../../core/sync'
import { browserTimeZone } from '../../../core/time'
import { buildEvent, PushError, usePushEvents } from '../../../core/push'
import { Button, Card, Chip, EmptyState, ErrorState, Page, Spinner } from '../../../core/ui'
import { reunionesQuery } from '../api'
import {
  agruparPorDia,
  avisoTexto,
  formatDia,
  formatFechaHora,
  formatHora,
  V_AVISO_REUNION_EDITADO,
} from '../model'
import { useDocumentTitle } from '../title'
import { AvisoForm } from './AvisoForm'

export default function ReunionesView() {
  useDocumentTitle('Reuniones próximas')
  const tz = browserTimeZone()
  const now = useNow()
  const reuniones = useQuery(reunionesQuery({ tz }))
  const push = usePushEvents()
  const [editing, setEditing] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const rows = reuniones.data
  const groups = useMemo(() => agruparPorDia(rows ?? []), [rows])

  async function save(payload: Record<string, unknown>) {
    setNotice(null)
    try {
      await push.mutateAsync([
        buildEvent('aviso_reunion_editado', payload, { v: V_AVISO_REUNION_EDITADO }),
      ])
      setNotice(
        'Aviso guardado. Vale para toda la serie. El teléfono lo aplica en su próximo pull.',
      )
      setEditing(null)
    } catch {
      setNotice(null)
    }
  }

  return (
    <Page
      title="Reuniones próximas"
      description="Las reuniones de las cuentas de Google del teléfono. El aviso se edita por serie."
    >
      {notice && (
        <p
          role="status"
          className="rounded-control border border-success bg-success-soft px-4 py-3 font-medium text-success-ink"
        >
          <span aria-hidden="true">✓ </span>
          {notice}
        </p>
      )}
      {push.isError && (
        <p
          role="alert"
          className="rounded-control border border-danger bg-danger-soft px-4 py-3 font-medium text-danger-ink"
        >
          {push.error instanceof PushError ? push.error.message : 'No se pudo guardar'}
        </p>
      )}
      {reuniones.isPending && <Spinner />}
      {reuniones.isError && (
        <ErrorState error={reuniones.error} onRetry={() => void reuniones.refetch()} />
      )}
      {reuniones.isSuccess && groups.length === 0 && (
        <EmptyState
          title="No hay reuniones próximas"
          description={`Las reuniones llegan desde el calendario del teléfono. Última lectura ${reuniones.dataUpdatedAt === 0 ? 'desconocida' : formatAgo(reuniones.dataUpdatedAt, now)}: si el teléfono no sincronizó, la lista puede estar vieja.`}
        />
      )}
      {groups.map((group) => (
        <Card key={group.dia} className="flex flex-col gap-4">
          <h2 className="text-lg font-semibold capitalize">{formatDia(group.dia)}</h2>
          <ul className="flex flex-col divide-y divide-line">
            {group.rows.map((row) => (
              <li key={row.reunion_id} className="flex flex-col gap-2 py-3 first:pt-0 last:pb-0">
                <div className="flex flex-col gap-1 sm:flex-row sm:items-start sm:justify-between">
                  <div className="flex flex-col gap-1">
                    <p className="font-medium">{row.titulo}</p>
                    <p className="text-caption text-muted">
                      {row.todo_el_dia ? 'Todo el día' : formatHora(row.inicio)} · {row.cuenta}
                    </p>
                    <p className="flex flex-wrap items-center gap-2 text-caption">
                      <Chip tone={row.aviso.apagado ? 'warn' : 'info'}>
                        {avisoTexto(row.aviso)}
                      </Chip>
                      <span className="text-muted">
                        {row.aviso_en ? `Suena ${formatFechaHora(row.aviso_en)}` : 'No suena'}
                      </span>
                    </p>
                  </div>
                  {editing !== row.reunion_id && (
                    <Button
                      aria-label={`Editar aviso de ${row.titulo}`}
                      onClick={() => {
                        setNotice(null)
                        push.reset()
                        setEditing(row.reunion_id)
                      }}
                    >
                      Editar aviso
                    </Button>
                  )}
                </div>
                {editing === row.reunion_id && (
                  <AvisoForm
                    reunion={row}
                    pending={push.isPending}
                    onSubmit={(payload) => void save(payload)}
                    onCancel={() => setEditing(null)}
                  />
                )}
              </li>
            ))}
          </ul>
        </Card>
      ))}
    </Page>
  )
}
