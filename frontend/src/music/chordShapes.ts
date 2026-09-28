// Digitaciones de guitarra: acordes abiertos comunes + cejillas generadas (formas de Mi y de La).
import { mod12 } from './theory'

export interface GuitarShape {
  /** Traste por cuerda, de la 6ª (Mi grave) a la 1ª. -1 = no se toca, 0 = al aire. */
  frets: number[]
  /** Traste de la cejilla (si hay). */
  barre?: number
}

const OPEN: Record<string, number[]> = {
  '0:maj': [-1, 3, 2, 0, 1, 0], '0:maj7': [-1, 3, 2, 0, 0, 0], '0:7': [-1, 3, 2, 3, 1, 0],
  '0:sus2': [-1, 3, 0, 0, 1, 3], '0:sus4': [-1, 3, 3, 0, 1, 1],
  '2:maj': [-1, -1, 0, 2, 3, 2], '2:min': [-1, -1, 0, 2, 3, 1], '2:7': [-1, -1, 0, 2, 1, 2],
  '2:maj7': [-1, -1, 0, 2, 2, 2], '2:min7': [-1, -1, 0, 2, 1, 1], '2:sus4': [-1, -1, 0, 2, 3, 3],
  '2:sus2': [-1, -1, 0, 2, 3, 0],
  '4:maj': [0, 2, 2, 1, 0, 0], '4:min': [0, 2, 2, 0, 0, 0], '4:7': [0, 2, 0, 1, 0, 0],
  '4:maj7': [0, 2, 1, 1, 0, 0], '4:min7': [0, 2, 0, 0, 0, 0], '4:sus4': [0, 2, 2, 2, 0, 0],
  '4:sus2': [0, 2, 4, 4, 0, 0],
  '5:maj7': [-1, -1, 3, 2, 1, 0],
  '7:maj': [3, 2, 0, 0, 0, 3], '7:7': [3, 2, 0, 0, 0, 1], '7:maj7': [3, 2, 0, 0, 0, 2],
  '7:sus4': [3, 3, 0, 0, 1, 3], '7:sus2': [3, 0, 0, 0, 3, 3],
  '9:maj': [-1, 0, 2, 2, 2, 0], '9:min': [-1, 0, 2, 2, 1, 0], '9:7': [-1, 0, 2, 0, 2, 0],
  '9:maj7': [-1, 0, 2, 1, 2, 0], '9:min7': [-1, 0, 2, 0, 1, 0], '9:sus4': [-1, 0, 2, 2, 3, 0],
  '9:sus2': [-1, 0, 2, 2, 0, 0],
  '11:7': [-1, 2, 1, 2, 0, 2],
}

// Formas relativas a la cejilla.
const E_SHAPE: Record<string, number[]> = {
  maj: [0, 2, 2, 1, 0, 0], min: [0, 2, 2, 0, 0, 0], '7': [0, 2, 0, 1, 0, 0], maj7: [0, -1, 1, 1, 0, -1],
  min7: [0, 2, 0, 0, 0, 0], sus4: [0, 2, 2, 2, 0, 0], sus2: [0, 2, 4, 4, 0, 0],
}
const A_SHAPE: Record<string, number[]> = {
  maj: [-1, 0, 2, 2, 2, 0], min: [-1, 0, 2, 2, 1, 0], '7': [-1, 0, 2, 0, 2, 0], maj7: [-1, 0, 2, 1, 2, 0],
  min7: [-1, 0, 2, 0, 1, 0], sus4: [-1, 0, 2, 2, 3, 0], sus2: [-1, 0, 2, 2, 0, 0], dim: [-1, 0, 1, 2, 1, -1],
}

function fromShape(shape: number[], fret: number): GuitarShape {
  return {
    frets: shape.map((f) => (f < 0 ? -1 : f + fret)),
    barre: fret > 0 ? fret : undefined,
  }
}

export function guitarShape(root: number, quality: string): GuitarShape | null {
  if (quality === 'N' || root < 0) return null
  const pc = mod12(root)
  const open = OPEN[`${pc}:${quality}`]
  if (open) return { frets: open }
  const eFret = mod12(pc - 4)
  const aFret = mod12(pc - 9)
  const eShape = E_SHAPE[quality]
  const aShape = A_SHAPE[quality]
  const options: GuitarShape[] = []
  if (eShape) options.push(fromShape(eShape, eFret === 0 ? 12 : eFret))
  if (aShape) options.push(fromShape(aShape, aFret === 0 ? 12 : aFret))
  if (!options.length) return null
  return options.reduce((best, s) => ((s.barre ?? 0) < (best.barre ?? 0) ? s : best))
}
