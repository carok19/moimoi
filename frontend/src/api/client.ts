import { apiUrl, isNativeApp } from './base'
import type {
  Analysis, ExportRequest, GuideKit, GuideUploadResult, Health, Job, Lyrics, LyricsLine, MultitrackStatus,
  NetworkInfo, Peaks, PresetId, Presets, Quality, SearchResult, SendResult, Settings, Song, SongSettings, UrlInfo,
} from './types'

export class ApiError extends Error {
  status: number
  constructor(message: string, status: number) {
    super(message)
    this.status = status
  }
}

async function parseError(response: Response): Promise<ApiError> {
  let message = `Error ${response.status}`
  try {
    const data = await response.json()
    if (typeof data?.detail === 'string') message = data.detail
    else if (Array.isArray(data?.detail) && data.detail[0]?.msg) message = data.detail[0].msg
  } catch {
    // sin cuerpo JSON
  }
  return new ApiError(message, response.status)
}

const OFFLINE = isNativeApp
  ? 'No se pudo conectar con MoiMoi. ¿Está abierto en la computadora y el celular está en la misma red WiFi?'
  : 'No se pudo conectar con MoiMoi. ¿Está abierto el programa?'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(apiUrl(path), init)
  } catch {
    throw new ApiError(OFFLINE, 0)
  }
  if (!response.ok) throw await parseError(response)
  return (await response.json()) as T
}

function json(method: string, body?: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  }
}

export const api = {
  health: () => request<Health>('/api/health'),
  presets: () => request<Presets>('/api/presets'),
  settings: () => request<Settings>('/api/settings'),
  saveSettings: (values: Partial<Settings>) => request<Settings>('/api/settings', json('PUT', values)),

  songs: () => request<Song[]>('/api/songs'),
  song: (id: string) => request<Song>(`/api/songs/${id}`),
  analysis: (id: string) => request<Analysis>(`/api/songs/${id}/analysis`),
  peaks: (id: string) => request<Peaks>(`/api/songs/${id}/peaks`),
  updateSong: (id: string, body: { title?: string; artist?: string | null; settings?: SongSettings }) =>
    request<Song>(`/api/songs/${id}`, json('PATCH', body)),
  deleteSong: (id: string) => request<{ ok: boolean }>(`/api/songs/${id}`, { method: 'DELETE' }),
  cancelSong: (id: string) => request<Song>(`/api/songs/${id}/cancel`, { method: 'POST' }),
  retrySong: (id: string, preset?: PresetId, quality?: Quality) =>
    request<Song>(`/api/songs/${id}/retry`, json('POST', { preset, quality })),
  reanalyze: (id: string) => request<Job>(`/api/songs/${id}/reanalyze`, { method: 'POST' }),
  addUrl: (url: string, preset: PresetId, quality: Quality, title?: string, artist?: string) =>
    request<Song>('/api/songs/url', json('POST', { url, preset, quality, title, artist })),
  urlInfo: (url: string) => request<UrlInfo>(`/api/url-info?url=${encodeURIComponent(url)}`),
  search: (q: string) => request<SearchResult[]>(`/api/search?q=${encodeURIComponent(q)}`),

  lyrics: (id: string) => request<Lyrics>(`/api/songs/${id}/lyrics`),
  requestLyrics: (id: string, language?: string) =>
    request<Job>(`/api/songs/${id}/lyrics`, json('POST', { language: language || null })),
  saveLyrics: (id: string, lines: LyricsLine[]) => request<Lyrics>(`/api/songs/${id}/lyrics`, json('PUT', { lines })),

  createExport: (id: string, body: ExportRequest) => request<Job>(`/api/songs/${id}/exports`, json('POST', body)),
  job: (id: string) => request<Job>(`/api/jobs/${id}`),
  cancelJob: (id: string) => request<Job>(`/api/jobs/${id}/cancel`, { method: 'POST' }),
  activeJobs: () => request<Job[]>('/api/jobs?active=true'),

  guide: (set?: string | null) => request<GuideKit>(`/api/guia${set ? `?set=${encodeURIComponent(set)}` : ''}`),
  setGuideActive: (set: string) => request<GuideKit>('/api/guia/activo', json('PUT', { set })),
  assignGuide: (fileId: string, cue: string | null, set?: string | null) =>
    request<GuideKit>(`/api/guia/${fileId}${set ? `?set=${encodeURIComponent(set)}` : ''}`, json('PUT', { cue })),
  deleteGuideFile: (fileId: string, set?: string | null) =>
    request<GuideKit>(`/api/guia/${fileId}${set ? `?set=${encodeURIComponent(set)}` : ''}`, { method: 'DELETE' }),
  deleteGuideSet: (set: string) => request<GuideKit>(`/api/guia?set=${encodeURIComponent(set)}`, { method: 'DELETE' }),
  deleteClickStyle: (style: string) => request<GuideKit>(`/api/guia/clicks/${encodeURIComponent(style)}`, { method: 'DELETE' }),
  /** Sube voces guía (un .zip con el paquete, audios sueltos o una grabación) con progreso. */
  uploadGuide(files: File[], options: { cue?: string; set?: string | null } = {},
    onProgress?: (fraction: number) => void): Promise<GuideUploadResult> {
    const form = new FormData()
    for (const file of files) form.append('files', file)
    if (options.cue) form.append('cue', options.cue)
    if (options.set) form.append('set', options.set)
    return sendForm<GuideUploadResult>('/api/guia', form, onProgress, 'No se pudieron cargar las voces')
  },

  multitrackStatus: (url?: string) =>
    request<MultitrackStatus>(`/api/multitrack${url ? `?url=${encodeURIComponent(url)}` : ''}`),
  sendToMultitrack: (jobId: string, url?: string) => request<SendResult>(`/api/jobs/${jobId}/enviar`, json('POST', { url })),
  network: () => request<NetworkInfo>('/api/red'),

  /** Sube un archivo con progreso (fetch no informa el avance de la subida). */
  upload(file: File, preset: PresetId, quality: Quality, onProgress?: (fraction: number) => void): Promise<Song> {
    const form = new FormData()
    form.append('file', file)
    form.append('preset', preset)
    form.append('quality', quality)
    return sendForm<Song>('/api/songs/upload', form, onProgress, 'No se pudo subir el archivo')
  },
}

function sendForm<T>(path: string, form: FormData, onProgress: ((fraction: number) => void) | undefined,
  failure: string): Promise<T> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', apiUrl(path))
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && onProgress) onProgress(event.loaded / event.total)
    }
    xhr.onload = () => {
      let data: unknown = null
      try {
        data = JSON.parse(xhr.responseText)
      } catch {
        // respuesta vacía
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data as T)
      else {
        const detail = (data as { detail?: unknown })?.detail
        reject(new ApiError(typeof detail === 'string' ? detail : `Error ${xhr.status}`, xhr.status))
      }
    }
    xhr.onerror = () => reject(new ApiError(xhr.status ? failure : OFFLINE, xhr.status))
    xhr.send(form)
  })
}

/** Espera a que un trabajo termine, informando el avance. */
export async function waitForJob(id: string, onUpdate?: (job: Job) => void, signal?: AbortSignal): Promise<Job> {
  for (;;) {
    if (signal?.aborted) throw new ApiError('Cancelado', 0)
    const job = await api.job(id)
    onUpdate?.(job)
    if (job.status === 'done' || job.status === 'error' || job.status === 'cancelled') return job
    await new Promise((r) => setTimeout(r, 700))
  }
}
