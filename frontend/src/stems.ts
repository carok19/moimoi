import type { PresetId, StemId } from './api/types'

export const STEM_INFO: Record<StemId, { name: string; color: string }> = {
  vocals: { name: 'Voz', color: '#ff5d8f' },
  drums: { name: 'Batería', color: '#ffa24c' },
  bass: { name: 'Bajo', color: '#a77bff' },
  guitar: { name: 'Guitarra', color: '#ffd75e' },
  piano: { name: 'Piano', color: '#57a8ff' },
  other: { name: 'Otros', color: '#3fd9b0' },
  instrumental: { name: 'Acompañamiento', color: '#9fb3c8' },
}

export const BAND_INSTRUMENTS: StemId[] = ['vocals', 'drums', 'bass', 'guitar', 'piano', 'other']

export const PRESET_STEMS: Record<PresetId, StemId[]> = {
  '2stems': ['vocals', 'instrumental'],
  '4stems': ['vocals', 'drums', 'bass', 'other'],
  '6stems': ['vocals', 'drums', 'bass', 'guitar', 'piano', 'other'],
}

/**
 * Qué pistas cubre la banda: con 2 o 4 pistas, "Acompañamiento"/"Otros" contienen varios
 * instrumentos, así que se consideran cubiertos solo si la banda los tiene todos.
 */
export function bandCovers(stem: StemId, band: StemId[]): boolean {
  if (stem === 'instrumental') return ['drums', 'bass', 'guitar', 'piano', 'other'].every((s) => band.includes(s as StemId))
  return band.includes(stem)
}

export const SECTION_COLORS: Record<string, string> = {
  Intro: '#5b6478',
  Verso: '#3c6fd8',
  'Pre-coro': '#8a5cf0',
  Coro: '#d94a7c',
  Puente: '#d88a2c',
  Instrumental: '#239f86',
  Final: '#5b6478',
}

export function sectionColor(label: string, group: string): string {
  const base = label.replace(/\s*\d+$/, '')
  if (SECTION_COLORS[base]) return SECTION_COLORS[base]
  const palette = ['#4d7ea8', '#7a5cc8', '#b8527a', '#3b9c8a', '#a8783a', '#6b8f3a']
  return palette[(group.charCodeAt(0) - 65) % palette.length] ?? '#5b6478'
}
