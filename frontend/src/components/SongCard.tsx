import { MoreHorizontal, Play, RotateCcw, Trash2, X } from 'lucide-react'
import { api } from '../api/client'
import type { PresetId, Quality, Song } from '../api/types'
import { useApp } from '../context'
import { navigate } from '../hooks/useHashRoute'
import { formatTime, keyName } from '../music/theory'
import { STEM_INFO } from '../stems'
import { Menu } from './Menu'
import { useToast } from './Toasts'

const STATUS_TEXT: Record<string, string> = {
  queued: 'En cola',
  downloading: 'Descargando',
  separating: 'Separando pistas',
  analyzing: 'Analizando',
}

function initials(title: string): string {
  return title.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]?.toUpperCase()).join('') || '♪'
}

export function SongCard({ song, onChange }: { song: Song; onChange: () => void }) {
  const { settings } = useApp()
  const toast = useToast()
  const processing = song.status in STATUS_TEXT
  const ready = song.status === 'ready'

  const act = async (fn: () => Promise<unknown>, message?: string) => {
    try {
      await fn()
      if (message) toast.show(message, 'ok')
      onChange()
    } catch (err) {
      toast.error(err)
    }
  }

  const reprocess = (preset: PresetId, quality: Quality) =>
    act(() => api.retrySong(song.id, preset, quality), 'Volviendo a separar…')

  return (
    <article
      className={`card song-card${ready ? ' clickable' : ''}`}
      onClick={() => ready && navigate(`/cancion/${song.id}`)}
      aria-label={song.title}
    >
      <Menu
        button={(open) => (
          <button className="btn icon small menu-btn" onClick={open} aria-label="Opciones"><MoreHorizontal size={16} /></button>
        )}
      >
        {(close) => (
          <>
            {ready && (
              <button onClick={() => { close(); navigate(`/cancion/${song.id}`) }}><Play size={15} />Abrir</button>
            )}
            <div className="label">Volver a separar</div>
            {(['2stems', '4stems', '6stems'] as PresetId[]).map((p) => (
              <button key={p} disabled={processing} onClick={() => { close(); void reprocess(p, song.quality) }}>
                <RotateCcw size={15} />{p[0]} pistas{song.preset === p ? ' (actual)' : ''}
              </button>
            ))}
            {song.quality === 'normal' && (
              <button disabled={processing} onClick={() => { close(); void reprocess(song.preset, 'alta') }}>
                <RotateCcw size={15} />Misma separación en calidad alta
              </button>
            )}
            <div className="sep" />
            <button onClick={() => {
              close()
              if (window.confirm(`¿Borrar "${song.title}" y sus pistas?`)) void act(() => api.deleteSong(song.id), 'Canción borrada')
            }}><Trash2 size={15} color="#ff9b9b" />Borrar</button>
          </>
        )}
      </Menu>
      <div className="cover">
        {song.thumbnailUrl ? <img src={song.thumbnailUrl} alt="" loading="lazy" /> : initials(song.title)}
        <div className="shade" />
        {song.duration ? <span className="duration">{formatTime(song.duration)}</span> : null}
      </div>
      <div className="info">
        <div>
          <div style={{ fontWeight: 750 }} className="ellipsis" title={song.title}>{song.title}</div>
          <div className="small muted ellipsis">{song.artist || (song.sourceType === 'url' ? 'Desde un link' : 'Archivo subido')}</div>
        </div>
        {processing && (
          <div className="stack" style={{ gap: 7 }}>
            <div className="row small">
              <span className="grow ellipsis muted">{song.stage || STATUS_TEXT[song.status]}</span>
              <span className="faint">{Math.round(song.progress * 100)}%</span>
            </div>
            <div className={`progress${song.status === 'queued' ? ' indeterminate' : ''}`}>
              <div style={{ width: `${Math.max(3, song.progress * 100)}%` }} />
            </div>
            <div className="row">
              <span className="tiny faint grow">{song.presetName}{song.quality === 'alta' ? ' · calidad alta' : ''}</span>
              <button className="btn ghost small" onClick={(e) => { e.stopPropagation(); void act(() => api.cancelSong(song.id)) }}>
                <X size={14} />Cancelar
              </button>
            </div>
          </div>
        )}
        {(song.status === 'error' || song.status === 'cancelled') && (
          <div className="stack" style={{ gap: 8 }}>
            <div className={`small ${song.status === 'error' ? '' : 'muted'}`} style={song.status === 'error' ? { color: '#ffa0a0' } : undefined}>
              {song.status === 'error' ? song.error || 'Ocurrió un error' : 'Cancelado'}
            </div>
            <button className="btn small" onClick={(e) => { e.stopPropagation(); void act(() => api.retrySong(song.id), 'Reintentando…') }}>
              <RotateCcw size={14} />Reintentar
            </button>
          </div>
        )}
        {ready && (
          <div className="row wrap" style={{ gap: 6 }}>
            {song.summary?.key && (
              <span className="chip accent">{keyName(song.summary.tonic, song.summary.mode, 0, settings.notation)}</span>
            )}
            {song.summary?.bpm ? <span className="chip">{Math.round(song.summary.bpm)} BPM</span> : null}
            <span className="chip">
              {song.stems.slice(0, 6).map((s) => (
                <span key={s.id} className="swatch" style={{ background: STEM_INFO[s.id]?.color ?? s.color }} title={s.name} />
              ))}
              {song.stems.length} pistas
            </span>
          </div>
        )}
      </div>
    </article>
  )
}
