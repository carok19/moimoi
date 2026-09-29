// MoiMoi dentro del celular: en la app de Android, sin computadora, la separación y todo lo demás
// lo hace la propia app (com.moimoi.local, en Java). La interfaz le habla con los mismos pedidos que
// al programa de la computadora (ver client.ts), a través de este plugin.

import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'
import type { Song } from './types'

export interface ImportError {
  name: string
  error: string
}

interface RawImport {
  songs: string
  errors: string
}

interface MoiMoiLocalPlugin {
  request(options: { method: string; path: string; body?: string }): Promise<{ status: number; body: string }>
  pickAudio(options: { preset?: string }): Promise<RawImport>
  shareFile(options: { url: string; name: string }): Promise<{ outcome: string }>
  saveFile(options: { url: string; name: string }): Promise<{ where?: string | null }>
  requestNotifications(): Promise<{ granted: boolean }>
  addListener(event: 'imported', listener: (data: RawImport) => void): Promise<PluginListenerHandle>
}

export const Local = registerPlugin<MoiMoiLocalPlugin>('MoiMoiLocal')

export interface ImportResult {
  songs: Song[]
  errors: ImportError[]
}

function parseImport(raw: RawImport): ImportResult {
  try {
    return { songs: JSON.parse(raw.songs || '[]') as Song[], errors: JSON.parse(raw.errors || '[]') as ImportError[] }
  } catch {
    return { songs: [], errors: [] }
  }
}

/** Abre el selector de archivos del celular y agrega las canciones elegidas. */
export async function pickAudio(preset?: string): Promise<ImportResult> {
  return parseImport(await Local.pickAudio({ preset }))
}

let notificationsAsked = false

/** Android 13 o más nuevo: permiso para mostrar el avance de la separación en la notificación. */
export function askNotifications(): void {
  if (notificationsAsked) return
  notificationsAsked = true
  Local.requestNotifications().catch(() => undefined)
}

/**
 * Audios compartidos con MoiMoi desde otras apps (WhatsApp, Archivos…): la app los agrega sola y
 * avisa acá (Android guarda el aviso si llega antes de que la página esté lista).
 */
export function onImported(listener: (result: ImportResult) => void): () => void {
  let handle: PluginListenerHandle | null = null
  let stopped = false
  void Local.addListener('imported', (raw) => listener(parseImport(raw))).then((h) => {
    if (stopped) void h.remove()
    else handle = h
  }).catch(() => undefined)
  return () => {
    stopped = true
    void handle?.remove()
  }
}
