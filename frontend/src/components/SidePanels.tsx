import { useEffect, useRef, useState } from 'react'
import { Loader2, Mic2, Repeat, RotateCcw } from 'lucide-react'
import { api } from '../api/client'
import type { Lyrics, Section, Song } from '../api/types'
import type { LoopRange, StemPlayer } from '../audio/StemPlayer'
import { useApp } from '../context'
import { useSampled } from '../hooks/useFrame'
import { formatTime } from '../music/theory'
import { sectionColor } from '../stems'
import { useToast } from './Toasts'

export function SectionsPanel({ player, sections, edited, loop, loopOn, onSeek, onLoop, onRename, onReset }: {
  player: StemPlayer
  sections: Section[]
  edited: boolean
  loop: LoopRange | null
  loopOn: boolean
  onSeek: (t: number) => void
  onLoop: (range: LoopRange) => void
  onRename: (index: number, label: string) => void
  onReset: () => void
}) {
  const current = useSampled(() => {
    const t = player.position
    return sections.findIndex((s) => t >= s.start && t < s.end)
  }, 150)
  return (
    <section className="card side-panel" aria-label="Partes de la canción">
      <div className="row">
        <h3 className="grow">Partes de la canción</h3>
        {edited && <button className="btn ghost small" onClick={onReset} title="Volver a los nombres detectados"><RotateCcw size={13} /></button>}
      </div>
      <div className="tiny faint" style={{ marginTop: 4 }}>
        Clic en el nombre para cambiarlo. Se exportan como marcadores y la voz guía los anuncia (por ejemplo
        "Coro (última vez)" o "Puente sube tono").
      </div>
      <div className="section-list">
        {sections.map((s, i) => {
          const looping = !!(loop && loopOn && Math.abs(loop.start - s.start) < 0.05 && Math.abs(loop.end - s.end) < 0.05)
          return (
            <div key={`${i}-${s.start}`} className={`section-item${i === current ? ' current' : ''}`}>
              <span className="sw" style={{ background: sectionColor(s.label, s.group) }} />
              <SectionName value={s.label} onSave={(label) => onRename(i, label)} />
              <button className="btn ghost small" style={{ padding: '0 6px' }} onClick={() => onSeek(s.start)} title="Ir">
                <span className="t">{formatTime(s.start)}</span>
              </button>
              <button className={`btn icon small${looping ? ' on' : ' ghost'}`} onClick={() => onLoop({ start: s.start, end: s.end })}
                title="Repetir esta parte"><Repeat size={14} /></button>
            </div>
          )
        })}
      </div>
    </section>
  )
}

function SectionName({ value, onSave }: { value: string; onSave: (v: string) => void }) {
  const [text, setText] = useState(value)
  useEffect(() => setText(value), [value])
  return (
    <input value={text} onChange={(e) => setText(e.target.value)} aria-label="Nombre de la parte"
      onBlur={() => text.trim() && text !== value && onSave(text.trim())}
      onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }} />
  )
}

export function LyricsPanel({ song, player, onSeek, onRefreshSong }: {
  song: Song
  player: StemPlayer
  onSeek: (t: number) => void
  onRefreshSong: () => void
}) {
  const { health } = useApp()
  const toast = useToast()
  const [lyrics, setLyrics] = useState<Lyrics | null>(null)
  const [requesting, setRequesting] = useState(false)
  const list = useRef<HTMLDivElement>(null)
  const status = song.lyricsStatus
  const running = status === 'queued' || status === 'running'
  const hasVocals = song.stems.some((s) => s.id === 'vocals')

  useEffect(() => {
    if (status === 'ready') api.lyrics(song.id).then(setLyrics).catch(() => setLyrics(null))
  }, [song.id, status])

  useEffect(() => {
    if (!running) return
    const timer = window.setInterval(onRefreshSong, 2000)
    return () => window.clearInterval(timer)
  }, [running, onRefreshSong])

  const t = useSampled(() => Math.round(player.position * 10) / 10, 100)
  const lines = lyrics?.lines ?? []
  const currentLine = lines.findIndex((l) => t >= l.start - 0.15 && t < l.end + 0.4)

  useEffect(() => {
    const el = list.current?.querySelector('p.current') as HTMLElement | null
    if (el && list.current) {
      const box = list.current
      const target = el.offsetTop - box.clientHeight / 2 + el.clientHeight / 2
      box.scrollTo({ top: target, behavior: 'smooth' })
    }
  }, [currentLine])

  const request = async () => {
    setRequesting(true)
    try {
      await api.requestLyrics(song.id)
      toast.show('Transcribiendo la letra (puede tardar unos minutos)…')
      onRefreshSong()
    } catch (err) {
      toast.error(err)
    } finally {
      setRequesting(false)
    }
  }

  return (
    <section className="card side-panel" aria-label="Letra">
      <div className="row">
        <h3 className="grow">Letra</h3>
        {lyrics && <span className="tiny faint">{lyrics.language?.toUpperCase()}</span>}
      </div>
      {!hasVocals && <div className="small muted" style={{ marginTop: 8 }}>Esta canción no tiene pista de voz.</div>}
      {hasVocals && !lyrics && !running && (
        health?.features.lyrics ? (
          <div className="stack" style={{ marginTop: 10 }}>
            <div className="small muted">La IA escucha la pista de voz y escribe la letra sincronizada con la canción.</div>
            <button className="btn small" disabled={requesting} onClick={() => void request()}>
              {requesting ? <Loader2 size={14} className="spin" /> : <Mic2 size={14} />}Transcribir letra
            </button>
            {status === 'error' && <div className="tiny" style={{ color: '#ffa0a0' }}>La última transcripción falló. Prueba de nuevo.</div>}
          </div>
        ) : (
          <div className="small muted" style={{ marginTop: 8 }}>
            {health?.standalone
              ? 'La letra automática todavía no está en la app del celular.'
              : <>Para transcribir letras instala el complemento: <code>pip install faster-whisper</code> y reinicia MoiMoi.</>}
          </div>
        )
      )}
      {running && (
        <div className="stack" style={{ marginTop: 10 }}>
          <div className="small muted">Transcribiendo…</div>
          <div className="progress indeterminate"><div /></div>
        </div>
      )}
      {lyrics && (
        <div className="lyrics" ref={list}>
          {lines.map((line, i) => (
            <p key={i} className={i === currentLine ? 'current' : i < currentLine ? 'past' : ''} onClick={() => onSeek(line.start)}>
              {i === currentLine && line.words.length
                ? line.words.map((w, j) => <span key={j} className={w.start <= t ? 'sung' : ''}>{w.text}</span>)
                : line.text}
            </p>
          ))}
          {!lines.length && <div className="small muted">No se reconoció letra en la voz.</div>}
        </div>
      )}
    </section>
  )
}
