// Tipos de la API de MoiMoi (ver backend/moimoi/api.py).

export type PresetId = '2stems' | '4stems' | '6stems'
export type Quality = 'normal' | 'alta'
export type SongStatus = 'queued' | 'downloading' | 'separating' | 'analyzing' | 'ready' | 'error' | 'cancelled'
export type StemId = 'vocals' | 'drums' | 'bass' | 'guitar' | 'piano' | 'other' | 'instrumental'

export interface StemInfo {
  id: StemId
  name: string
  color: string
  url: string
}

export interface SongSummary {
  bpm: number | null
  beatsPerBar: number
  key: string
  keyLabel: string
  tonic: number
  mode: 'major' | 'minor'
  a4: number
  instruments: StemId[]
}

export interface MixerChannel {
  volume: number
  pan: number
  mute: boolean
  solo: boolean
}

export interface Section {
  start: number
  end: number
  label: string
  group: string
  bars?: number
  vocals?: boolean
}

export interface TempoSegment {
  start: number
  end: number
  bpm: number
  beatsPerBar: number
  steady: boolean
}

/** Corrección del usuario para un tramo de tempo (el que empieza en `start`). */
export interface TempoEdit {
  start: number
  /** Tempo elegido (BPM); sin él, el detectado. */
  bpm?: number
  /** Mueve el "1" del compás n pulsos en ese tramo. */
  shift?: number
}

export interface SongSettings {
  mixer?: Partial<Record<StemId, MixerChannel>>
  tempo?: number
  semitones?: number
  cents?: number
  loop?: { start: number; end: number } | null
  loopOn?: boolean
  sections?: Section[]
  /** Corrección manual del pulso: 'double' | 'half' | null */
  beatScale?: 'double' | 'half' | null
  /** Desplaza el "1" del compás n pulsos. */
  downbeatShift?: number
  /** Correcciones del tempo por tramo (las hace el panel de Tempo del reproductor). */
  tempoEdits?: TempoEdit[]
  /** La grilla final con esas correcciones, para el click y la guía del paquete (null = la del análisis). */
  grid?: { beats: number[]; downbeats: number[]; bpm: number | null } | null
  masterVolume?: number
  [key: string]: unknown
}

export interface Song {
  id: string
  title: string
  artist: string | null
  sourceType: 'upload' | 'url'
  sourceUrl: string | null
  originalFilename: string | null
  duration: number | null
  preset: PresetId
  presetName: string
  quality: Quality
  model: string | null
  status: SongStatus
  progress: number
  stage: string | null
  error: string | null
  stems: StemInfo[]
  summary: SongSummary | null
  lyricsStatus: string | null
  settings: SongSettings
  thumbnailUrl: string | null
  createdAt: string
  updatedAt: string
}

export interface Chord {
  start: number
  end: number
  /** -1 = sin acorde */
  root: number
  quality: 'maj' | 'min' | '7' | 'maj7' | 'min7' | 'sus4' | 'sus2' | 'dim' | 'N'
  bass: number | null
  name: string
}

export interface KeyInfo {
  tonic: number
  mode: 'major' | 'minor'
  name: string
  label: string
  confidence?: number
}

export interface InstrumentInfo {
  presence: number
  relativeDb: number
  level: 'alta' | 'media' | 'baja' | 'ausente'
  active: [number, number][]
}

/** Click y Guía para el reproductor: dónde suena cada voz (segundos de la canción) y los audios. */
export interface PlayerGuide {
  placements: { cue: string; time: number; label: string }[]
  /** {voz: url}: las que suenan y los números para la cuenta ("n1"…). */
  voices: Record<string, string>
  /** Sonido de click elegido (vacío: el de MoiMoi). */
  click: { accent?: string; beat?: string }
  /** Idioma de las voces y nombre del sonido de click (para mostrarlos). */
  voiceSet?: string | null
  clickName?: string | null
}

/** Versión del análisis que hacen el programa y la app (moimoi/analysis y Analyzer.java). */
export const ANALYSIS_VERSION = 3

export interface Analysis {
  version: number
  duration: number
  tempo: {
    bpm: number | null
    beatsPerBar: number
    steady: boolean
    confidence: number
    meterConfidence: number
    /** Tramos de tempo (análisis nuevos): uno solo si la canción no cambia de tempo. */
    segments?: TempoSegment[]
  }
  beats: number[]
  downbeats: number[]
  /** Curva de ataques (8 bits en base64) para volver a acomodar los pulsos a otro tempo. */
  onset?: { fps: number; data: string } | null
  key: KeyInfo
  /** Tonalidad del comienzo (con cambios de tonalidad puede no ser la principal). */
  keyStart?: KeyInfo
  keyChanges: (KeyInfo & { time: number })[]
  tuning: { a4: number; cents: number }
  chords: Chord[]
  sections: Section[]
  instruments: Partial<Record<StemId, InstrumentInfo>>
  summary: SongSummary
}

export interface Peaks {
  perSecond: number
  duration: number
  peaks: Record<string, string>
}

export type JobStatus = 'queued' | 'running' | 'done' | 'error' | 'cancelled'

export interface Job {
  id: string
  songId: string | null
  kind: 'process' | 'lyrics' | 'reanalyze' | 'export'
  status: JobStatus
  progress: number
  message: string | null
  error: string | null
  result: { file: string; name: string; size: number; mime: string } | null
  downloadUrl: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface Health {
  ok: boolean
  version: string
  /** true en la app del celular sin computadora (todo se hace en el celular). */
  standalone?: boolean
  /** device: 'cuda' | 'mps' | 'cpu' en la computadora; 'phone' en el celular. */
  engine: { available: boolean; device: string | null; gpu?: string | null; detail: string }
  features: {
    youtube: boolean
    lyrics: boolean
    stretchExport: boolean
    ffmpeg: boolean
    /** Tempo, acordes y partes (false solo en versiones viejas de la app del celular). */
    analysis?: boolean
    /** Voz guía y sonidos de click. */
    guide?: boolean
  }
  dataDir: string
}

export interface PresetInfo {
  id: PresetId
  name: string
  description: string
  stems: StemId[]
}

export interface Presets {
  default: PresetId
  presets: PresetInfo[]
  stems: { id: StemId; name: string; color: string }[]
  qualities: { id: Quality; name: string; description: string }[]
}

export type GuideNumbering = 'verses' | 'all' | 'none'

export interface Settings {
  defaultPreset: PresetId
  defaultQuality: Quality
  band: StemId[]
  notation: 'american' | 'latin'
  countInBars: number
  metronomeVolume: number
  metronomeSound: string
  /** Dirección de Multitrack Alabanza (por defecto la misma computadora). */
  multitrackUrl: string
  exportClick: boolean
  exportGuide: boolean
  exportPreRollBars: number
  /** "moimoi" o un estilo de click del paquete de voces. */
  exportClickSound: string
  guideNumbering: GuideNumbering
  guideKeyChanges: boolean
  lanAccess: boolean
}

export interface SearchResult {
  id: string
  url: string
  title: string
  rawTitle: string | null
  artist: string | null
  channel: string | null
  duration: number | null
  thumbnail: string
}

export interface UrlInfo {
  url: string
  title: string
  artist: string | null
  duration: number | null
  thumbnail: string | null
}

export interface LyricsWord {
  start: number
  end: number
  text: string
}

export interface LyricsLine {
  start: number
  end: number
  text: string
  words: LyricsWord[]
}

export interface Lyrics {
  language: string | null
  model?: string
  lines: LyricsLine[]
  edited: boolean
}

export interface ExportRequest {
  type: 'multitrack' | 'stems' | 'mix'
  stems?: StemId[]
  format?: 'wav' | 'mp3' | 'flac'
  click?: boolean
  clickVolume?: number
  clickSound?: string
  tempo?: number
  semitones?: number
  mixer?: Partial<Record<StemId, MixerChannel>>
  guide?: boolean
  guideNumbering?: GuideNumbering
  guideKeyChanges?: boolean
  preRollBars?: number
}

// ---- voz guía -----------------------------------------------------------------------------

export type CueGroup = 'partes' | 'cuenta' | 'indicaciones'

export interface GuideCue {
  id: string
  name: string
  group: CueGroup
  file: string | null
}

export interface GuideFile {
  id: string
  original: string | null
  cue: string | null
  duration: number | null
  url: string
}

export interface GuideSet {
  id: string
  name: string
  files: number
  assigned: number
  active: boolean
}

export type ClickRole = 'accent' | 'beat' | 'eighth' | 'sixteenth'

export interface ClickStyle {
  id: string
  name: string
  sounds: Partial<Record<ClickRole, string>>
}

export interface GuideKit {
  active: string | null
  set: string | null
  sets: GuideSet[]
  cues: GuideCue[]
  files: GuideFile[]
  count: number
  missing: string[]
  clicks: ClickStyle[]
  /** Idiomas que vinieron con MoiMoi (las versiones viejas no lo mandan). */
  included?: string[]
}

export interface GuideImportSummary {
  added: number
  recognized: number
  clicks: number
  sets: string[]
  errors: { original: string; error: string }[]
  skipped: { original: string; skipped: string }[]
}

export interface GuideUploadResult extends GuideKit {
  summary: GuideImportSummary
}

// ---- red local y Multitrack Alabanza ----------------------------------------------------------

export interface NetworkInfo {
  lanAccess: boolean
  listening: boolean
  port: number
  httpsPort: number | null
  http: string[]
  https: string[]
  /** ¿La consulta viene de la misma computadora donde corre MoiMoi? */
  local: boolean
}

export interface MultitrackStatus {
  ok: boolean
  url: string
  bloqueado?: boolean
  error?: string
}

export interface SendResult {
  ok: boolean
  proyectoId: string
  nombre: string
  pistas: number
  marcadores: number
  activada: boolean
}
