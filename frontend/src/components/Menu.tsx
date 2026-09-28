import { useEffect, useRef, useState, type ReactNode } from 'react'

interface Props {
  button: (open: () => void) => ReactNode
  children: (close: () => void) => ReactNode
  align?: 'left' | 'right'
}

/** Menú desplegable simple (se cierra al hacer clic afuera o con Escape). */
export function Menu({ button, children, align = 'right' }: Props) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    window.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onKey)
    }
  }, [open])
  return (
    <div ref={ref} style={{ position: 'relative' }} onClick={(e) => e.stopPropagation()}>
      {button(() => setOpen((v) => !v))}
      {open && (
        <div className="menu" style={{ top: 'calc(100% + 6px)', [align]: 0 }}>
          {children(() => setOpen(false))}
        </div>
      )}
    </div>
  )
}
