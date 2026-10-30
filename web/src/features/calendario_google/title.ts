import { useEffect } from 'react'

/** Título de pestaña por vista: «Alarmas activas · Cauce». */
export function useDocumentTitle(title: string) {
  useEffect(() => {
    const previous = document.title
    document.title = `${title} · Cauce`
    return () => {
      document.title = previous
    }
  }, [title])
}
