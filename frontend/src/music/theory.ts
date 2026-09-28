import type { Chord } from '../api/types'

export type Notation = 'american' | 'latin'

const SHARP = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B']
const FLAT = ['C', 'Db', 'D', 'Eb', 'E', 'F', 'Gb', 'G', 'Ab', 'A', 'Bb', 'B']
const LATIN_SHARP = ['Do', 'Do#', 'Re', 'Re#', 'Mi', 'Fa', 'Fa#', 'Sol', 'Sol#', 'La', 'La#', 'Si']
const LATIN_FLAT = ['Do', 'Reb', 'Re', 'Mib', 'Mi', 'Fa', 'Solb', 'Sol', 'Lab', 'La', 'Sib', 'Si']
const FLAT_MAJOR = new Set([5, 10, 3, 8, 1, 6])
const FLAT_MINOR = new Set([2, 7, 0, 5, 10, 3])

export const QUALITY_SUFFIX: Record<string, string> = {
  maj: '', min: 'm', '7': '7', maj7: 'maj7', min7: 'm7', sus4: 'sus4', sus2: 'sus2', dim: 'dim',
}

export const CHORD_INTERVALS: Record<string, number[]> = {
  maj: [0, 4, 7], min: [0, 3, 7], '7': [0, 4, 7, 10], maj7: [0, 4, 7, 11], min7: [0, 3, 7, 10],
  sus4: [0, 5, 7], sus2: [0, 2, 7], dim: [0, 3, 6],
}

export const mod12 = (n: number) => ((Math.round(n) % 12) + 12) % 12

export function usesFlats(tonic: number, mode: 'major' | 'minor'): boolean {
  return (mode === 'major' ? FLAT_MAJOR : FLAT_MINOR).has(mod12(tonic))
}

export function noteName(pc: number, flats: boolean, notation: Notation = 'american'): string {
  const table = notation === 'latin' ? (flats ? LATIN_FLAT : LATIN_SHARP) : flats ? FLAT : SHARP
  return table[mod12(pc)]
}

export interface Transposition {
  semitones: number
  flats: boolean
  notation: Notation
}

export function chordLabel(chord: Pick<Chord, 'root' | 'quality' | 'bass'>, t: Transposition): string {
  if (chord.quality === 'N' || chord.root < 0) return '—'
  const root = noteName(chord.root + t.semitones, t.flats, t.notation)
  let label = root + (QUALITY_SUFFIX[chord.quality] ?? '')
  if (chord.bass !== null && chord.bass !== undefined && mod12(chord.bass) !== mod12(chord.root)) {
    label += '/' + noteName(chord.bass + t.semitones, t.flats, t.notation)
  }
  return label
}

export function keyName(tonic: number, mode: 'major' | 'minor', semitones = 0, notation: Notation = 'american'): string {
  const pc = mod12(tonic + semitones)
  const flats = usesFlats(pc, mode)
  if (notation === 'latin') return `${noteName(pc, flats, 'latin')} ${mode === 'minor' ? 'menor' : 'mayor'}`
  return noteName(pc, flats) + (mode === 'minor' ? 'm' : '')
}

/** "Sol mayor · G" con la notación elegida primero. */
export function keyFull(tonic: number, mode: 'major' | 'minor', semitones = 0, notation: Notation = 'american'): string {
  const american = keyName(tonic, mode, semitones, 'american')
  const latin = keyName(tonic, mode, semitones, 'latin')
  return notation === 'latin' ? `${latin} (${american})` : `${american} · ${latin}`
}

export function chordTones(chord: Pick<Chord, 'root' | 'quality'>, semitones = 0): number[] {
  const intervals = CHORD_INTERVALS[chord.quality] ?? []
  return intervals.map((i) => mod12(chord.root + semitones + i))
}

export function formatTime(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !isFinite(seconds)) return '0:00'
  const s = Math.max(0, seconds)
  const m = Math.floor(s / 60)
  const rest = Math.floor(s % 60)
  return `${m}:${rest.toString().padStart(2, '0')}`
}

export function formatTimePrecise(seconds: number): string {
  const s = Math.max(0, seconds)
  const m = Math.floor(s / 60)
  const rest = s - m * 60
  return `${m}:${rest.toFixed(1).padStart(4, '0')}`
}

export function semitoneLabel(semitones: number): string {
  if (semitones === 0) return 'Original'
  return `${semitones > 0 ? '+' : ''}${semitones}`
}
