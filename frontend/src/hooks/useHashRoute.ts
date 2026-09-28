import { useEffect, useState } from 'react'

export type Route =
  | { name: 'library' }
  | { name: 'player'; id: string }
  | { name: 'settings' }

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#\/?/, '')
  const [first, second] = path.split('/')
  if (first === 'cancion' && second) return { name: 'player', id: decodeURIComponent(second) }
  if (first === 'ajustes') return { name: 'settings' }
  return { name: 'library' }
}

export function navigate(to: string): void {
  window.location.hash = to
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
