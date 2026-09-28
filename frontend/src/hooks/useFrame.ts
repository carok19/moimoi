import { useEffect, useRef, useState } from 'react'

/** Llama a `callback` en cada cuadro de animación (para dibujar sin re-renderizar React). */
export function useFrame(callback: () => void, active = true): void {
  const ref = useRef(callback)
  ref.current = callback
  useEffect(() => {
    if (!active) return
    let id = 0
    const loop = () => {
      ref.current()
      id = requestAnimationFrame(loop)
    }
    id = requestAnimationFrame(loop)
    return () => cancelAnimationFrame(id)
  }, [active])
}

/** Valor derivado de la posición, actualizado como estado de React solo cuando cambia. */
export function useSampled<T>(read: () => T, intervalMs = 100, equals: (a: T, b: T) => boolean = Object.is): T {
  const readRef = useRef(read)
  readRef.current = read
  const [value, setValue] = useState<T>(() => read())
  const last = useRef(value)
  useEffect(() => {
    const timer = window.setInterval(() => {
      const next = readRef.current()
      if (!equals(next, last.current)) {
        last.current = next
        setValue(next)
      }
    }, intervalMs)
    return () => window.clearInterval(timer)
  }, [intervalMs, equals])
  return value
}

export function useDebouncedEffect(effect: () => void, deps: unknown[], ms: number): void {
  const ref = useRef(effect)
  ref.current = effect
  const first = useRef(true)
  useEffect(() => {
    if (first.current) {
      first.current = false
      return
    }
    const timer = window.setTimeout(() => ref.current(), ms)
    return () => window.clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
}
