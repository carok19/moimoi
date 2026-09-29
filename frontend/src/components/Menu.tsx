import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'

interface Props {
  button: (open: () => void) => ReactNode
  children: (close: () => void) => ReactNode
  align?: 'left' | 'right'
  className?: string
}

const MARGIN = 8

/** Menú desplegable simple (se cierra al hacer clic afuera o con Escape). */
export function Menu({ button, children, align = 'right', className }: Props) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const menu = useRef<HTMLDivElement>(null)
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

  // Que nunca quede fuera de la pantalla: se corre hacia adentro y, si abajo no entra, se abre
  // hacia arriba (p. ej. en la barra de reproducción).
  useLayoutEffect(() => {
    const el = menu.current
    const anchor = ref.current
    if (!open || !el || !anchor) return
    el.style.transform = ''
    el.style.top = 'calc(100% + 6px)'
    el.style.bottom = ''
    el.style.maxHeight = ''
    const box = anchor.getBoundingClientRect()
    const rect = el.getBoundingClientRect()
    const below = window.innerHeight - box.bottom - MARGIN - 6
    const above = box.top - MARGIN - 6
    if (rect.height > below && above > below) {
      el.style.top = 'auto'
      el.style.bottom = 'calc(100% + 6px)'
      el.style.maxHeight = `${Math.max(120, above)}px`
    } else {
      el.style.maxHeight = `${Math.max(120, below)}px`
    }
    let dx = 0
    if (rect.right > window.innerWidth - MARGIN) dx = window.innerWidth - MARGIN - rect.right
    if (rect.left + dx < MARGIN) dx = MARGIN - rect.left
    if (dx) el.style.transform = `translateX(${Math.round(dx)}px)`
  }, [open])

  return (
    <div ref={ref} className={`menu-anchor${className ? ` ${className}` : ''}`} onClick={(e) => e.stopPropagation()}>
      {button(() => setOpen((v) => !v))}
      {open && (
        <div ref={menu} className="menu" style={{ top: 'calc(100% + 6px)', [align]: 0 }}>
          {children(() => setOpen(false))}
        </div>
      )}
    </div>
  )
}
