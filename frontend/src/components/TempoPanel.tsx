import { useRef, useState } from 'react'
import { ExternalLink, Hand, Minus, Plus, RefreshCw, RotateCcw } from 'lucide-react'
import type { TempoEdit } from '../api/types'
import type { TempoPart } from '../music/grid'
import { formatTime } from '../music/theory'
import { Modal } from './Modal'

interface Props {
  parts: TempoPart[]
  /** Tramo que está sonando. */
  current: number
  /** Tempo detectado de cada tramo (sin correcciones), para "volver a lo detectado". */
  detected: number[]
  edits: TempoEdit[]
  rate: number
  songTitle: string
  songArtist: string | null
  /** El análisis es de una versión anterior (sin cambios de tempo): conviene volver a analizar. */
  oldAnalysis: boolean
  onEdit: (start: number, patch: Partial<TempoEdit> | null) => void
  onSeek: (t: number) => void
  onReanalyze?: () => void
  onClose: () => void
}

const round1 = (v: number) => Math.round(v * 10) / 10

/** Panel de tempo: escribir el BPM, marcarlo tocando, doble/mitad, mover el "1", buscarlo en internet. */
export function TempoPanel(p: Props) {
  const [selected, setSelected] = useState(p.current)
  const part = p.parts[selected] ?? p.parts[0]
  const edit = p.edits.find((e) => Math.abs(e.start - part.start) < 0.5)
  const [draft, setDraft] = useState<string | null>(null)
  const taps = useRef<number[]>([])
  const [tapped, setTapped] = useState<number | null>(null)
  const several = p.parts.length > 1

  const setBpm = (bpm: number) => {
    const value = Math.min(260, Math.max(30, round1(bpm)))
    p.onEdit(part.start, { bpm: Math.abs(value - p.detected[selected]) < 0.05 ? undefined : value })
    setDraft(null)
  }
  const commitDraft = () => {
    if (draft === null) return
    const value = Number(draft.replace(',', '.'))
    if (value >= 30 && value <= 260) setBpm(value)
    else setDraft(null)
  }
  const tap = () => {
    const now = performance.now()
    const list = taps.current.filter((t) => now - t < 3000) // si deja de tocar 3 s, empieza de nuevo
    list.push(now)
    taps.current = list.slice(-12)
    if (taps.current.length >= 4) {
      const gaps = taps.current.slice(1).map((t, i) => t - taps.current[i]).sort((a, b) => a - b)
      const median = gaps[gaps.length >> 1]
      setTapped(Math.round(60000 / median))
    } else {
      setTapped(null)
    }
  }
  const query = [p.songTitle, p.songArtist, 'bpm'].filter(Boolean).join(' ')

  return (
    <Modal title="Tempo" onClose={p.onClose} width={460}>
      <div className="stack tempo-panel">
        {p.oldAnalysis && (
          <div className="banner info small" style={{ margin: 0 }}>
            <div className="grow">
              Esta canción se analizó con una versión anterior. Vuelve a analizarla para que detecte los cambios de tempo
              y de tonalidad (por ejemplo, en un popurrí).
            </div>
            {p.onReanalyze && (
              <button className="btn small" onClick={p.onReanalyze}><RefreshCw size={14} />Analizar</button>
            )}
          </div>
        )}
        <div className="small muted">
          {several
            ? <>Esta canción cambia de tempo. Estás cambiando la parte que empieza en <b>{formatTime(part.start)}</b>.</>
            : 'El click y la voz guía siguen este tempo (también en el paquete para Multitrack).'}
        </div>

        <div className="tempo-big">
          <button className="btn icon" onClick={() => setBpm(part.bpm - 1)} aria-label="Un BPM menos"><Minus size={18} /></button>
          <label className="tempo-value">
            <input
              inputMode="decimal"
              value={draft ?? String(round1(part.bpm))}
              onChange={(e) => setDraft(e.target.value)}
              onBlur={commitDraft}
              onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()}
              aria-label="BPM"
            />
            <span>BPM{part.edited ? ' · elegido' : ' · detectado'}</span>
          </label>
          <button className="btn icon" onClick={() => setBpm(part.bpm + 1)} aria-label="Un BPM más"><Plus size={18} /></button>
        </div>
        {Math.abs(p.rate - 1) > 0.001 && (
          <div className="tiny faint" style={{ textAlign: 'center', marginTop: -6 }}>
            A la velocidad que elegiste ({Math.round(p.rate * 100)} %) suena a {Math.round(part.bpm * p.rate)} BPM.
          </div>
        )}

        <div className="row wrap tempo-actions">
          <button className="btn small" onClick={() => setBpm(part.bpm * 2)}>El doble (×2)</button>
          <button className="btn small" onClick={() => setBpm(part.bpm / 2)}>La mitad (÷2)</button>
          <button className="btn small" onClick={() => p.onEdit(part.start, { shift: (edit?.shift ?? 0) + 1 })}>
            Mover el "1" un pulso
          </button>
        </div>

        <div className="tap-box">
          <button className="btn tap" onClick={tap} onPointerDown={(e) => e.preventDefault()}>
            <Hand size={18} />Toca aquí al ritmo
          </button>
          <div className="small">
            {tapped
              ? <>Tocaste <b>{tapped} BPM</b>. <button className="linkish" onClick={() => setBpm(tapped)}>Usar {tapped}</button></>
              : <span className="muted">Toca por lo menos 4 veces siguiendo la música.</span>}
          </div>
        </div>

        <a className="btn small ghost" style={{ alignSelf: 'flex-start' }} target="_blank" rel="noopener"
          href={`https://www.google.com/search?q=${encodeURIComponent(query)}`}>
          <ExternalLink size={14} />Buscar el BPM en internet
        </a>

        {several && (
          <div className="stack" style={{ gap: 4 }}>
            <div className="small" style={{ fontWeight: 700 }}>Cambios de tempo</div>
            {p.parts.map((x, i) => (
              <button key={x.start} className={`tempo-part${i === selected ? ' on' : ''}`}
                onClick={() => { setSelected(i); setDraft(null); p.onSeek(x.start) }}>
                <span>{formatTime(x.start)}</span>
                <b>{Math.round(x.bpm)} BPM</b>
                <span className="tiny faint">{x.edited ? 'elegido' : ''}{i === p.current ? (x.edited ? ' · suena ahora' : 'suena ahora') : ''}</span>
              </button>
            ))}
          </div>
        )}

        {(edit || p.edits.length > 0) && (
          <div className="row wrap">
            {edit && (
              <button className="btn small ghost" onClick={() => p.onEdit(part.start, null)}>
                <RotateCcw size={14} />{several ? 'Esta parte como se detectó' : 'Volver al tempo detectado'}
              </button>
            )}
            {several && p.edits.length > 1 && (
              <button className="btn small ghost" onClick={() => p.edits.forEach((e) => p.onEdit(e.start, null))}>
                <RotateCcw size={14} />Todas como se detectaron
              </button>
            )}
          </div>
        )}
      </div>
    </Modal>
  )
}
