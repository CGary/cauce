import type { ReactNode } from 'react'

export interface Column<T> {
  header: string
  render: (row: T) => ReactNode
  align?: 'left' | 'right'
}

/** Tabla en escritorio; en pantallas angostas cada fila se apila como tarjeta (ver .stack-table). */
export function Table<T>({
  caption,
  columns,
  rows,
  rowKey,
}: {
  caption: string
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T) => string
}) {
  return (
    <div className="overflow-x-auto">
      <table className="stack-table text-body">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className="border-b border-line-strong text-left text-caption text-muted">
            {columns.map((col) => (
              <th
                key={col.header}
                scope="col"
                className={`px-3 py-2 font-semibold ${col.align === 'right' ? 'text-right' : ''}`}
              >
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={rowKey(row)} className="border-b border-line last:border-0">
              {columns.map((col) => (
                <td
                  key={col.header}
                  data-label={col.header}
                  className={`px-3 py-3 align-middle ${col.align === 'right' ? 'text-right tabular-nums' : ''}`}
                >
                  {col.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
