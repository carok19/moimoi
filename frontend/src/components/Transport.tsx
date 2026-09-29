import { useRef, useState } from 'react'
import {
  ChevronDown, ChevronUp, Megaphone, Minus, Pause, Play, Plus, Redo2, Repeat, RotateCcw, SkipBack, SlidersHorizontal, Timer,
  Undo2, Volume2,
} from 'lucide-react'
import type { StemPlayer } from '../audio/StemPlayer'
import { useFrame } from '../hooks/useFrame'
import { formatTime } from '../music/theory'
import { Menu } from './Menu'

interface Props {
  player: StemPlayer
  playing: boolean
  rate: number
  semitones: number
  keyLabel: string | null
  originalKeyLabel: string | null
  bpm: number | null
  loopOn: boolean
  metronome: boolean
  guide: boolean
  metronomeVolume: number
  countIn: number
  masterVolume: number
  tuningCents: number
  tuneTo440: boolean
  onToggle: () => void
  onSkip: (seconds: number) => void
  onRestart: () => void
  onRate: (rate: number) => void
  onSemitones: (semitones: number) => void
  onLoop: () => void
  onMetronome: () => void
  onGuide: () => void
  onMetronomeVolume: (v: number) => void
  onCountIn: (bars: number) => void
  onMasterVolume: (v: number) => void
  onTune440: (on: boolean) => void
}

export function Transport(p: Props) {
  const time = useRef<HTMLSpanElement>(null)
  const [expanded, setExpanded] = useState(false)
  useFrame(() => {
    if (time.current) {
      const counting = p.player.countingIn
      time.current.textContent = counting ? 'Cuenta…' : formatTime(p.player.position)
    }
  })
  const pct = Math.round(p.rate * 100)
  return (
    <div className={`transport${expanded ? ' expanded' : ''}`} role="region" aria-label="Controles de reproducción">
      <div className="inner">
        <div className="left">
          <div className="tools">
            <button className={`tool${p.loopOn ? ' on' : ''}`} onClick={p.onLoop} title="Repetir (L)">
              <Repeat size={18} />Loop
            </button>
            <button className={`tool${p.countIn > 0 ? ' on' : ''}`} onClick={() => p.onCountIn(p.countIn > 0 ? 0 : 1)}
              title="Cuenta antes de empezar (C)">
              <Timer size={18} />Cuenta{p.countIn > 1 ? ` ×${p.countIn}` : ''}
            </button>
            <button className={`tool${p.metronome ? ' on' : ''}`} onClick={p.onMetronome} title="Click (M)" aria-pressed={p.metronome}>
              <MetronomeIcon />Click
            </button>
            <button className={`tool${p.guide ? ' on' : ''}`} onClick={p.onGuide} title="Guía: anuncia las partes (G)" aria-pressed={p.guide}>
              <Megaphone size={18} />Guía
            </button>
            <Menu
              align="left"
              button={(open) => (
                <button className="tool" onClick={open} title="Volumen del click y compases de cuenta"><ChevronDown size={18} />Opciones</button>
              )}
            >
              {() => (
                <div style={{ padding: '8px 10px', display: 'flex', flexDirection: 'column', gap: 12, minWidth: 230 }}>
                  <label className="small">Volumen del click
                    <input type="range" min={0} max={150} value={Math.round(p.metronomeVolume * 100)}
                      onChange={(e) => p.onMetronomeVolume(Number(e.target.value) / 100)} />
                  </label>
                  <div className="small">Compases de cuenta
                    <div className="segmented" style={{ marginTop: 6 }}>
                      {[0, 1, 2].map((n) => (
                        <button key={n} className={p.countIn === n ? 'active' : ''} onClick={() => p.onCountIn(n)}>
                          {n === 0 ? 'Sin cuenta' : n}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              )}
            </Menu>
          </div>
        </div>

        <div className="center">
          <div className="buttons">
            <button className="btn ghost icon restart" onClick={p.onRestart} title="Al principio (Inicio)" aria-label="Al principio"><SkipBack size={18} /></button>
            <button className="btn ghost icon" onClick={() => p.onSkip(-5)} title="5 s atrás (←)"><Undo2 size={18} /></button>
            <button className="play" onClick={p.onToggle} aria-label={p.playing ? 'Pausa' : 'Reproducir'} title="Reproducir / pausa (espacio)">
              {p.playing ? <Pause size={26} fill="#111" /> : <Play size={26} fill="#111" style={{ marginLeft: 3 }} />}
            </button>
            <button className="btn ghost icon" onClick={() => p.onSkip(5)} title="5 s adelante (→)"><Redo2 size={18} /></button>
            <div className="time">
              <span ref={time}>0:00</span> / {formatTime(p.player.duration)}
            </div>
            <button className="btn icon more-toggle" onClick={() => setExpanded((v) => !v)}
              aria-expanded={expanded} aria-label="Velocidad, tono, loop, click y guía">
              {expanded ? <ChevronDown size={18} /> : <><SlidersHorizontal size={16} /><ChevronUp size={12} /></>}
            </button>
          </div>
        </div>

        <div className="right">
          <div className="stepper" style={{ minWidth: 200 }}>
            <div className="label">Velocidad <b>{pct}%{p.bpm ? ` · ${Math.round(p.bpm * p.rate)} BPM` : ''}</b></div>
            <div className="ctrl">
              <button onClick={() => p.onRate(Math.max(0.5, Math.round((p.rate - 0.05) * 100) / 100))} aria-label="Más lento ([)"><Minus size={14} /></button>
              <input type="range" min={50} max={150} step={1} value={pct} onChange={(e) => p.onRate(Number(e.target.value) / 100)}
                onDoubleClick={() => p.onRate(1)} aria-label="Velocidad" />
              <button onClick={() => p.onRate(Math.min(1.5, Math.round((p.rate + 0.05) * 100) / 100))} aria-label="Más rápido (])"><Plus size={14} /></button>
            </div>
          </div>
          <div className="stepper" style={{ minWidth: 170 }}>
            <div className="label">Tono
              <b>{p.semitones === 0 ? 'Original' : `${p.semitones > 0 ? '+' : ''}${p.semitones}`}{p.keyLabel ? ` · ${p.keyLabel}` : ''}</b>
            </div>
            <div className="ctrl">
              <button onClick={() => p.onSemitones(Math.max(-12, p.semitones - 1))} aria-label="Bajar medio tono (-)"><Minus size={14} /></button>
              <button style={{ width: 'auto', padding: '0 8px', fontSize: 12 }} onClick={() => p.onSemitones(0)}
                disabled={p.semitones === 0} title={p.originalKeyLabel ? `Tonalidad original: ${p.originalKeyLabel}` : 'Tono original'}>
                <RotateCcw size={12} />
              </button>
              <button onClick={() => p.onSemitones(Math.min(12, p.semitones + 1))} aria-label="Subir medio tono (+)"><Plus size={14} /></button>
              {Math.abs(p.tuningCents) >= 5 && (
                <button style={{ width: 'auto', padding: '0 8px', fontSize: 11.5 }} className={p.tuneTo440 ? 'on' : ''}
                  onClick={() => p.onTune440(!p.tuneTo440)}
                  title={`La grabación está ${p.tuningCents > 0 ? '+' : ''}${p.tuningCents} cents respecto de La 440: corregir para tocar con instrumentos afinados`}>
                  La 440
                </button>
              )}
            </div>
          </div>
          <div className="stepper" style={{ minWidth: 110 }}>
            <div className="label">Volumen <b>{Math.round(p.masterVolume * 100)}%</b></div>
            <div className="ctrl">
              <Volume2 size={16} color="var(--text-3)" />
              <input type="range" min={0} max={150} value={Math.round(p.masterVolume * 100)}
                onChange={(e) => p.onMasterVolume(Number(e.target.value) / 100)} aria-label="Volumen general" />
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function MetronomeIcon() {
  return (
    <svg width={18} height={18} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M9 3h6l4 18H5L9 3z" />
      <path d="M12 16l5-9" />
      <path d="M7.5 14h9" />
    </svg>
  )
}
