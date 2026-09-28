import { guitarShape } from '../music/chordShapes'
import { CHORD_INTERVALS, mod12 } from '../music/theory'

interface Props {
  root: number
  quality: string
  bass: number | null
}

/** Diagrama de guitarra (cuerdas verticales, 6ª a la izquierda). */
export function GuitarDiagram({ root, quality }: Props) {
  const shape = guitarShape(root, quality)
  const W = 118
  const H = 132
  const left = 22
  const top = 26
  const stringGap = 16
  const fretGap = 20
  const frets = 5
  if (!shape) return <div className="faint small" style={{ width: W, textAlign: 'center' }}>Sin diagrama</div>
  const played = shape.frets.filter((f) => f > 0)
  const minFret = played.length ? Math.min(...played) : 1
  const maxFret = played.length ? Math.max(...played) : 1
  const base = maxFret <= frets ? 1 : shape.barre ?? minFret
  const y = (fret: number) => top + (fret - base + 0.5) * fretGap
  const x = (string: number) => left + string * stringGap
  let barre: { from: number; to: number; fret: number } | null = null
  if (shape.barre) {
    const strings = shape.frets.map((f, i) => (f === shape.barre ? i : -1)).filter((i) => i >= 0)
    if (strings.length >= 2) barre = { from: strings[0], to: strings[strings.length - 1], fret: shape.barre }
  }
  return (
    <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Diagrama de guitarra">
      {base === 1
        ? <rect x={left - 1} y={top - 3} width={stringGap * 5 + 2} height={4} rx={1} fill="#e6e8ee" />
        : <text x={4} y={top + fretGap * 0.65} fontSize={11} fill="#a9afbd" fontWeight={700}>{base}</text>}
      {Array.from({ length: frets + 1 }, (_, i) => (
        <line key={`f${i}`} x1={left} x2={left + stringGap * 5} y1={top + i * fretGap} y2={top + i * fretGap} stroke="#4a5160" strokeWidth={1} />
      ))}
      {Array.from({ length: 6 }, (_, i) => (
        <line key={`s${i}`} x1={x(i)} x2={x(i)} y1={top} y2={top + frets * fretGap} stroke="#8d95a5" strokeWidth={i < 3 ? 1.4 : 1} />
      ))}
      {barre && (
        <rect x={x(barre.from) - 6} y={y(barre.fret) - 6} width={x(barre.to) - x(barre.from) + 12} height={12} rx={6} fill="#b79bff" />
      )}
      {shape.frets.map((f, i) => {
        if (f < 0) return <text key={i} x={x(i)} y={top - 9} fontSize={11} textAnchor="middle" fill="#8d95a5">×</text>
        if (f === 0) return <circle key={i} cx={x(i)} cy={top - 12} r={4} fill="none" stroke="#c9cfdb" strokeWidth={1.4} />
        if (barre && f === barre.fret && i >= barre.from && i <= barre.to) return null
        return <circle key={i} cx={x(i)} cy={y(f)} r={6} fill="#b79bff" />
      })}
    </svg>
  )
}

const WHITE = [0, 2, 4, 5, 7, 9, 11]
const BLACK: Record<number, number> = { 1: 0, 3: 1, 6: 3, 8: 4, 10: 5 }

/** Teclado de dos octavas con las notas del acorde (y el bajo, si es invertido). */
export function PianoDiagram({ root, quality, bass }: Props) {
  const intervals = CHORD_INTERVALS[quality]
  if (!intervals || root < 0) return <div className="faint small">Sin diagrama</div>
  const W = 196
  const H = 82
  const whiteW = W / 14
  // Mano izquierda (octava inferior): el bajo. Mano derecha (octava superior): el acorde.
  const notes = new Set(intervals.map((i) => {
    let n = 12 + mod12(root) + i
    while (n > 23) n -= 12
    return n
  }))
  const bassNote = mod12(bass ?? root)
  const keyFill = (n: number, isBlack: boolean) => {
    if (n === bassNote) return '#ff8fb3'
    if (notes.has(n)) return '#b79bff'
    return isBlack ? '#1b1e25' : '#eef0f4'
  }
  const whites = []
  const blacks = []
  for (let octave = 0; octave < 2; octave++) {
    for (let w = 0; w < 7; w++) {
      const n = octave * 12 + WHITE[w]
      const xPos = (octave * 7 + w) * whiteW
      whites.push(<rect key={`w${n}`} x={xPos + 0.5} y={0.5} width={whiteW - 1} height={H - 1} rx={3} fill={keyFill(n, false)} stroke="#2a2f3a" />)
    }
    for (const [pc, afterWhite] of Object.entries(BLACK)) {
      const n = octave * 12 + Number(pc)
      const xPos = (octave * 7 + afterWhite + 1) * whiteW - whiteW * 0.32
      blacks.push(<rect key={`b${n}`} x={xPos} y={0} width={whiteW * 0.64} height={H * 0.6} rx={2} fill={keyFill(n, true)} stroke="#0d0f13" />)
    }
  }
  return (
    <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Diagrama de piano">
      {whites}
      {blacks}
    </svg>
  )
}
