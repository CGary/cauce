import { useQuery } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { browserTimeZone } from '../../../core/time'
import { buildEvent, PushError, usePushEvents, uuidv7 } from '../../../core/push'
import {
  Button,
  Card,
  Dialog,
  Field,
  EmptyState,
  ErrorState,
  Page,
  SectionHeader,
  Spinner,
  Table,
  inputClass,
  type Column,
} from '../../../core/ui'
import { activasQuery, adherenciaQuery } from '../api'
import { formatFechaHora, formatHora } from '../format'
import { useDocumentTitle } from '../title'
import { exportIcs, importIcs, type IcsImportResult, type IcsSource } from '../ics'
import {
  V_ALARMA_CREADA,
  V_ALARMA_DESACTIVADA,
  V_ALARMA_MODIFICADA,
  activaToForm,
  isScheduleLocked,
  bulkToPayload,
  desactivadaPayload,
  emptyForm,
  exportBulk,
  isGoogleCalendar,
  parseBulk,
  payloadRecord,
  repeticionResumen,
  rowsToBulk,
  type AlarmaFormState,
} from '../model'
import type { AlarmaActiva, AlarmaPayload, BulkAlarma } from '../types'
import { AlarmaForm } from './AlarmaForm'
import { TipoChip } from './TipoChip'

type ImportResult = { ok: string | null; errors: string[] }

type Editing = {
  mode: 'crear' | 'editar'
  alarmaId: string
  initial: AlarmaFormState
  /** Fila de P1 de un remedio: se corrige sin tocar su calendario (SPEC 2.2). */
  locked?: AlarmaActiva
}

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

/** Un solo control de archivo: la etiqueta es el botón y la nota de formato va como descripción. */
function ImportItem({
  id,
  label,
  hint,
  accept,
  onFile,
}: {
  id: string
  label: string
  hint: string
  accept: string
  onFile: (file: File) => void
}) {
  return (
    <div className="flex flex-col">
      <label
        htmlFor={id}
        className="inline-flex min-h-11 cursor-pointer items-center rounded-control px-4 py-2 text-caption font-semibold whitespace-nowrap text-primary-ink hover:bg-primary-soft focus-within:outline-3 focus-within:outline-offset-2 focus-within:outline-ring sm:text-body"
      >
        {label}
        <input
          id={id}
          type="file"
          accept={accept}
          aria-describedby={`${id}-hint`}
          className="sr-only"
          onChange={(event) => {
            const file = event.target.files?.[0]
            event.target.value = ''
            if (file) onFile(file)
          }}
        />
      </label>
      <span id={`${id}-hint`} className="px-4 pb-1 text-caption text-muted">
        {hint}
      </span>
    </div>
  )
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
  // Resultado de la última importación (éxito y errores juntos), junto al control de importar.
  const [importResult, setImportResult] = useState<ImportResult | null>(null)
  const [tipoFiltro, setTipoFiltro] = useState('')
  const [origenFiltro, setOrigenFiltro] = useState('')
  const [googleAbierto, setGoogleAbierto] = useState(false)
  const menu = useRef<HTMLDetailsElement>(null)
  useDocumentTitle('Alarmas activas')

  const extras = adherencia.data ?? []
  // P1 no trae destinatario ni remedio: sin P2 no se puede exportar una alarma
  // `remedio` completa (no volvería a importar) ni editarla sin perderlos.
  const adherenciaReady = adherencia.data !== undefined
  const closeMenu = () => {
    if (menu.current) menu.current.open = false
  }

  async function send(events: ReturnType<typeof buildEvent>[], ok: string) {
    setNotice(null)
    try {
      await push.mutateAsync(events)
      setNotice(ok)
      setImportResult(null)
      setEditing(null)
      setPendingDelete(null)
    } catch {
      setNotice(null)
    }
  }

  /** Empuja lo importado y deja éxito y errores en un solo resultado, que reemplaza al anterior. */
  async function sendImport(entries: BulkAlarma[], errors: string[]) {
    setNotice(null)
    if (entries.length === 0) {
      setImportResult({ ok: null, errors })
      return
    }
    try {
      await push.mutateAsync(
        entries.map((entry) =>
          buildEvent('alarma_creada', payloadRecord(bulkToPayload(entry, uuidv7())), {
            v: V_ALARMA_CREADA,
          }),
        ),
      )
      const n = entries.length
      setImportResult({ ok: n === 1 ? 'Se cargó 1 alarma.' : `Se cargaron ${n} alarmas.`, errors })
    } catch {
      // El motivo del rechazo se ve en `pushError`, en el mismo lugar.
      setImportResult({ ok: null, errors })
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
      'Guardado.',
    )
  }

  async function onImportJson(file: File) {
    setImportResult(null)
    let parsed: ReturnType<typeof parseBulk>
    try {
      parsed = parseBulk(await readFile(file))
    } catch (error) {
      setImportResult({
        ok: null,
        errors: [error instanceof Error ? error.message : 'No se pudo leer el archivo'],
      })
      return
    }
    if (!parsed.ok) {
      setImportResult({ ok: null, errors: [parsed.error] })
      return
    }
    if (parsed.alarmas.length === 0) {
      setImportResult({ ok: null, errors: ['El archivo no tiene alarmas'] })
      return
    }
    await sendImport(parsed.alarmas, [])
  }

  async function onImportIcs(file: File) {
    setImportResult(null)
    let parsed: IcsImportResult
    try {
      parsed = importIcs(await readFile(file), { tz })
    } catch (error) {
      setImportResult({
        ok: null,
        errors: [error instanceof Error ? error.message : 'No se pudo leer el archivo .ics'],
      })
      return
    }
    await sendImport(
      parsed.alarmas,
      parsed.errors.map((error) => `Evento ${error.index}: ${error.message}`),
    )
  }

  function sources(rows: AlarmaActiva[]): IcsSource[] {
    return rows.flatMap((row) => {
      const entry = rowsToBulk([row], extras, tz)[0]
      if (!entry) return []
      return [
        {
          ...entry,
          alarma_id: row.alarma_id,
          proximo_disparo: row.proximo_disparo,
          // `restantes` viene en P1: el COUNT del .ics no depende de P2.
          restantes: row.restantes,
        },
      ]
    })
  }

  const rows = activas.data ?? []
  const visibles = rows.filter(
    (row) =>
      (tipoFiltro === '' || row.tipo === tipoFiltro) &&
      (origenFiltro === '' || (origenFiltro === 'google') === isGoogleCalendar(row.alarma_id)),
  )
  const propias = visibles.filter((row) => !isGoogleCalendar(row.alarma_id))
  const deGoogle = visibles.filter((row) => isGoogleCalendar(row.alarma_id))
  // Las reuniones de Google no son alarmas de cauce: no se editan ni se exportan.
  const exportables = rowsToBulk(rows, extras, tz)

  const columns: Column<AlarmaActiva>[] = [
    { header: 'Título', render: (row) => row.titulo },
    // «otro» no aporta nada: la celda queda vacía y las tarjetas angostas la ocultan.
    {
      header: 'Tipo',
      render: (row) => (row.tipo === 'otro' ? null : <TipoChip tipo={row.tipo} />),
    },
    {
      header: 'Hora',
      render: (row) => <span className="whitespace-nowrap">{formatHora(row.hora)}</span>,
    },
    { header: 'Repetición', render: (row) => repeticionResumen(row.repeticion, row.restantes) },
    {
      header: 'Próximo disparo',
      render: (row) => (
        <span className="whitespace-nowrap">{formatFechaHora(row.proximo_disparo)}</span>
      ),
    },
    {
      header: 'Acciones',
      render: (row) =>
        isGoogleCalendar(row.alarma_id) ? (
          <span className="text-caption text-muted">Google Calendar · se edita en Reuniones</span>
        ) : (
          <div className="flex flex-wrap gap-2">
            <Button
              aria-label={`Editar ${row.titulo}`}
              className="whitespace-nowrap"
              disabled={!adherenciaReady}
              onClick={() => {
                const extra = extras.find((item) => item.alarma_id === row.alarma_id)
                setPendingDelete(null)
                setEditing({
                  mode: 'editar',
                  alarmaId: row.alarma_id,
                  initial: activaToForm(row, extra, tz),
                  locked: isScheduleLocked(row) ? row : undefined,
                })
              }}
            >
              Editar
            </Button>
            <Button
              aria-label={`Eliminar ${row.titulo}`}
              variant="ghost"
              className="border border-danger whitespace-nowrap text-danger-ink! hover:bg-danger-soft!"
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
        className="sticky bottom-0 z-20 -mx-4 flex items-center gap-3 border-t border-line bg-surface px-4 py-3 sm:static sm:mx-0 sm:border-0 sm:bg-transparent sm:p-0"
      >
        <Button
          variant="primary"
          className="flex-1 whitespace-nowrap sm:flex-none"
          onClick={openCreate}
        >
          Nueva alarma
        </Button>
        <details ref={menu} className="relative">
          <summary className="inline-flex min-h-11 min-w-11 cursor-pointer list-none items-center justify-center gap-2 rounded-control border border-line-strong bg-surface px-4 py-2 text-caption font-semibold whitespace-nowrap text-ink select-none hover:bg-surface-2 sm:text-body [&::-webkit-details-marker]:hidden">
            Más
            <span aria-hidden="true">▾</span>
          </summary>
          <div className="absolute bottom-full right-0 z-30 mb-2 flex w-64 flex-col gap-1 rounded-card border border-line bg-surface p-2 shadow-overlay sm:bottom-auto sm:left-0 sm:right-auto sm:top-full sm:mb-0 sm:mt-2">
            <Button
              variant="ghost"
              className="justify-start whitespace-nowrap"
              disabled={exportables.length === 0 || (!adherenciaReady && !adherencia.isError)}
              onClick={() => {
                downloadText('alarmas.json', exportBulk(exportables), 'application/json')
                closeMenu()
              }}
            >
              Exportar JSON
            </Button>
            <Button
              variant="ghost"
              className="justify-start whitespace-nowrap"
              disabled={exportables.length === 0 || (!adherenciaReady && !adherencia.isError)}
              onClick={() => {
                downloadText(
                  'alarmas.ics',
                  exportIcs(sources(rows), { tz, now: new Date().toISOString() }),
                  'text/calendar',
                )
                closeMenu()
              }}
            >
              Exportar ICS
            </Button>
            <ImportItem
              id="importar-json"
              label="Importar JSON"
              hint="Archivo .json con una lista de alarmas"
              accept="application/json,.json"
              onFile={(file) => {
                closeMenu()
                void onImportJson(file)
              }}
            />
            <ImportItem
              id="importar-ics"
              label="Importar ICS"
              hint="Archivo .ics de un calendario"
              accept=".ics,text/calendar"
              onFile={(file) => {
                closeMenu()
                void onImportIcs(file)
              }}
            />
          </div>
        </details>
      </div>
      {(importResult || (!editing && !pendingDelete && pushError)) && (
        <section aria-label="Resultado de la importación" className="flex flex-col gap-2">
          {importResult?.ok && (
            <p
              role="status"
              className="rounded-control border border-success bg-success-soft px-4 py-3 font-medium text-success-ink"
            >
              <span aria-hidden="true">✓ </span>
              {importResult.ok}
            </p>
          )}
          {importResult && importResult.errors.length > 0 && (
            <div
              role="alert"
              className="rounded-control border border-danger bg-danger-soft px-4 py-3 text-danger-ink"
            >
              <p className="font-semibold">
                {importResult.ok
                  ? 'Algunos ítems no se importaron'
                  : 'No se pudo importar el archivo'}
              </p>
              <ul className="list-disc pl-5">
                {importResult.errors.map((error) => (
                  <li key={error}>{error}</li>
                ))}
              </ul>
            </div>
          )}
          {!editing && !pendingDelete && pushError}
        </section>
      )}
      <div role="status" aria-live="polite" className="flex flex-col gap-2 empty:hidden">
        {adherencia.isPending && (
          <p className="rounded-control bg-surface-2 px-4 py-3 text-caption text-muted">
            Cargando adherencia: editar y exportar se habilitan al terminar.
          </p>
        )}
        {adherencia.isError && (
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-control border border-warning/60 bg-warning-soft px-4 py-3 text-warning-ink">
            <p>
              <span aria-hidden="true">▲ </span>
              No se pudo cargar la adherencia. Editar está deshabilitado; los remedios se exportan
              sin destinatario ni remedio; el .ics no se ve afectado.
            </p>
            <Button
              variant="ghost"
              className="border border-warning text-warning-ink! hover:bg-warning-soft!"
              onClick={() => void adherencia.refetch()}
            >
              Reintentar
            </Button>
          </div>
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
        <div className="flex flex-wrap gap-3">
          <Field label="Filtrar por tipo" className="w-44">
            {(c) => (
              <select
                {...c}
                className={inputClass}
                value={tipoFiltro}
                onChange={(e) => setTipoFiltro(e.target.value)}
              >
                <option value="">Todos</option>
                <option value="remedio">Remedio</option>
                <option value="reunion">Reunión</option>
                <option value="otro">Otro</option>
              </select>
            )}
          </Field>
          <Field label="Filtrar por origen" className="w-44">
            {(c) => (
              <select
                {...c}
                className={inputClass}
                value={origenFiltro}
                onChange={(e) => {
                  setOrigenFiltro(e.target.value)
                  if (e.target.value === 'google') setGoogleAbierto(true)
                }}
              >
                <option value="">Todos</option>
                <option value="cauce">Cauce</option>
                <option value="google">Google</option>
              </select>
            )}
          </Field>
        </div>
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
        {activas.isSuccess && rows.length > 0 && visibles.length === 0 && (
          <p className="text-muted">Ninguna alarma coincide con los filtros.</p>
        )}
        {activas.isSuccess && propias.length > 0 && (
          <div className="compact-cards">
            <Table
              caption="Alarmas activas"
              columns={columns}
              rows={propias}
              rowKey={(row) => row.alarma_id}
            />
          </div>
        )}
        {activas.isSuccess && deGoogle.length > 0 && (
          <details
            open={googleAbierto}
            onToggle={(e) => setGoogleAbierto(e.currentTarget.open)}
            className="rounded-control border border-line"
          >
            <summary className="min-h-11 cursor-pointer px-3 py-3 font-semibold">
              Reuniones de Google ({deGoogle.length})
            </summary>
            <div className="compact-cards">
              <Table
                caption="Reuniones de Google"
                columns={columns}
                rows={deGoogle}
                rowKey={(row) => row.alarma_id}
              />
            </div>
          </details>
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
                    'Alarma desactivada.',
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
              locked={editing.locked}
              onSubmit={onSave}
              onCancel={() => setEditing(null)}
            />
            {pushError}
          </div>
        </Dialog>
      )}
    </Page>
  )
}
