import { useMemo, useState } from 'react'
import { Download, Loader2, Package } from 'lucide-react'
import { api, download, waitForJob } from '../api/client'
import type { Analysis, ExportRequest, Job, MixerChannel, Song, StemId } from '../api/types'
import { bandCovers, STEM_INFO } from '../stems'
import { Modal } from './Modal'
import { useToast } from './Toasts'

type Tab = 'multitrack' | 'stems' | 'mix'

interface Props {
  song: Song
  analysis: Analysis | null
  mixer: Partial<Record<StemId, MixerChannel>>
  rate: number
  semitones: number
  keyLabel: string | null
  band: StemId[]
  onClose: () => void
}

function formatSize(bytes: number): string {
  if (bytes > 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${Math.round(bytes / 1024)} KB`
}

export function ExportDialog({ song, analysis, mixer, rate, semitones, keyLabel, band, onClose }: Props) {
  const toast = useToast()
  const stems = song.stems.map((s) => s.id)
  const missing = useMemo(() => stems.filter((s) => !bandCovers(s, band)), [stems, band])
  const [tab, setTab] = useState<Tab>('multitrack')
  const [selected, setSelected] = useState<StemId[]>(band.length && missing.length ? missing : stems)
  const [format, setFormat] = useState<'wav' | 'mp3' | 'flac'>('wav')
  const [click, setClick] = useState(Boolean(analysis?.beats.length))
  const changed = Math.abs(rate - 1) > 0.001 || semitones !== 0
  const [apply, setApply] = useState(changed)
  const [job, setJob] = useState<Job | null>(null)
  const [busy, setBusy] = useState(false)

  const toggle = (id: StemId) =>
    setSelected((cur) => (cur.includes(id) ? cur.filter((s) => s !== id) : [...cur, id]))

  const changesText = [
    Math.abs(rate - 1) > 0.001 ? `velocidad ${Math.round(rate * 100)}%` : null,
    semitones ? `tono ${semitones > 0 ? '+' : ''}${semitones}${keyLabel ? ` (${keyLabel})` : ''}` : null,
  ].filter(Boolean).join(' y ')

  const run = async () => {
    const body: ExportRequest = {
      type: tab,
      stems: tab === 'mix' ? stems : selected,
      format: tab === 'multitrack' ? 'wav' : format,
      click: tab !== 'stems' && click,
      tempo: apply ? rate : 1,
      semitones: apply ? semitones : 0,
      mixer: tab === 'mix' ? mixer : undefined,
    }
    if (tab !== 'mix' && !selected.length) {
      toast.show('Elige al menos una pista', 'err')
      return
    }
    setBusy(true)
    try {
      const created = await api.createExport(song.id, body)
      setJob(created)
      const done = await waitForJob(created.id, setJob)
      if (done.status === 'done' && done.downloadUrl) {
        download(done.downloadUrl)
        toast.show('Exportación lista: descargando…', 'ok')
      } else if (done.status === 'error') {
        toast.show(done.error || 'No se pudo exportar', 'err')
      }
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  const stemPicker = (
    <>
      <div className="row wrap" style={{ gap: 6 }}>
        <span className="small muted grow">Pistas a incluir</span>
        <button className="btn small ghost" onClick={() => setSelected(stems)}>Todas</button>
        {band.length > 0 && missing.length > 0 && (
          <button className="btn small ghost" onClick={() => setSelected(missing)} title="Las que tu banda no toca">
            Lo que le falta a mi banda
          </button>
        )}
        {stems.includes('vocals') && (
          <button className="btn small ghost" onClick={() => setSelected(stems.filter((s) => s !== 'vocals'))}>Sin voz</button>
        )}
      </div>
      <div className="check-grid">
        {stems.map((id) => (
          <label key={id} className={`check${selected.includes(id) ? ' on' : ''}`}>
            <input type="checkbox" checked={selected.includes(id)} onChange={() => toggle(id)} />
            <span className="swatch" style={{ width: 9, height: 9, borderRadius: 3, background: STEM_INFO[id].color }} />
            {STEM_INFO[id].name}
          </label>
        ))}
      </div>
    </>
  )

  return (
    <Modal
      title="Exportar"
      onClose={onClose}
      width={660}
      footer={
        <>
          {job?.status === 'done' && job.downloadUrl && (
            <button className="btn ghost" onClick={() => download(job.downloadUrl!)}><Download size={16} />Descargar de nuevo</button>
          )}
          <button className="btn" onClick={onClose}>Cerrar</button>
          <button className="btn primary" disabled={busy} onClick={() => void run()}>
            {busy ? <Loader2 size={16} className="spin" /> : tab === 'multitrack' ? <Package size={16} /> : <Download size={16} />}
            {tab === 'multitrack' ? 'Exportar para Multitrack' : tab === 'stems' ? 'Descargar pistas (.zip)' : 'Descargar mezcla'}
          </button>
        </>
      }
    >
      <div className="segmented" role="tablist">
        <button className={tab === 'multitrack' ? 'active' : ''} onClick={() => setTab('multitrack')}>Multitrack (AI Tracks)</button>
        <button className={tab === 'stems' ? 'active' : ''} onClick={() => setTab('stems')}>Pistas sueltas</button>
        <button className={tab === 'mix' ? 'active' : ''} onClick={() => setTab('mix')}>Mezcla actual</button>
      </div>

      {tab === 'multitrack' && (
        <>
          <p className="small muted" style={{ margin: 0 }}>
            Genera un <b>.zip</b> listo para <b>Multitrack Alabanza</b>: una pista WAV por instrumento, el click y un
            archivo con tempo, tonalidad, acordes y las partes de la canción (marcadores). En la app de Multitrack usa
            <i> Cargar canción (.zip)</i>.
          </p>
          {stemPicker}
          <label className="toggle">
            <input type="checkbox" checked={click} disabled={!analysis?.beats.length} onChange={(e) => setClick(e.target.checked)} />
            <span className="track" />
            <span>Incluir pista de <b>Click</b> (metrónomo sobre el pulso de la canción)</span>
          </label>
        </>
      )}

      {tab === 'stems' && (
        <>
          <div className="row">
            <span className="small muted grow">Formato</span>
            <div className="segmented">
              {(['wav', 'mp3', 'flac'] as const).map((f) => (
                <button key={f} className={format === f ? 'active' : ''} onClick={() => setFormat(f)}>{f.toUpperCase()}</button>
              ))}
            </div>
          </div>
          {stemPicker}
          <div className="small muted">
            O descarga una sola:{' '}
            {song.stems.map((s, i) => (
              <span key={s.id}>
                {i > 0 && ' · '}
                <a href={`/api/songs/${song.id}/download/${s.id}.${format}`} download>{s.name}</a>
              </span>
            ))}
          </div>
        </>
      )}

      {tab === 'mix' && (
        <>
          <p className="small muted" style={{ margin: 0 }}>
            Un solo archivo con la mezcla que estás escuchando (volumen, paneo, mute y solo de cada pista).
          </p>
          <div className="row">
            <span className="small muted grow">Formato</span>
            <div className="segmented">
              {(['wav', 'mp3'] as const).map((f) => (
                <button key={f} className={format === f ? 'active' : ''} onClick={() => setFormat(f)}>{f.toUpperCase()}</button>
              ))}
            </div>
          </div>
          <label className="toggle">
            <input type="checkbox" checked={click} disabled={!analysis?.beats.length} onChange={(e) => setClick(e.target.checked)} />
            <span className="track" />
            <span>Agregar el click a la mezcla</span>
          </label>
        </>
      )}

      {changed && (
        <label className="toggle">
          <input type="checkbox" checked={apply} onChange={(e) => setApply(e.target.checked)} />
          <span className="track" />
          <span>Aplicar los cambios actuales: <b>{changesText}</b></span>
        </label>
      )}

      {job && (
        <div className="stack" style={{ gap: 6 }}>
          <div className="row small">
            <span className="grow muted">
              {job.status === 'done' ? `Listo · ${job.result ? `${job.result.name} (${formatSize(job.result.size)})` : ''}` : job.status === 'error' ? job.error : job.message}
            </span>
            {job.status !== 'done' && job.status !== 'error' && <span className="faint">{Math.round(job.progress * 100)}%</span>}
          </div>
          {job.status !== 'done' && job.status !== 'error' && (
            <div className="progress"><div style={{ width: `${Math.max(4, job.progress * 100)}%` }} /></div>
          )}
        </div>
      )}
    </Modal>
  )
}
