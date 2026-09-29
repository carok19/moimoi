// Funciones del celular: compartir (WhatsApp…), guardar archivos, botón "atrás" de Android y
// links compartidos desde otras apps (por ejemplo "Compartir → MoiMoi" en YouTube).
// En el navegador se usan las funciones equivalentes de la web cuando existen.

import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'
import { apiUrl, isNativeApp } from './api/base'

export { isNativeApp }

/** Tipos de archivo que los navegadores dejan compartir (los .zip no: solo desde la app). */
const WEB_SHAREABLE = /\.(wav|mp3|flac|m4a|ogg|opus|webm|pdf|txt)$/i

export type ShareOutcome = 'shared' | 'cancelled' | 'unsupported'

function safeName(name: string): string {
  return name.replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ').trim() || 'moimoi'
}

async function nativeDownload(url: string, directory: 'cache' | 'documents', name: string,
  onProgress?: (fraction: number) => void): Promise<string> {
  const [{ Filesystem, Directory }, { FileTransfer }] = await Promise.all([
    import('@capacitor/filesystem'), import('@capacitor/file-transfer'),
  ])
  const dir = directory === 'cache' ? Directory.Cache : Directory.Documents
  const folder = directory === 'cache' ? 'compartir' : 'MoiMoi'
  await Filesystem.mkdir({ directory: dir, path: folder, recursive: true }).catch(() => undefined)
  const { uri } = await Filesystem.getUri({ directory: dir, path: `${folder}/${safeName(name)}` })
  let listener: PluginListenerHandle | null = null
  if (onProgress) {
    listener = await FileTransfer.addListener('progress', (p) => {
      if (p.lengthComputable && p.contentLength > 0) onProgress(p.bytes / p.contentLength)
    })
  }
  try {
    await FileTransfer.downloadFile({ url: apiUrl(url), path: uri, progress: Boolean(onProgress) })
  } finally {
    await listener?.remove()
  }
  return uri
}

/** ¿Se puede compartir este archivo desde acá? */
export function canShare(name: string): boolean {
  if (isNativeApp) return true
  return typeof navigator.canShare === 'function' && WEB_SHAREABLE.test(name)
}

/**
 * Comparte un archivo del servidor (exportación, pista) con otras apps: WhatsApp, Drive, correo…
 * En la app se descarga al celular y se abre el menú "Compartir" de Android.
 */
export async function shareFile(url: string, name: string, onProgress?: (fraction: number) => void): Promise<ShareOutcome> {
  if (isNativeApp) {
    const uri = await nativeDownload(url, 'cache', name, onProgress)
    const { Share } = await import('@capacitor/share')
    try {
      await Share.share({ title: name, files: [uri], dialogTitle: 'Compartir con…' })
      return 'shared'
    } catch (err) {
      if (/cancel/i.test(String((err as Error)?.message ?? err))) return 'cancelled'
      throw err
    }
  }
  if (!canShare(name)) return 'unsupported'
  const response = await fetch(apiUrl(url))
  if (!response.ok) throw new Error('No se pudo leer el archivo')
  const blob = await response.blob()
  const file = new File([blob], name, { type: blob.type || 'application/octet-stream' })
  if (!navigator.canShare({ files: [file] })) return 'unsupported'
  try {
    await navigator.share({ files: [file], title: name })
    return 'shared'
  } catch (err) {
    if ((err as DOMException)?.name === 'AbortError') return 'cancelled'
    throw err
  }
}

/**
 * Descarga un archivo. En la computadora (y en el navegador del celular) lo baja el navegador;
 * en la app queda en Documentos/MoiMoi.
 */
export async function saveFile(url: string, name?: string, onProgress?: (fraction: number) => void): Promise<string | null> {
  if (isNativeApp) {
    const fileName = name || decodeURIComponent(url.split('/').pop()?.split('?')[0] || 'moimoi')
    try {
      await nativeDownload(url, 'documents', fileName, onProgress)
      return `Documentos/MoiMoi/${safeName(fileName)}`
    } catch {
      // Algunos Android no dejan escribir en Documentos: se abre "Compartir" (Guardar en Archivos, Drive…).
      await shareFile(url, fileName, onProgress)
      return null
    }
  }
  const a = document.createElement('a')
  a.href = apiUrl(url)
  a.rel = 'noopener'
  a.download = name ?? ''
  document.body.appendChild(a)
  a.click()
  a.remove()
  return null
}

// ---- botón "atrás" de Android ---------------------------------------------------------

const backHandlers: (() => void)[] = []

/** Mientras está abierto (un diálogo, por ejemplo), el botón "atrás" llama a `handler`. */
export function pushBackHandler(handler: () => void): () => void {
  backHandlers.push(handler)
  return () => {
    const index = backHandlers.lastIndexOf(handler)
    if (index >= 0) backHandlers.splice(index, 1)
  }
}

// ---- links compartidos desde otras apps -------------------------------------------------

interface SharedLinkPlugin {
  getPending(): Promise<{ text: string | null }>
  addListener(event: 'shared', listener: (data: { text: string }) => void): Promise<PluginListenerHandle>
}

const SharedLink = registerPlugin<SharedLinkPlugin>('SharedLink')

function openSharedText(text: string | null | undefined): void {
  const link = text?.match(/https?:\/\/\S+/)?.[0]
  if (link) window.location.hash = `#/?link=${encodeURIComponent(link)}`
}

let started = false

/** Conecta el botón "atrás" y los links compartidos (solo en la app). */
export function startNative(): void {
  if (!isNativeApp || started) return
  started = true
  void import('@capacitor/app').then(({ App }) => {
    void App.addListener('backButton', () => {
      const handler = backHandlers[backHandlers.length - 1]
      if (handler) handler()
      else if (window.location.hash && !/^#\/?(\?.*)?$/.test(window.location.hash)) window.history.back()
      else void App.minimizeApp()
    })
  })
  SharedLink.getPending().then((r) => openSharedText(r.text)).catch(() => undefined)
  void SharedLink.addListener('shared', (data) => openSharedText(data.text)).catch(() => undefined)
}
