import { useEffect, useMemo, useRef, useState } from 'react'
import type { Section } from '../api/types'
import { drawWave } from '../audio/peaks'
import { lowerBound, type LoopRange, type StemPlayer } from '../audio/StemPlayer'
import { useFrame } from '../hooks/useFrame'
import { formatTime } from '../music/theory'
import { sectionColor } from '../stems'

interface Props {
  player: StemPlayer
  peaks: Uint8Array
  duration: number
  sections: Section[]
  beats: number[]
  loop: LoopRange | null
  loopOn: boolean
  onSeek: (t: number) => void
  onLoop: (range: LoopRange | null, enable: boolean) => void
}

type Drag = { mode: 'new' | 'start' | 'end'; anchor: number; x: number; moved: boolean }

function snap(t: number, beats: number[]): number {
  if (!beats.length) return t
  const i = lowerBound(beats, t)
  const candidates = [beats[i - 1], beats[i]].filter((b) => b !== undefined) as number[]
  const best = candidates.reduce((a, b) => (Math.abs(b - t) < Math.abs(a - t) ? b : a), candidates[0])
  return Math.abs(best - t) < 0.15 ? best : t
}

const LABEL_FONT = '700 12px Inter, "Segoe UI", system-ui, -apple-system, Roboto, "Helvetica Neue", Arial, sans-serif'
let measure: CanvasRenderingContext2D | null | undefined

function textWidth(text: string): number {
  if (measure === undefined) measure = document.createElement('canvas').getContext('2d')
  if (!measure) return text.length * 7.5
  measure.font = LABEL_FONT
  return measure.measureText(text).width
}

/** Formas cortas de los nombres que pone el análisis, de más larga a más corta. */
const SHORT: Record<string, string[]> = {
  Intro: ['In'],
  Verso: ['V'],
  'Pre-coro': ['Pre', 'PC'],
  Coro: ['C'],
  Puente: ['Pte', 'P'],
  Instrumental: ['Inst', 'I'],
  Final: ['Fin', 'F'],
}

/** El nombre de la parte que entra en su recuadro ("Verso 1" -> "V1"); los nombres propios se cortan con "…". */
function fitLabel(label: string, px: number): string {
  const room = px - 15 // relleno (6 + 6), borde (1 + 1) y un píxel de margen
  if (textWidth(label) <= room) return label
  const match = label.match(/^(.*?)\s*(\d+)$/)
  const base = match ? match[1] : label
  const number = match ? match[2] : ''
  const forms = SHORT[base]
  if (!forms) return room >= 18 ? label : ''
  for (const form of forms) {
    if (textWidth(form + number) <= room) return form + number
  }
  return ''
}

export function Timeline({ player, peaks, duration, sections, beats, loop, loopOn, onSeek, onLoop }: Props) {
  const wrap = useRef<HTMLDivElement>(null)
  const base = useRef<HTMLCanvasElement>(null)
  const played = useRef<HTMLCanvasElement>(null)
  const playedBox = useRef<HTMLDivElement>(null)
  const line = useRef<HTMLDivElement>(null)
  const timeLabel = useRef<HTMLSpanElement>(null)
  const [width, setWidth] = useState(0)
  const [draft, setDraft] = useState<LoopRange | null>(null)
  const drag = useRef<Drag | null>(null)

  useEffect(() => {
    const el = wrap.current
    if (!el) return
    const observer = new ResizeObserver(() => setWidth(el.clientWidth))
    observer.observe(el)
    setWidth(el.clientWidth)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    if (!width) return
    if (base.current) drawWave(base.current, peaks, { color: '#3b4252' })
    if (played.current) drawWave(played.current, peaks, { color: '#c4a8ff' })
  }, [peaks, width])

  useFrame(() => {
    const p = duration > 0 ? player.position / duration : 0
    if (playedBox.current) playedBox.current.style.width = `${p * 100}%`
    if (line.current) line.current.style.transform = `translateX(${p * width}px)`
    if (timeLabel.current) timeLabel.current.textContent = formatTime(player.position)
  })

  const timeAt = (clientX: number) => {
    const rect = wrap.current!.getBoundingClientRect()
    return Math.min(duration, Math.max(0, ((clientX - rect.left) / rect.width) * duration))
  }

  const shown = draft ?? (loop && loopOn ? loop : null)
  const labels = useMemo(
    () => sections.map((s) => (width && duration ? fitLabel(s.label, ((s.end - s.start) / duration) * width - 3) : s.label)),
    [sections, width, duration],
  )

  const onPointerDown = (e: React.PointerEvent) => {
    if (e.button !== 0) return
    e.currentTarget.setPointerCapture(e.pointerId)
    const target = e.target as HTMLElement
    const mode = target.dataset.handle === 'start' ? 'start' : target.dataset.handle === 'end' ? 'end' : 'new'
    drag.current = { mode, anchor: timeAt(e.clientX), x: e.clientX, moved: false }
  }
  const onPointerMove = (e: React.PointerEvent) => {
    const d = drag.current
    if (!d) return
    if (!d.moved && Math.abs(e.clientX - d.x) < 5) return
    d.moved = true
    const t = snap(timeAt(e.clientX), beats)
    if (d.mode === 'new') {
      const a = snap(d.anchor, beats)
      setDraft({ start: Math.min(a, t), end: Math.max(a, t) })
    } else if (loop) {
      const start = d.mode === 'start' ? Math.min(t, loop.end - 0.3) : loop.start
      const end = d.mode === 'end' ? Math.max(t, loop.start + 0.3) : loop.end
      setDraft({ start, end })
    }
  }
  const onPointerUp = (e: React.PointerEvent) => {
    const d = drag.current
    drag.current = null
    if (!d) return
    if (!d.moved) {
      if (d.mode === 'new') onSeek(timeAt(e.clientX))
      return
    }
    if (draft && draft.end - draft.start >= 0.5) onLoop(draft, true)
    setDraft(null)
  }

  return (
    <section className="card pad timeline" aria-label="Línea de tiempo">
      <div className="sections">
        {sections.map((s, i) => {
          const looping = !!(loop && loopOn && Math.abs(loop.start - s.start) < 0.05 && Math.abs(loop.end - s.end) < 0.05)
          return (
            <div
              key={i}
              className={`section${looping ? ' looping' : ''}`}
              style={{
                left: `${(s.start / duration) * 100}%`,
                width: `calc(${((s.end - s.start) / duration) * 100}% - 3px)`,
                background: sectionColor(s.label, s.group),
              }}
              title={`${s.label} · clic: ir · doble clic: repetir esta parte`}
              onClick={() => onSeek(s.start)}
              onDoubleClick={() => onLoop({ start: s.start, end: s.end }, true)}
            >
              <span className="ellipsis">{labels[i]}</span>
            </div>
          )
        })}
      </div>
      <div
        ref={wrap}
        style={{ position: 'relative', height: 76, cursor: 'pointer' }}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        title="Clic: ir a ese punto · arrastrar: marcar un loop"
      >
        <canvas ref={base} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }} />
        <div ref={playedBox} style={{ position: 'absolute', top: 0, bottom: 0, left: 0, width: 0, overflow: 'hidden', pointerEvents: 'none' }}>
          <canvas ref={played} style={{ position: 'absolute', top: 0, left: 0, width: width || '100%', height: '100%' }} />
        </div>
        {shown && (
          <div
            style={{
              position: 'absolute', top: -2, bottom: -2, left: `${(shown.start / duration) * 100}%`,
              width: `${((shown.end - shown.start) / duration) * 100}%`, background: 'rgba(255, 255, 255, 0.10)',
              border: '1px solid rgba(255,255,255,0.55)', borderRadius: 6,
            }}
          >
            <div data-handle="start" style={{ position: 'absolute', left: -5, top: 0, bottom: 0, width: 10, cursor: 'ew-resize' }} />
            <div data-handle="end" style={{ position: 'absolute', right: -5, top: 0, bottom: 0, width: 10, cursor: 'ew-resize' }} />
          </div>
        )}
        <div ref={line} className="playline" />
      </div>
      <div className="times">
        <span ref={timeLabel}>0:00</span>
        {loop && loopOn && <span>Loop {formatTime(loop.start)} – {formatTime(loop.end)}</span>}
        <span>{formatTime(duration)}</span>
      </div>
    </section>
  )
}
