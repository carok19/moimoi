import { useRef, useState } from 'react'
import type { Chord } from '../api/types'
import type { BeatGrid, StemPlayer } from '../audio/StemPlayer'
import { useFrame, useSampled } from '../hooks/useFrame'
import { beatInBar, chordIndexAt } from '../music/grid'
import { chordLabel, mod12, type Transposition } from '../music/theory'
import { GuitarDiagram, PianoDiagram } from './ChordDiagrams'

type Instrument = 'guitar' | 'piano'

function storedInstrument(): Instrument {
  try {
    return localStorage.getItem('moimoi.diagram') === 'piano' ? 'piano' : 'guitar'
  } catch {
    return 'guitar'
  }
}

interface Props {
  player: StemPlayer
  chords: Chord[]
  grid: BeatGrid
  transposition: Transposition
}

export function ChordPanel({ player, chords, grid, transposition }: Props) {
  const [instrument, setInstrument] = useState<Instrument>(storedInstrument)
  const index = useSampled(() => chordIndexAt(chords, player.position + 0.05), 60)
  const dots = useRef<(HTMLElement | null)[]>([])
  const perBar = grid.beatsPerBar || 4

  useFrame(() => {
    const beat = player.playing && !player.countingIn ? beatInBar(grid, player.position) : -1
    dots.current.forEach((el, i) => el?.classList.toggle('on', i === beat))
  })

  const current = index >= 0 ? chords[index] : undefined
  let next: Chord | undefined
  for (let i = Math.max(0, index + 1); i < chords.length; i++) {
    if (chords[i].quality !== 'N') {
      next = chords[i]
      break
    }
  }
  const choose = (value: Instrument) => {
    setInstrument(value)
    try {
      localStorage.setItem('moimoi.diagram', value)
    } catch {
      // sin almacenamiento local
    }
  }
  const name = current ? chordLabel(current, transposition) : '—'
  // Nombres largos ("F#m7/C#") en letra más chica para que entren al lado del diagrama.
  const size = name.length > 5 ? ' long' : name.length > 3 ? ' mid' : ''
  const root = current && current.quality !== 'N' ? mod12(current.root + transposition.semitones) : -1
  const bass = current && current.bass !== null ? mod12(current.bass + transposition.semitones) : null

  return (
    <section className="card chord-now" aria-label="Acorde actual">
      <div>
        <div className="small faint" style={{ fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase' }}>Acorde</div>
        <div className="chord-big">
          <div className={`current${size}`} aria-live="polite">{name}</div>
          {next && (
            <div className="next"><small>Sigue</small>{chordLabel(next, transposition)}</div>
          )}
        </div>
        <div className="beat-dots" aria-hidden>
          {Array.from({ length: perBar }, (_, i) => (
            <i key={i} ref={(el) => { dots.current[i] = el }} className={i === 0 ? 'first' : ''} />
          ))}
        </div>
      </div>
      <div className="diagram-box">
        {root >= 0 && current ? (
          instrument === 'guitar'
            ? <GuitarDiagram root={root} quality={current.quality} bass={bass} />
            : <PianoDiagram root={root} quality={current.quality} bass={bass} />
        ) : <div style={{ width: 118, height: 82 }} />}
        <div className="segmented">
          <button className={instrument === 'guitar' ? 'active' : ''} onClick={() => choose('guitar')}>Guitarra</button>
          <button className={instrument === 'piano' ? 'active' : ''} onClick={() => choose('piano')}>Piano</button>
        </div>
      </div>
    </section>
  )
}

const PPS = 70

export function ChordStrip({ player, chords, downbeats, transposition, onSeek }: {
  player: StemPlayer
  chords: Chord[]
  downbeats: number[]
  transposition: Transposition
  onSeek: (t: number) => void
}) {
  const container = useRef<HTMLDivElement>(null)
  const rail = useRef<HTMLDivElement>(null)
  const index = useSampled(() => chordIndexAt(chords, player.position + 0.05), 80)
  useFrame(() => {
    if (!container.current || !rail.current) return
    const offset = container.current.clientWidth / 2 - player.position * PPS
    rail.current.style.transform = `translateX(${offset}px)`
  })
  if (!chords.length) {
    return <div className="chord-strip" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <span className="small faint">No se detectaron acordes</span>
    </div>
  }
  return (
    <div className="chord-strip" ref={container} aria-label="Acordes de la canción">
      <div className="rail" ref={rail} style={{ width: player.duration * PPS }}>
        {downbeats.map((t, i) => <div key={`b${i}`} className="bar" style={{ left: t * PPS }} />)}
        {chords.map((c, i) => (
          <div
            key={i}
            className={`box${c.quality === 'N' ? ' none' : ''}${i === index ? ' current' : ''}`}
            style={{ left: c.start * PPS + 2, width: Math.max(18, (c.end - c.start) * PPS - 4) }}
            onClick={() => onSeek(c.start)}
            title={chordLabel(c, transposition)}
          >
            {c.quality === 'N' ? '' : chordLabel(c, transposition)}
          </div>
        ))}
      </div>
      <div className="needle" />
    </div>
  )
}
