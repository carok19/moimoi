import { useEffect, useRef, useState } from 'react'
import { RotateCcw, Users } from 'lucide-react'
import type { InstrumentInfo, MixerChannel, StemId, StemInfo } from '../api/types'
import { decodePeaks, drawWave } from '../audio/peaks'
import { DEFAULT_CHANNEL, type StemPlayer } from '../audio/StemPlayer'
import { useFrame } from '../hooks/useFrame'
import { bandCovers, STEM_INFO } from '../stems'

const LEVEL_TEXT: Record<string, string> = {
  alta: 'Muy presente',
  media: 'Presente',
  baja: 'Poco presente',
  ausente: 'Casi no suena',
}

interface Props {
  player: StemPlayer
  stems: StemInfo[]
  peaks: Record<string, string>
  instruments: Partial<Record<StemId, InstrumentInfo>>
  mixer: Partial<Record<StemId, MixerChannel>>
  duration: number
  band: StemId[]
  onChange: (id: StemId, patch: Partial<MixerChannel>) => void
  onReplace: (mixer: Partial<Record<StemId, MixerChannel>>) => void
}

export function Mixer({ player, stems, peaks, instruments, mixer, duration, band, onChange, onReplace }: Props) {
  const meters = useRef<Record<string, HTMLDivElement | null>>({})
  const lines = useRef<Record<string, HTMLDivElement | null>>({})
  const waves = useRef<Record<string, HTMLDivElement | null>>({})
  const anySolo = stems.some((s) => mixer[s.id]?.solo)

  useFrame(() => {
    const levels = player.levels()
    const p = duration > 0 ? player.position / duration : 0
    for (const s of stems) {
      const meter = meters.current[s.id]
      if (meter) meter.style.width = `${(levels[s.id] ?? 0) * 100}%`
      const line = lines.current[s.id]
      const wave = waves.current[s.id]
      if (line && wave) line.style.transform = `translateX(${p * wave.clientWidth}px)`
    }
  })

  const bandActive = band.length > 0 && stems.every((s) => {
    const covered = bandCovers(s.id, band)
    return (mixer[s.id]?.mute ?? false) === covered && !mixer[s.id]?.solo
  })

  const playWithBand = () => {
    const next: Partial<Record<StemId, MixerChannel>> = {}
    for (const s of stems) next[s.id] = { ...DEFAULT_CHANNEL, ...(mixer[s.id] ?? {}), solo: false, mute: bandCovers(s.id, band) }
    onReplace(next)
  }
  const reset = () => {
    const next: Partial<Record<StemId, MixerChannel>> = {}
    for (const s of stems) next[s.id] = { ...DEFAULT_CHANNEL }
    onReplace(next)
  }

  return (
    <section className="card mixer" aria-label="Mezclador">
      <div className="mixer-head">
        <h2 className="grow">Pistas</h2>
        {band.length > 0 ? (
          <button className={`btn small${bandActive ? ' on' : ''}`} onClick={bandActive ? reset : playWithBand}
            title="Silencia lo que toca tu banda y deja sonar lo que les falta">
            <Users size={14} />{bandActive ? 'Sonando lo que le falta a tu banda' : 'Tocar con mi banda'}
          </button>
        ) : (
          <a className="btn small ghost" href="#/ajustes" title="Elige qué instrumentos tiene tu banda">
            <Users size={14} />Configurar mi banda
          </a>
        )}
        <button className="btn small ghost" onClick={reset} title="Todo al 100 %, sin mute ni solo"><RotateCcw size={14} />Restablecer</button>
      </div>
      {stems.map((stem) => {
        const state = { ...DEFAULT_CHANNEL, ...(mixer[stem.id] ?? {}) }
        const audible = anySolo ? state.solo : !state.mute
        const info = instruments[stem.id]
        return (
          <StemRow
            key={stem.id}
            stem={stem}
            state={state}
            audible={audible}
            info={info}
            peaks={peaks[stem.id]}
            duration={duration}
            onChange={(patch) => onChange(stem.id, patch)}
            meterRef={(el) => { meters.current[stem.id] = el }}
            lineRef={(el) => { lines.current[stem.id] = el }}
            waveRef={(el) => { waves.current[stem.id] = el }}
          />
        )
      })}
    </section>
  )
}

interface RowProps {
  stem: StemInfo
  state: MixerChannel
  audible: boolean
  info: InstrumentInfo | undefined
  peaks: string | undefined
  duration: number
  onChange: (patch: Partial<MixerChannel>) => void
  meterRef: (el: HTMLDivElement | null) => void
  lineRef: (el: HTMLDivElement | null) => void
  waveRef: (el: HTMLDivElement | null) => void
}

function StemRow({ stem, state, audible, info, peaks, duration, onChange, meterRef, lineRef, waveRef }: RowProps) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const box = useRef<HTMLDivElement | null>(null)
  const [width, setWidth] = useState(0)
  const color = STEM_INFO[stem.id]?.color ?? stem.color

  useEffect(() => {
    const el = box.current
    if (!el) return
    const observer = new ResizeObserver(() => setWidth(el.clientWidth))
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    if (canvas.current && width) {
      drawWave(canvas.current, decodePeaks(peaks), { color, gain: 1.2, active: info?.active, duration })
    }
  }, [peaks, width, color, info, duration])

  const presence = info ? `${LEVEL_TEXT[info.level] ?? ''} · ${Math.round(info.presence * 100)}% del tiempo` : ''

  return (
    <div className={`stem-row${audible ? '' : ' dim'}`}>
      <div className="stem-name">
        <span className="color" style={{ background: color }} />
        <div style={{ minWidth: 0 }}>
          <b className="ellipsis">{stem.name}</b>
          <small className="ellipsis" title={presence}>{presence}</small>
        </div>
      </div>
      <div className="ms">
        <button className={`mute${state.mute ? ' on' : ''}`} onClick={() => onChange({ mute: !state.mute })}
          title="Silenciar (mute)" aria-pressed={state.mute}>M</button>
        <button className={`solo${state.solo ? ' on' : ''}`} onClick={() => onChange({ solo: !state.solo })}
          title="Escuchar solo esta pista" aria-pressed={state.solo}>S</button>
      </div>
      <div className="stem-wave" ref={(el) => { box.current = el; waveRef(el) }}>
        <canvas ref={canvas} />
        <div className="playline" ref={lineRef} />
      </div>
      <div className="stem-controls">
        <div className="vol">
          <input type="range" min={0} max={150} step={1} value={Math.round(state.volume * 100)}
            onChange={(e) => onChange({ volume: Number(e.target.value) / 100 })}
            onDoubleClick={() => onChange({ volume: 1 })}
            aria-label={`Volumen de ${stem.name}`}
            style={{ ['--track' as string]: `linear-gradient(90deg, ${color} ${(state.volume / 1.5) * 100}%, var(--panel-3) 0)` }} />
          <span>{Math.round(state.volume * 100)}%</span>
        </div>
        <div className="meter"><div ref={meterRef} /></div>
        <div className="pan-row">
          <span>I</span>
          <input type="range" min={-100} max={100} step={1} value={Math.round(state.pan * 100)}
            onChange={(e) => onChange({ pan: Number(e.target.value) / 100 })}
            onDoubleClick={() => onChange({ pan: 0 })} aria-label={`Paneo de ${stem.name}`} />
          <span>D</span>
        </div>
      </div>
    </div>
  )
}
