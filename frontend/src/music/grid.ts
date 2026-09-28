import type { Analysis, Chord, Section, SongSettings } from '../api/types'
import type { BeatGrid } from '../audio/StemPlayer'
import { lowerBound } from '../audio/StemPlayer'

/**
 * Pulsos y acentos finales, con las correcciones manuales del usuario:
 * - beatScale 'double' / 'half' (si el detector contó el doble o la mitad del tempo)
 * - downbeatShift (mover el "1" del compás)
 */
export function deriveGrid(analysis: Analysis, settings: SongSettings): BeatGrid & { bpm: number | null; downbeats: number[] } {
  const perBar = analysis.tempo.beatsPerBar || 4
  let beats = analysis.beats.slice()
  const downSet = new Set(analysis.downbeats.map((t) => t.toFixed(3)))
  let phase = beats.findIndex((b) => downSet.has(b.toFixed(3)))
  if (phase < 0) phase = 0
  let bpm = analysis.tempo.bpm
  if (settings.beatScale === 'double' && beats.length > 1) {
    const doubled: number[] = []
    for (let i = 0; i < beats.length; i++) {
      doubled.push(beats[i])
      if (i + 1 < beats.length) doubled.push((beats[i] + beats[i + 1]) / 2)
    }
    beats = doubled
    phase *= 2
    bpm = bpm ? bpm * 2 : bpm
  } else if (settings.beatScale === 'half' && beats.length > 1) {
    const offset = phase % 2
    beats = beats.filter((_, i) => i % 2 === offset)
    phase = Math.floor(phase / 2)
    bpm = bpm ? bpm / 2 : bpm
  }
  const shift = settings.downbeatShift ?? 0
  const first = (((phase + shift) % perBar) + perBar) % perBar
  const accents = beats.map((_, i) => (i - first) % perBar === 0)
  const downbeats = beats.filter((_, i) => accents[i])
  return { beats, accents, beatsPerBar: perBar, bpm, downbeats }
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
  let first = grid.accents.indexOf(true)
  if (first < 0) first = 0
  return (((i - first) % grid.beatsPerBar) + grid.beatsPerBar) % grid.beatsPerBar
}
