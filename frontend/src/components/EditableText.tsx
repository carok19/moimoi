import { useLayoutEffect, useRef } from 'react'

interface Props {
  value: string
  onSave: (value: string) => void
  className?: string
  placeholder?: string
  ariaLabel: string
  /** No se puede dejar vacío (el título). */
  required?: boolean
}

/**
 * Texto editable que pasa a la línea siguiente cuando no entra (un título largo se lee entero,
 * no queda cortado al borde de la pantalla). Enter guarda, igual que salir del campo.
 */
export function EditableText({ value, onSave, className, placeholder, ariaLabel, required }: Props) {
  const ref = useRef<HTMLTextAreaElement>(null)
  const fit = () => {
    const el = ref.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight}px`
  }
  useLayoutEffect(() => {
    fit()
    const el = ref.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => fit())
    observer.observe(el)
    return () => observer.disconnect()
  }, [value])
  return (
    <textarea
      ref={ref}
      rows={1}
      className={`editable-text${className ? ` ${className}` : ''}`}
      defaultValue={value}
      placeholder={placeholder}
      aria-label={ariaLabel}
      spellCheck={false}
      onInput={fit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault()
          ;(e.target as HTMLTextAreaElement).blur()
        }
      }}
      onBlur={(e) => {
        const next = e.target.value.replace(/\s*\n\s*/g, ' ').trim()
        if (required && !next) {
          e.target.value = value
          fit()
          return
        }
        if (next !== value) onSave(next)
      }}
    />
  )
}
