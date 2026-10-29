import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { browserTimeZone, formatDateTime } from '../../../core/time'
import { buildEvent, PushError, usePushEvents, uuidv7 } from '../../../core/push'
import {
  Button,
  Card,
  Chip,
  Dialog,
  EmptyState,
  ErrorState,
  Page,
  SectionHeader,
  Spinner,
  Table,
  buttonClass,
  type Column,
} from '../../../core/ui'
import { activasQuery, adherenciaQuery } from '../api'
import { exportIcs, importIcs, type IcsImportResult, type IcsSource } from '../ics'
import {
  V_ALARMA_CREADA,
  V_ALARMA_DESACTIVADA,
  V_ALARMA_MODIFICADA,
  activaToForm,
  bulkToPayload,
  desactivadaPayload,
  emptyForm,
  exportBulk,
  parseBulk,
  payloadRecord,
  repeticionResumen,
  rowsToBulk,
  type AlarmaFormState,
} from '../model'
import type { AlarmaActiva, AlarmaPayload } from '../types'
import { AlarmaForm } from './AlarmaForm'

type Editing = { mode: 'crear' | 'editar'; alarmaId: string; initial: AlarmaFormState }

function readFile(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(reader.error ?? new Error('No se pudo leer el archivo'))
    reader.readAsText(file)
  })
}

function downloadText(filename: string, content: string, mime: string) {
  const url = URL.createObjectURL(new Blob([content], { type: mime }))
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.append(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

function pushText(error: unknown): string {
  return error instanceof PushError ? error.message : 'No se pudo guardar'
}

export default function ActivasView() {
  const tz = browserTimeZone()
  const activas = useQuery(activasQuery({ tz }))
  const adherencia = useQuery(adherenciaQuery({ tz }))
  const push = usePushEvents()
  const [editing, setEditing] = useState<Editing | null>(null)
  const [pendingDelete, setPendingDelete] = useState<AlarmaActiva | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [importErrors, setImportErrors] = useState<string[]>([])

  const extras = adherencia.data ?? []
  // P1 no trae destinatario ni remedio: sin P2 no se puede exportar una alarma
  // `remedio` completa (no volvería a importar) ni editarla sin perderlos.
  const adherenciaReady = adherencia.data !== undefined

  async function send(events: ReturnType<typeof buildEvent>[], ok: string) {
    setNotice(null)
    try {
      await push.mutateAsync(events)
      setNotice(ok)
      setEditing(null)
      setPendingDelete(null)
    } catch {
      setNotice(null)
    }
  }

  function onSave(payload: AlarmaPayload) {
    const created = editing?.mode !== 'editar'
    void send(
      [
        buildEvent(created ? 'alarma_creada' : 'alarma_modificada', payloadRecord(payload), {
          v: created ? V_ALARMA_CREADA : V_ALARMA_MODIFICADA,
        }),
      ],
      'Guardado. La lista se actualiza al releer.',
    )
  }

  async function onImportJson(file: File) {
    setImportErrors([])
    let parsed: ReturnType<typeof parseBulk>
    try {
      parsed = parseBulk(await readFile(file))
    } catch (error) {
      setImportErrors([error instanceof Error ? error.message : 'No se pudo leer el archivo'])
      return
    }
    if (!parsed.ok) {
      setImportErrors([parsed.error])
      return
    }
    if (parsed.alarmas.length === 0) {
      setImportErrors(['El archivo no tiene alarmas'])
      return
    }
    await send(
      parsed.alarmas.map((entry) =>
        buildEvent('alarma_creada', payloadRecord(bulkToPayload(entry, uuidv7())), {
          v: V_ALARMA_CREADA,
        }),
      ),
      `Se cargaron ${parsed.alarmas.length} alarmas.`,
    )
  }

  async function onImportIcs(file: File) {
    let parsed: IcsImportResult
    try {
      parsed = importIcs(await readFile(file), { tz })
    } catch (error) {
      setImportErrors([error instanceof Error ? error.message : 'No se pudo leer el archivo .ics'])
      return
    }
    setImportErrors(parsed.errors.map((error) => `Evento ${error.index}: ${error.message}`))
    if (parsed.alarmas.length === 0) return
    await send(
      parsed.alarmas.map((entry) =>
        buildEvent('alarma_creada', payloadRecord(bulkToPayload(entry, uuidv7())), {
          v: V_ALARMA_CREADA,
        }),
      ),
      `Se cargaron ${parsed.alarmas.length} alarmas.`,
    )
  }

  function sources(rows: AlarmaActiva[]): IcsSource[] {
    return rows.flatMap((row) => {
      const entry = rowsToBulk([row], extras)[0]
      if (!entry) return []
      return [
        {
          ...entry,
          alarma_id: row.alarma_id,
          proximo_disparo: row.proximo_disparo,
          restantes: row.restantes,
        },
      ]
    })
  }

  const columns: Column<AlarmaActiva>[] = [
    { header: 'Título', render: (row) => row.titulo },
    {
      header: 'Tipo',
      render: (row) => <Chip tone={row.tipo === 'remedio' ? 'good' : 'neutral'}>{row.tipo}</Chip>,
    },
    { header: 'Hora', render: (row) => row.hora },
    { header: 'Repetición', render: (row) => repeticionResumen(row.repeticion) },
    { header: 'Próximo disparo', render: (row) => formatDateTime(row.proximo_disparo) },
    {
      header: 'Acciones',
      render: (row) => (
        <div className="flex gap-2">
          <Button
            aria-label={`Editar ${row.titulo}`}
            disabled={!adherenciaReady}
            onClick={() => {
              const extra = extras.find((item) => item.alarma_id === row.alarma_id)
              setPendingDelete(null)
              setEditing({
                mode: 'editar',
                alarmaId: row.alarma_id,
                initial: activaToForm(row, extra),
              })
            }}
          >
            Editar
          </Button>
          <Button
            aria-label={`Eliminar ${row.titulo}`}
            onClick={() => {
              setEditing(null)
              setPendingDelete(row)
            }}
          >
            Eliminar
          </Button>
        </div>
      ),
    },
  ]

  const rows = activas.data ?? []

  function openCreate() {
    setPendingDelete(null)
    setEditing({ mode: 'crear', alarmaId: uuidv7(), initial: emptyForm() })
  }

  const pushError = push.isError && (
    <p
      role="alert"
      className="rounded-control border border-danger bg-danger-soft px-4 py-3 font-medium text-danger-ink"
    >
      {pushText(push.error)}
    </p>
  )

  return (
    <Page
      title="Alarmas activas"
      description="Las alarmas que siguen sonando. Crealas, editalas o cargalas desde un archivo."
    >
      <div
        role="toolbar"
        aria-label="Acciones de alarmas"
        className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center"
      >
        <Button variant="primary" onClick={openCreate}>
          Nueva alarma
        </Button>
        <span aria-hidden="true" className="hidden h-6 w-px bg-line sm:block" />
        <Button
          disabled={rows.length === 0 || !adherenciaReady}
          onClick={() =>
            downloadText('alarmas.json', exportBulk(rowsToBulk(rows, extras)), 'application/json')
          }
        >
          Exportar JSON
        </Button>
        <Button
          disabled={rows.length === 0 || !adherenciaReady}
          onClick={() =>
            downloadText(
              'alarmas.ics',
              exportIcs(sources(rows), { tz, now: new Date().toISOString() }),
              'text/calendar',
            )
          }
        >
          Exportar ICS
        </Button>
        <span aria-hidden="true" className="hidden h-6 w-px bg-line sm:block" />
        <label
          htmlFor="importar-json"
          className={buttonClass(
            'secondary',
            'focus-within:outline-3 focus-within:outline-offset-2 focus-within:outline-ring',
          )}
        >
          Importar JSON
          <input
            id="importar-json"
            type="file"
            accept="application/json,.json"
            className="sr-only"
            onChange={(event) => {
              const file = event.target.files?.[0]
              event.target.value = ''
              if (file) void onImportJson(file)
            }}
          />
        </label>
        <label
          htmlFor="importar-ics"
          className={buttonClass(
            'secondary',
            'focus-within:outline-3 focus-within:outline-offset-2 focus-within:outline-ring',
          )}
        >
          Importar ICS
          <input
            id="importar-ics"
            type="file"
            accept=".ics,text/calendar"
            className="sr-only"
            onChange={(event) => {
              const file = event.target.files?.[0]
              event.target.value = ''
              if (file) void onImportIcs(file)
            }}
          />
        </label>
      </div>
      <div role="status" aria-live="polite" className="flex flex-col gap-2 empty:hidden">
        {!adherenciaReady && adherencia.isPending && (
          <p className="text-caption text-muted">
            Cargando adherencia… exportar y editar se habilitan al terminar.
          </p>
        )}
        {notice && (
          <p className="rounded-control border border-success bg-success-soft px-4 py-3 font-medium text-success-ink">
            <span aria-hidden="true">✓ </span>
            {notice}
          </p>
        )}
      </div>
      <Card className="flex flex-col gap-5">
        <SectionHeader
          title="Activas"
          description="Eliminar desactiva la alarma; no se borra, para que el historial de dosis se conserve."
        />
        {adherencia.isError && (
          <p className="rounded-control border border-warning bg-warning-soft px-4 py-3 text-warning-ink">
            <span aria-hidden="true">▲ </span>
            No se pudo cargar la adherencia: exportar y editar quedan deshabilitados.
          </p>
        )}
        {activas.isPending && <Spinner />}
        {activas.isError && (
          <ErrorState error={activas.error} onRetry={() => void activas.refetch()} />
        )}
        {activas.isSuccess && rows.length === 0 && (
          <EmptyState
            title="No hay alarmas activas"
            description="Creá una o importá un archivo."
            action={
              <Button variant="primary" onClick={openCreate}>
                Crear la primera alarma
              </Button>
            }
          />
        )}
        {activas.isSuccess && rows.length > 0 && (
          <Table
            caption="Alarmas activas"
            columns={columns}
            rows={rows}
            rowKey={(row) => row.alarma_id}
          />
        )}
      </Card>
      {pendingDelete && (
        <Dialog
          title={`¿Eliminar «${pendingDelete.titulo}»?`}
          description="Eliminar desactiva la alarma; no se borra."
          onClose={() => setPendingDelete(null)}
        >
          <div className="flex flex-col gap-5">
            <p>
              La alarma deja de sonar, pero el historial de dosis se conserva. Podés volver a
              crearla cuando quieras.
            </p>
            {pushError}
            <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
              <Button data-autofocus onClick={() => setPendingDelete(null)}>
                Cancelar
              </Button>
              <Button
                variant="danger"
                pending={push.isPending}
                onClick={() =>
                  void send(
                    [
                      buildEvent(
                        'alarma_desactivada',
                        desactivadaPayload(pendingDelete.alarma_id),
                        { v: V_ALARMA_DESACTIVADA },
                      ),
                    ],
                    'Alarma desactivada. La lista se actualiza al releer.',
                  )
                }
              >
                Confirmar eliminación
              </Button>
            </div>
          </div>
        </Dialog>
      )}
      {editing && (
        <Dialog
          title={editing.mode === 'crear' ? 'Nueva alarma' : 'Editar alarma'}
          description="Los cambios se guardan como un evento nuevo; el historial no se reescribe."
          onClose={() => setEditing(null)}
        >
          <div className="flex flex-col gap-4">
            <AlarmaForm
              key={editing.alarmaId}
              mode={editing.mode}
              alarmaId={editing.alarmaId}
              initial={editing.initial}
              pending={push.isPending}
              onSubmit={onSave}
              onCancel={() => setEditing(null)}
            />
            {pushError}
          </div>
        </Dialog>
      )}
      {!editing && !pendingDelete && pushError}
      {importErrors.length > 0 && (
        <div
          role="alert"
          className="rounded-control border border-danger bg-danger-soft px-4 py-3 text-danger-ink"
        >
          <p className="font-semibold">No se pudo importar todo el archivo</p>
          <ul className="list-disc pl-5">
            {importErrors.map((error) => (
              <li key={error}>{error}</li>
            ))}
          </ul>
        </div>
      )}
    </Page>
  )
}
