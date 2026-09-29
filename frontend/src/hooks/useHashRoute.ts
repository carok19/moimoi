import { useEffect, useState } from 'react'

export type Route =
  | { name: 'library' }
  | { name: 'player'; id: string }
  | { name: 'settings' }
  | { name: 'connect' }

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#\/?/, '').split('?')[0]
  const [first, second] = path.split('/')
  if (first === 'cancion' && second) return { name: 'player', id: decodeURIComponent(second) }
  if (first === 'ajustes') return { name: 'settings' }
  if (first === 'conectar') return { name: 'connect' }
  return { name: 'library' }
}

export function navigate(to: string): void {
  window.location.hash = to
}

/** Parámetros después de "?" en la dirección (por ejemplo #/?link=… cuando se comparte un link). */
export function hashParams(hash = window.location.hash): URLSearchParams {
  const index = hash.indexOf('?')
  return new URLSearchParams(index >= 0 ? hash.slice(index + 1) : '')
}

export function useHashRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash))
  useEffect(() => {
    const onChange = () => {
      setRoute(parseRoute(window.location.hash))
      window.scrollTo({ top: 0 })
    }
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return route
}
