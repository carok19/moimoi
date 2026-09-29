// Dirección del servidor de MoiMoi.
// - En el navegador, la interfaz la sirve el mismo MoiMoi: se usa la misma dirección ('').
// - En la app de Android todo se hace en el celular ("modo celular", ver local.ts). Opcionalmente
//   se puede conectar a MoiMoi en una computadora (por ejemplo http://192.168.1.20:4747).

import { Capacitor } from '@capacitor/core'

const KEY = 'moimoi.servidor'
const RECENT_KEY = 'moimoi.servidores'

export const isNativeApp: boolean = Capacitor.isNativePlatform()

let base: string | null = null

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

function write(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(key)
    else window.localStorage.setItem(key, value)
  } catch {
    // sin almacenamiento: se pregunta de nuevo la próxima vez
  }
}

export function serverBase(): string {
  if (!isNativeApp) return ''
  if (base === null) base = read(KEY) ?? ''
  return base
}

/** App de Android sin computadora elegida: MoiMoi corre dentro del celular. */
export function isStandalone(): boolean {
  return isNativeApp && !serverBase()
}

export function setServerBase(url: string | null): void {
  base = url ?? ''
  write(KEY, url)
  if (url) write(RECENT_KEY, JSON.stringify([url, ...recentServers().filter((u) => u !== url)].slice(0, 5)))
}

export function recentServers(): string[] {
  try {
    const list = JSON.parse(read(RECENT_KEY) ?? '[]')
    return Array.isArray(list) ? list.filter((u): u is string => typeof u === 'string') : []
  } catch {
    return []
  }
}

/** URL completa para una ruta del servidor ("/api/…"). */
export function apiUrl(path: string): string
export function apiUrl(path: string | null | undefined): string | null
export function apiUrl(path: string | null | undefined): string | null {
  if (!path) return null
  if (/^[a-z][a-z0-9+.-]*:/i.test(path)) return path
  return serverBase() + path
}

/** Lo que escribe el usuario ("192.168.1.20") -> "http://192.168.1.20:4747". */
export function normalizeServer(text: string): string | null {
  let value = text.trim().replace(/\/+$/, '')
  if (!value) return null
  if (!/^https?:\/\//i.test(value)) value = `http://${value}`
  try {
    const url = new URL(value)
    if (!url.hostname) return null
    const port = url.port || (url.protocol === 'https:' ? '4748' : '4747')
    return `${url.protocol}//${url.hostname}:${port}`
  } catch {
    return null
  }
}
