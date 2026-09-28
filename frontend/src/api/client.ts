import type {
  Analysis, ExportRequest, Health, Job, Lyrics, LyricsLine, Peaks, PresetId, Presets, Quality, SearchResult,
  Settings, Song, SongSettings, UrlInfo,
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

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, init)
  } catch {
    throw new ApiError('No se pudo conectar con MoiMoi. ¿Está abierto el programa?', 0)
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

  /** Sube un archivo con progreso (fetch no informa el avance de la subida). */
  upload(file: File, preset: PresetId, quality: Quality, onProgress?: (fraction: number) => void): Promise<Song> {
    return new Promise((resolve, reject) => {
      const form = new FormData()
      form.append('file', file)
      form.append('preset', preset)
      form.append('quality', quality)
      const xhr = new XMLHttpRequest()
      xhr.open('POST', '/api/songs/upload')
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
        if (xhr.status >= 200 && xhr.status < 300) resolve(data as Song)
        else {
          const detail = (data as { detail?: unknown })?.detail
          reject(new ApiError(typeof detail === 'string' ? detail : `Error ${xhr.status}`, xhr.status))
        }
      }
      xhr.onerror = () => reject(new ApiError('No se pudo subir el archivo', 0))
      xhr.send(form)
    })
  },
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

/** Descarga un archivo del servidor (sin salir de la página). */
export function download(url: string): void {
  const a = document.createElement('a')
  a.href = url
  a.rel = 'noopener'
  a.download = ''
  document.body.appendChild(a)
  a.click()
  a.remove()
}
