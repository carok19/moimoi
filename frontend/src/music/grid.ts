import type { Analysis, Chord, Section, SongSettings, TempoEdit } from '../api/types'
import type { BeatGrid } from '../audio/StemPlayer'
import { lowerBound } from '../audio/StemPlayer'

/** Un tramo de tempo de la canción (en un popurrí, cada canción; si no, la canción entera). */
export interface TempoPart {
  start: number
  end: number
  bpm: number
  beatsPerBar: number
  steady: boolean
  /** El usuario cambió el tempo o el "1" de este tramo. */
  edited: boolean
}

export interface DerivedGrid extends BeatGrid {
  bpm: number | null
  downbeats: number[]
  parts: TempoPart[]
  /** Hay correcciones del usuario (la grilla se guarda en la canción para el paquete). */
  edited: boolean
}

export const EMPTY_GRID: DerivedGrid = { beats: [], accents: [], beatsPerBar: 4, bpm: null, downbeats: [], parts: [], edited: false }

const r3 = (t: number) => Math.round(t * 1000) / 1000

/** Tramos de tempo del análisis (los análisis viejos no los traen: uno solo, la canción entera). */
export function tempoParts(analysis: Analysis): TempoPart[] {
  const segments = analysis.tempo.segments
  if (segments?.length) return segments.map((s) => ({ ...s, edited: false }))
  return [{
    start: 0, end: analysis.duration, bpm: analysis.tempo.bpm ?? 0, beatsPerBar: analysis.tempo.beatsPerBar || 4,
    steady: analysis.tempo.steady, edited: false,
  }]
}

export function partIndexAt(parts: { start: number }[], t: number): number {
  let i = 0
  while (i + 1 < parts.length && t >= parts[i + 1].start) i++
  return i
}

/** Pulsos y acentos del análisis con las correcciones viejas (doble/mitad y "1" para toda la canción). */
function legacyGrid(analysis: Analysis, settings: SongSettings): { beats: number[]; accents: boolean[]; scale: number } {
  const perBar = analysis.tempo.beatsPerBar || 4
  let beats = analysis.beats.slice()
  const downs = new Set(analysis.downbeats.map(r3))
  let accents = beats.map((b) => downs.has(r3(b)))
  if (beats.length && !accents.some(Boolean)) accents = beats.map((_, i) => i % perBar === 0)
  let scale = 1
  if (settings.beatScale === 'double' && beats.length > 1) {
    const b2: number[] = []
    const a2: boolean[] = []
    beats.forEach((b, i) => {
      b2.push(b)
      a2.push(accents[i])
      if (i + 1 < beats.length) {
        b2.push((b + beats[i + 1]) / 2)
        a2.push(false)
      }
    })
    beats = b2
    accents = a2
    scale = 2
  } else if (settings.beatScale === 'half' && beats.length > 1) {
    const first = Math.max(0, accents.indexOf(true))
    const b2: number[] = []
    const a2: boolean[] = []
    let since = first % 2 // pulsos antes del primer "1": misma paridad que él
    beats.forEach((b, i) => {
      if (accents[i]) since = 0
      if (since % 2 === 0) {
        b2.push(b)
        a2.push(accents[i])
      }
      since++
    })
    beats = b2
    accents = a2
    scale = 0.5
  }
  const shift = (((settings.downbeatShift ?? 0) % perBar) + perBar) % perBar
  if (shift) accents = rotate(accents, shift, perBar)
  return { beats, accents, scale }
}

/** Mueve los acentos `shift` pulsos más tarde (el "1" del compás cae un pulso después). */
function rotate(accents: boolean[], shift: number, perBar: number): boolean[] {
  return accents.map((_, i) => {
    const from = i >= shift ? i - shift : i - shift + perBar
    return from < accents.length && accents[from]
  })
}

// ---- volver a acomodar los pulsos a otro tempo ---------------------------------------------------

let decoded: { data: string; env: Float64Array } | null = null

function onsetEnvelope(onset: NonNullable<Analysis['onset']>): Float64Array {
  if (decoded?.data === onset.data) return decoded.env
  const raw = atob(onset.data)
  const env = new Float64Array(raw.length)
  for (let i = 0; i < raw.length; i++) env[i] = raw.charCodeAt(i) / 255
  decoded = { data: onset.data, env }
  return env
}

/**
 * beat_track de librosa (programación dinámica de Ellis) con un tempo fijo, sobre un tramo de la
 * curva de ataques: los pulsos caen en los golpes reales de la música, al tempo pedido.
 */
export function trackBeats(env: Float64Array, fps: number, bpm: number, tightness = 200): number[] {
  const n = env.length
  if (n < 4 || !(bpm > 0)) return []
  const fpb = Math.round((fps * 60) / bpm)
  let mean = 0
  for (const v of env) mean += v
  mean /= n
  let sq = 0
  for (const v of env) sq += (v - mean) ** 2
  const norm = Math.sqrt(sq / Math.max(1, n - 1)) + 1e-300
  const kLen = 2 * fpb + 1
  const window = new Float64Array(kLen)
  for (let k = 0; k < kLen; k++) {
    const x = ((k - fpb) * 32) / fpb
    window[k] = Math.exp(-0.5 * x * x)
  }
  const half = kLen >> 1
  const local = new Float64Array(n)
  for (let i = 0; i < n; i++) {
    let acc = 0
    for (let k = Math.max(0, i + half - n + 1); k < Math.min(i + half, kLen); k++) acc += window[k] * (env[i + half - k] / norm)
    local[i] = acc
  }
  let maxLocal = -Infinity
  for (const v of local) maxLocal = Math.max(maxLocal, v)
  const thresh = 0.01 * maxLocal
  const back = new Int32Array(n)
  const cum = new Float64Array(n)
  const from = Math.round(fpb / 2)
  const logFpb = Math.log(fpb)
  let first = true
  for (let i = 0; i < n; i++) {
    let best = -Infinity
    let loc = -1
    for (let j = i - from; j > i - 2 * fpb - 1 && j >= 0; j--) {
      const d = Math.log(i - j) - logFpb
      const score = cum[j] - tightness * d * d
      if (score > best) {
        best = score
        loc = j
      }
    }
    cum[i] = loc >= 0 ? local[i] + best : local[i]
    if (first && local[i] < thresh) back[i] = -1
    else {
      back[i] = loc
      first = false
    }
  }
  // Último pulso: el último máximo local con puntaje >= la mitad de la mediana de los máximos.
  const maxima: number[] = []
  const isMax = new Uint8Array(n)
  for (let i = 1; i < n; i++) {
    const m = i < n - 1 ? cum[i] > cum[i - 1] && cum[i] >= cum[i + 1] : cum[i] > cum[i - 1]
    if (m) {
      isMax[i] = 1
      maxima.push(cum[i])
    }
  }
  maxima.sort((a, b) => a - b)
  const median = maxima.length ? (maxima.length % 2 ? maxima[maxima.length >> 1]
    : (maxima[maxima.length / 2 - 1] + maxima[maxima.length / 2]) / 2) : NaN
  let tail = n - 1
  for (let i = n - 1; i >= 0; i--) {
    if (isMax[i] && cum[i] >= 0.5 * median) {
      tail = i
      break
    }
  }
  const frames: number[] = []
  for (let i = tail; i >= 0; i = back[i]) frames.push(i)
  frames.reverse()
  return frames.filter((f) => local[f] > 0)
}

/** fit_steady_grid del análisis: si los pulsos van a tempo constante, la grilla perfecta. */
export function fitSteadyGrid(t: number[]): { period: number; offset: number; k0: number; k1: number; steady: boolean } | null {
  const n = t.length
  if (n < 12) return null
  const diffs = t.slice(1).map((v, i) => v - t[i])
  const sorted = [...diffs].sort((a, b) => a - b)
  const period = sorted.length % 2 ? sorted[sorted.length >> 1] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2
  if (!(period > 0)) return null
  const k = [0]
  for (let i = 1; i < n; i++) k.push(k[i - 1] + Math.max(1, Math.round(diffs[i - 1] / period)))
  let mask = t.map(() => true)
  let slope = period
  let intercept = t[0]
  for (let iter = 0; iter < 4; iter++) {
    const idx = mask.flatMap((m, i) => (m ? [i] : []))
    if (idx.length < 8) return null
    let sk = 0, st = 0, skk = 0, skt = 0
    for (const i of idx) {
      sk += k[i]
      st += t[i]
      skk += k[i] * k[i]
      skt += k[i] * t[i]
    }
    const den = idx.length * skk - sk * sk
    slope = (idx.length * skt - sk * st) / den
    intercept = (st - slope * sk) / idx.length
    const resid = idx.map((i) => t[i] - (slope * k[i] + intercept))
    const m = resid.reduce((a, b) => a + b, 0) / resid.length
    const std = Math.sqrt(resid.reduce((a, b) => a + (b - m) ** 2, 0) / resid.length)
    const limit = Math.max(0.03, 3 * std)
    mask = t.map((v, i) => Math.abs(v - (slope * k[i] + intercept)) < limit)
  }
  const kept = mask.flatMap((m, i) => (m ? [i] : []))
  const rms = Math.sqrt(kept.reduce((a, i) => a + (t[i] - (slope * k[i] + intercept)) ** 2, 0) / Math.max(1, kept.length))
  const coverage = kept.length / n
  const med = (v: number[]) => {
    const s = [...v].sort((a, b) => a - b)
    return s.length % 2 ? s[s.length >> 1] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2
  }
  const halfN = n >> 1
  const d1 = med(t.slice(1, halfN).map((v, i) => v - t[i]))
  const d2 = med(t.slice(halfN + 1).map((v, i) => v - t[halfN + i]))
  const drift = Math.abs(d1 - d2) / period
  return { period: slope, offset: intercept, k0: k[0], k1: k[n - 1], steady: rms < 0.02 && coverage > 0.85 && drift < 0.02 }
}

/**
 * Pulsos de un tramo a otro tempo: con la curva de ataques, siguiendo los golpes de la música (y
 * con grilla perfecta si van parejos); sin ella, a partir de los pulsos detectados.
 */
function retrack(analysis: Analysis, part: TempoPart, bpm: number, old: number[]): number[] {
  const detected = part.bpm
  const ratio = detected > 0 ? bpm / detected : 0
  const onset = analysis.onset
  if (onset?.data) {
    const env = onsetEnvelope(onset)
    const f0 = Math.max(0, Math.floor(part.start * onset.fps))
    const f1 = Math.min(env.length, Math.ceil(part.end * onset.fps))
    if (f1 - f0 > 8) {
      let times = trackBeats(env.subarray(f0, f1), onset.fps, bpm).map((f) => (f + f0) / onset.fps)
      // El detector corrige unos milisegundos el ataque: se usa la misma corrección que tenía el tramo.
      if (old.length) {
        const shifts = times.map((t) => {
          const j = lowerBound(old, t)
          const near = [old[j - 1], old[j]].filter((v) => v !== undefined)
          return near.reduce((a, b) => (Math.abs(b - t) < Math.abs(a - t) ? b : a), near[0]) - t
        }).filter((d) => Math.abs(d) < 0.05)
        if (shifts.length >= Math.max(4, times.length / 4)) {
          shifts.sort((a, b) => a - b)
          const offset = shifts[shifts.length >> 1]
          times = times.map((t) => t + offset)
        }
      }
      const fit = fitSteadyGrid(times)
      if (fit?.steady) {
        times = []
        for (let k = fit.k0; k <= fit.k1; k++) times.push(fit.offset + fit.period * k)
      }
      return times.filter((t) => t >= part.start - 0.05 && t < part.end)
    }
  }
  // Sin curva de ataques (análisis viejo): doble, mitad o una grilla pareja desde el primer pulso.
  if (Math.abs(ratio - 2) < 0.08 * 2) {
    const out: number[] = []
    old.forEach((b, i) => {
      out.push(b)
      if (i + 1 < old.length) out.push((b + old[i + 1]) / 2)
    })
    return out
  }
  if (Math.abs(ratio - 0.5) < 0.04) return old.filter((_, i) => i % 2 === 0)
  const start = old[0] ?? part.start
  const out: number[] = []
  for (let t = start; t < part.end; t += 60 / bpm) out.push(t)
  return out
}

/** El "1" de un tramo nuevo: la fase que mejor coincide con los "1" del análisis. */
function accentsFor(times: number[], perBar: number, downbeats: number[]): boolean[] {
  let bestPhase = 0
  let best = -1
  for (let phase = 0; phase < perBar; phase++) {
    let hits = 0
    for (let i = phase; i < times.length; i += perBar) {
      const j = lowerBound(downbeats, times[i] - 0.07)
      if (j < downbeats.length && Math.abs(downbeats[j] - times[i]) < 0.07) hits++
    }
    if (hits > best) {
      best = hits
      bestPhase = phase
    }
  }
  return times.map((_, i) => i >= bestPhase && (i - bestPhase) % perBar === 0)
}

/**
 * Pulsos y acentos finales, con las correcciones manuales del usuario:
 * - tempoEdits: el tempo o el "1" de un tramo (panel de Tempo del reproductor)
 * - beatScale 'double' / 'half' y downbeatShift (versiones anteriores: toda la canción)
 */
export function deriveGrid(analysis: Analysis, settings: SongSettings): DerivedGrid {
  const perBar = analysis.tempo.beatsPerBar || 4
  const legacy = legacyGrid(analysis, settings)
  let beats = legacy.beats
  let accents = legacy.accents
  const parts = tempoParts(analysis).map((p) => ({ ...p, bpm: p.bpm * legacy.scale }))
  const edits: TempoEdit[] = Array.isArray(settings.tempoEdits) ? settings.tempoEdits : []
  let edited = false
  for (const edit of edits) {
    const index = parts.findIndex((p) => Math.abs(p.start - edit.start) < 0.5)
    if (index < 0) continue
    const part = parts[index]
    const inPart = (t: number) => t >= part.start - 1e-6 && (index === parts.length - 1 || t < part.end)
    const lo = beats.findIndex(inPart)
    const hi = lo < 0 ? -1 : beats.length - [...beats].reverse().findIndex(inPart)
    let times = lo < 0 ? [] : beats.slice(lo, hi)
    let marks = lo < 0 ? [] : accents.slice(lo, hi)
    if (edit.bpm && edit.bpm > 0 && Math.abs(edit.bpm - part.bpm) > 0.05) {
      const downs = times.filter((_, i) => marks[i])
      times = retrack(analysis, part, edit.bpm, times)
      marks = accentsFor(times, part.beatsPerBar || perBar, downs)
      part.bpm = edit.bpm
      part.edited = true
    }
    const meter = part.beatsPerBar || perBar
    const shift = (((edit.shift ?? 0) % meter) + meter) % meter
    if (shift) {
      marks = rotate(marks, shift, meter)
      part.edited = true
    }
    if (!part.edited) continue
    edited = true
    // Reemplazar el tramo sin pulsos repetidos en los bordes.
    const before = lo < 0 ? beats.filter((t) => t < part.start) : beats.slice(0, lo)
    const beforeMarks = lo < 0 ? accents.slice(0, before.length) : accents.slice(0, lo)
    const afterStart = lo < 0 ? before.length : hi
    const after = beats.slice(afterStart)
    const afterMarks = accents.slice(afterStart)
    const gap = 0.4 * (60 / part.bpm)
    const keep = times.map((t) => (!before.length || t - before[before.length - 1] > gap) && (!after.length || after[0] - t > gap))
    beats = [...before, ...times.filter((_, i) => keep[i]), ...after]
    accents = [...beforeMarks, ...marks.filter((_, i) => keep[i]), ...afterMarks]
  }
  const main = parts.reduce((a, b) => (b.end - b.start > a.end - a.start ? b : a), parts[0])
  const bpm = main ? main.bpm || null : analysis.tempo.bpm ? analysis.tempo.bpm * legacy.scale : null
  return {
    beats, accents, beatsPerBar: perBar, bpm, parts, edited,
    downbeats: beats.filter((_, i) => accents[i]),
  }
}

/** Tempo del tramo que suena en `t` (el que se muestra en el reproductor). */
export function tempoAt(grid: DerivedGrid, t: number): number | null {
  if (!grid.parts.length) return grid.bpm
  return grid.parts[partIndexAt(grid.parts, t)].bpm || grid.bpm
}

export function chordIndexAt(chords: Chord[], t: number): number {
  let lo = 0
  let hi = chords.length - 1
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (chords[mid].end <= t) lo = mid + 1
    else if (chords[mid].start > t) hi = mid - 1
    else return mid
  }
  return -1
}

export function sectionIndexAt(sections: Section[], t: number): number {
  for (let i = 0; i < sections.length; i++) if (t >= sections[i].start && t < sections[i].end) return i
  return sections.length ? sections.length - 1 : -1
}

/** Posición dentro del compás del pulso actual (0 = "1"), o -1 si no hay pulso cerca. */
export function beatInBar(grid: BeatGrid, t: number): number {
  const i = lowerBound(grid.beats, t + 0.001) - 1
  if (i < 0 || i >= grid.beats.length) return -1
  const next = grid.beats[i + 1] ?? grid.beats[i] + 1
  if (t - grid.beats[i] > (next - grid.beats[i]) * 1.5) return -1
  // El último "1" antes de este pulso (en un popurrí cada tramo tiene el suyo).
  let j = i
  while (j >= 0 && !grid.accents[j]) j--
  if (j < 0) {
    const first = grid.accents.indexOf(true)
    return first < 0 ? i % grid.beatsPerBar : ((((i - first) % grid.beatsPerBar) + grid.beatsPerBar) % grid.beatsPerBar)
  }
  return (i - j) % grid.beatsPerBar
}
