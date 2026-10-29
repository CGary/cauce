import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { Dialog } from './Dialog'

function Harness() {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button onClick={() => setOpen(true)}>Abrir</button>
      {open && (
        <Dialog title="Confirmar" onClose={() => setOpen(false)}>
          <button>Primero</button>
          <button>Último</button>
        </Dialog>
      )}
    </>
  )
}

describe('Dialog', () => {
  it('enfoca el contenido, atrapa Tab, cierra con Escape y devuelve el foco', async () => {
    render(<Harness />)
    const trigger = screen.getByRole('button', { name: 'Abrir' })
    await userEvent.click(trigger)
    const dialog = screen.getByRole('dialog', { name: 'Confirmar' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    // El primer enfocable es el botón de cerrar de la cabecera.
    expect(screen.getByRole('button', { name: 'Cerrar diálogo' })).toHaveFocus()

    await userEvent.tab()
    expect(screen.getByRole('button', { name: 'Primero' })).toHaveFocus()
    await userEvent.tab()
    expect(screen.getByRole('button', { name: 'Último' })).toHaveFocus()
    await userEvent.tab()
    expect(screen.getByRole('button', { name: 'Cerrar diálogo' })).toHaveFocus()
    await userEvent.tab({ shift: true })
    expect(screen.getByRole('button', { name: 'Último' })).toHaveFocus()

    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })
})
