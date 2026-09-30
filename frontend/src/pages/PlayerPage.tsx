import { useCallback, useEffect, useMemo, useReducer, useState } from 'react'
import { ArrowLeft, ExternalLink, Loader2, MoreHorizontal, Package, RefreshCw, Trash2 } from 'lucide-react'
import { api } from '../api/client'
import { apiUrl } from '../api/base'
import { ANALYSIS_VERSION, type Analysis, type MixerChannel, type Peaks, type Section, type Song, type StemId,
  type TempoEdit } from '../api/types'
import { decodePeaks } from '../audio/peaks'
import { playbackQuality, StemPlayer, type GuideVoice, type LoopRange } from '../audio/StemPlayer'
import { ChordPanel, ChordStrip } from '../components/ChordPanel'
import { EditableText } from '../components/EditableText'
import { ExportDialog } from '../components/ExportDialog'
import { Menu } from '../components/Menu'
import { Mixer } from '../components/Mixer'
import { LyricsPanel, SectionsPanel } from '../components/SidePanels'
import { TempoPanel } from '../components/TempoPanel'
import { Timeline } from '../components/Timeline'
import { useToast } from '../components/Toasts'
import { Transport } from '../components/Transport'
import { useApp } from '../context'
import { useDebouncedEffect, useSampled } from '../hooks/useFrame'
import { navigate } from '../hooks/useHashRoute'
import { deriveGrid, EMPTY_GRID, partIndexAt, sectionIndexAt, tempoAt, type DerivedGrid } from '../music/grid'
import { formatTime, keyName, mod12, usesFlats } from '../music/theory'

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v))
const PROCESSING = new Set(['queued', 'downloading', 'separating', 'analyzing'])

export function PlayerPage({ songId }: { songId: string }) {
  const { settings, health } = useApp()
  // "Volver a analizar" (tempo, acordes, partes), si el programa que responde lo tiene.
  const canAnalyze = health?.features.analysis ?? true
  const toast = useToast()
  const [reloadKey, setReloadKey] = useState(0)
  const [song, setSong] = useState<Song | null>(null)
  const [analysis, setAnalysis] = useState<Analysis | null>(null)
  const [peaks, setPeaks] = useState<Peaks | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [player, setPlayer] = useState<StemPlayer | null>(null)
  const [loading, setLoading] = useState<{ fraction: number; message: string } | null>({ fraction: 0, message: 'Cargando…' })
  const [, rerender] = useReducer((x: number) => x + 1, 0)

  const [mixer, setMixer] = useState<Partial<Record<StemId, MixerChannel>>>({})
  const [rate, setRate] = useState(1)
  const [semitones, setSemitones] = useState(0)
  const [tune440, setTune440] = useState(false)
  const [loop, setLoop] = useState<LoopRange | null>(null)
  const [loopOn, setLoopOn] = useState(false)
  const [beatScale, setBeatScale] = useState<'double' | 'half' | null>(null)
  const [downbeatShift, setDownbeatShift] = useState(0)
  const [tempoEdits, setTempoEdits] = useState<TempoEdit[]>([])
  const [tempoOpen, setTempoOpen] = useState(false)
  const [sectionsEdited, setSectionsEdited] = useState<Section[] | null>(null)
  const [masterVolume, setMasterVolume] = useState(1)
  const [metronome, setMetronome] = useState(false)
  const [metronomeVolume, setMetronomeVolume] = useState(settings.metronomeVolume)
  const [countIn, setCountIn] = useState(0)
  const [guideOn, setGuideOn] = useState(false)
  const [guideVolume, setGuideVolume] = useState(0.9)
  const [guideInfo, setGuideInfo] = useState<{ voices: number; voiceSet: string | null; clickName: string | null } | null>(null)
  const [exportOpen, setExportOpen] = useState(false)

  // ---- carga -------------------------------------------------------------------------------
  useEffect(() => {
    let cancelled = false
    const abort = new AbortController()
    let created: StemPlayer | null = null
    setLoading({ fraction: 0, message: 'Cargando…' })
    setError(null)
    ;(async () => {
      try {
        const s = await api.song(songId)
        if (cancelled) return
        setSong(s)
        if (s.status !== 'ready') {
          setLoading(null)
          return
        }
        const [a, p] = await Promise.all([api.analysis(songId).catch(() => null), api.peaks(songId).catch(() => null)])
        if (cancelled) return
        setAnalysis(a)
        setPeaks(p)
        const st = s.settings ?? {}
        setMixer(st.mixer ?? {})
        setRate(typeof st.tempo === 'number' ? st.tempo : 1)
        setSemitones(typeof st.semitones === 'number' ? st.semitones : 0)
        setTune440(Boolean(st.tune440))
        setLoop(st.loop ?? null)
        setLoopOn(Boolean(st.loopOn && st.loop))
        setBeatScale(st.beatScale ?? null)
        setDownbeatShift(typeof st.downbeatShift === 'number' ? st.downbeatShift : 0)
        setTempoEdits(Array.isArray(st.tempoEdits) ? st.tempoEdits : [])
        setSectionsEdited(Array.isArray(st.sections) && st.sections.length ? st.sections : null)
        setMasterVolume(typeof st.masterVolume === 'number' ? st.masterVolume : 1)
        created = StemPlayer.create(playbackQuality(s.stems.length, s.duration ?? 0))
        await created.load(
          s.stems.map((x) => ({ id: x.id, name: x.name, url: apiUrl(x.url) })),
          st.mixer ?? {},
          (fraction, message) => !cancelled && setLoading({ fraction, message }),
          abort.signal,
        )
        if (cancelled) return
        setPlayer(created)
        setLoading(null)
      } catch (err) {
        if (cancelled) return
        setError(err instanceof Error ? err.message : String(err))
        setLoading(null)
      }
    })()
    return () => {
      cancelled = true
      abort.abort()
      created?.destroy()
      setPlayer(null)
    }
  }, [songId, reloadKey])

  // Canción todavía procesándose: seguir consultando hasta que esté lista.
  useEffect(() => {
    if (!song || !PROCESSING.has(song.status)) return
    const timer = window.setInterval(async () => {
      try {
        const s = await api.song(songId)
        setSong(s)
        if (s.status === 'ready') setReloadKey((k) => k + 1)
      } catch {
        // reintenta en el próximo ciclo
      }
    }, 1500)
    return () => window.clearInterval(timer)
  }, [song, songId])

  const refreshSong = useCallback(() => {
    api.song(songId).then(setSong).catch(() => {})
  }, [songId])

  // ---- derivados --------------------------------------------------------------------------------
  const grid = useMemo(
    () => analysis ? deriveGrid(analysis, { beatScale, downbeatShift, tempoEdits }) : EMPTY_GRID,
    [analysis, beatScale, downbeatShift, tempoEdits],
  )
  // El tempo de cada tramo tal como se detectó (para "volver a lo detectado").
  const detected = useMemo(
    () => analysis ? deriveGrid(analysis, { beatScale, downbeatShift }).parts.map((x) => x.bpm) : [],
    [analysis, beatScale, downbeatShift],
  )
  const editTempo = useCallback((start: number, patch: Partial<TempoEdit> | null) => {
    setTempoEdits((list) => {
      const others = list.filter((e) => Math.abs(e.start - start) >= 0.5)
      if (!patch) return others
      const current = list.find((e) => Math.abs(e.start - start) < 0.5) ?? { start }
      const next: TempoEdit = { ...current, ...patch, start }
      if (next.bpm === undefined) delete next.bpm
      if (!next.shift) delete next.shift
      return next.bpm === undefined && next.shift === undefined ? others : [...others, next].sort((a, b) => a.start - b.start)
    })
  }, [])
  const sections = sectionsEdited ?? analysis?.sections ?? []
  const key = analysis?.key
  const transposition = useMemo(() => ({
    semitones,
    flats: key ? usesFlats(mod12(key.tonic + semitones), key.mode) : false,
    notation: settings.notation,
  }), [semitones, key, settings.notation])
  const keyLabel = key ? keyName(key.tonic, key.mode, semitones, settings.notation) : null
  const originalKeyLabel = key ? keyName(key.tonic, key.mode, 0, settings.notation) : null
  const tuningCents = analysis?.tuning.cents ?? 0
  const mixPeaks = useMemo(() => decodePeaks(peaks?.peaks.mix), [peaks])

  // ---- sincronizar estado -> motor de audio ---------------------------------------------------
  useEffect(() => player?.subscribe(rerender), [player])
  useEffect(() => { player?.setRate(rate) }, [player, rate])
  useEffect(() => { player?.setPitch(semitones - (tune440 ? tuningCents / 100 : 0)) }, [player, semitones, tune440, tuningCents])
  useEffect(() => { player?.setChannels(mixer) }, [player, mixer])
  useEffect(() => { player?.setMasterVolume(masterVolume) }, [player, masterVolume])
  useEffect(() => { player?.setLoop(loopOn ? loop : null) }, [player, loop, loopOn])
  useEffect(() => { player?.setGrid(grid) }, [player, grid])
  useEffect(() => { player?.setMetronome(metronome) }, [player, metronome])
  useEffect(() => { player?.setMetronomeVolume(metronomeVolume) }, [player, metronomeVolume])
  useEffect(() => { player?.setCountIn(countIn) }, [player, countIn])
  useEffect(() => { setMetronomeVolume(settings.metronomeVolume) }, [settings.metronomeVolume])
  useEffect(() => { player?.setGuideOn(guideOn) }, [player, guideOn])
  useEffect(() => { player?.setGuideVolume(guideVolume) }, [player, guideVolume])

  // Click y Guía con el sonido de click y las voces incluidas (como en el paquete para Multitrack).
  // Se vuelve a armar si cambian las partes, el pulso o las opciones de la voz guía.
  const guideKey = JSON.stringify([sectionsEdited, beatScale, downbeatShift, tempoEdits, settings.exportClickSound,
    settings.guideNumbering, settings.guideKeyChanges])
  useEffect(() => {
    if (!player || !song || !analysis) return
    let cancelled = false
    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const guide = await api.songGuide(song.id)
          const cache = new Map<string, Promise<AudioBuffer | null>>()
          const load = (url?: string) => {
            if (!url) return Promise.resolve(null)
            let pending = cache.get(url)
            if (!pending) {
              pending = fetch(apiUrl(url)).then((r) => r.arrayBuffer()).then((data) => player.ctx.decodeAudioData(data))
                .catch(() => null)
              cache.set(url, pending)
            }
            return pending
          }
          const voices = await Promise.all(guide.placements.map(async (p) => ({ time: p.time, buffer: await load(guide.voices[p.cue]) })))
          const count = await Promise.all(Array.from({ length: 12 }, (_, i) => load(guide.voices[`n${i + 1}`])))
          const [accent, beat] = await Promise.all([load(guide.click.accent), load(guide.click.beat)])
          if (cancelled) return
          player.setGuide(voices.filter((v): v is GuideVoice => v.buffer !== null), count.map((b) => b ?? undefined))
          player.setClickSounds(accent, beat)
          setGuideInfo({ voices: voices.length, voiceSet: guide.voiceSet ?? null, clickName: guide.clickName ?? null })
        } catch {
          if (!cancelled) setGuideInfo({ voices: 0, voiceSet: null, clickName: null })
        }
      })()
    }, guideInfo ? 1200 : 0) // después de un cambio, espera a que se guarden las partes editadas
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [player, song?.id, analysis, guideKey])

  // ---- guardar ajustes de la canción -------------------------------------------------------------
  useDebouncedEffect(() => {
    if (!song || !player) return
    api.updateSong(song.id, {
      settings: {
        mixer, tempo: rate, semitones, tune440, loop, loopOn, beatScale, downbeatShift, masterVolume,
        sections: sectionsEdited ?? [],
        tempoEdits,
        // Con el tempo corregido, el click y la guía del paquete usan esta misma grilla.
        grid: grid.edited
          ? { beats: grid.beats.map((b) => Math.round(b * 1000) / 1000), downbeats: grid.downbeats.map((b) => Math.round(b * 1000) / 1000), bpm: grid.bpm }
          : null,
      },
    }).catch(() => {})
  }, [mixer, rate, semitones, tune440, loop, loopOn, beatScale, downbeatShift, masterVolume, sectionsEdited, tempoEdits], 800)

  // ---- acciones --------------------------------------------------------------------------------
  const seek = useCallback((t: number) => {
    if (!player) return
    if (loopOn && loop && (t < loop.start - 0.05 || t >= loop.end)) setLoopOn(false)
    player.seek(t)
  }, [player, loop, loopOn])

  const skip = useCallback((seconds: number) => {
    if (!player) return
    seek(clamp(player.position + seconds * player.currentRate, 0, player.duration))
  }, [player, seek])

  const setLoopRange = useCallback((range: LoopRange | null, enable: boolean) => {
    setLoop(range)
    setLoopOn(enable && !!range)
  }, [])

  const toggleLoop = useCallback(() => {
    if (!player) return
    if (loopOn) return setLoopOn(false)
    if (loop) return setLoopOn(true)
    const i = sectionIndexAt(sections, player.position)
    if (i >= 0) setLoopRange({ start: sections[i].start, end: sections[i].end }, true)
    else toast.show('Arrastra sobre la forma de onda para marcar un loop')
  }, [player, loop, loopOn, sections, setLoopRange, toast])

  useEffect(() => {
    if (!player) return
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null
      const tag = target?.tagName
      const inputType = (target as HTMLInputElement | null)?.type
      const typing = tag === 'TEXTAREA' || (tag === 'INPUT' && !['range', 'checkbox', 'button'].includes(inputType ?? ''))
        || target?.isContentEditable
      if (typing || e.ctrlKey || e.metaKey || e.altKey) return
      const onRange = tag === 'INPUT' && inputType === 'range'
      switch (e.key) {
        case ' ':
          e.preventDefault()
          player.toggle()
          break
        case 'ArrowLeft':
          if (onRange) return
          e.preventDefault()
          skip(e.shiftKey ? -1 : -5)
          break
        case 'ArrowRight':
          if (onRange) return
          e.preventDefault()
          skip(e.shiftKey ? 1 : 5)
          break
        case 'Home':
          e.preventDefault()
          seek(loopOn && loop ? loop.start : 0)
          break
        case 'l':
        case 'L':
          toggleLoop()
          break
        case 'm':
        case 'M':
          setMetronome((v) => !v)
          break
        case 'c':
        case 'C':
          setCountIn((v) => (v > 0 ? 0 : 1))
          break
        case 'g':
        case 'G':
          setGuideOn((v) => !v)
          break
        case '[':
          setRate((r) => clamp(Math.round((r - 0.05) * 100) / 100, 0.5, 1.5))
          break
        case ']':
          setRate((r) => clamp(Math.round((r + 0.05) * 100) / 100, 0.5, 1.5))
          break
        case '-':
          setSemitones((s) => Math.max(-12, s - 1))
          break
        case '+':
        case '=':
          setSemitones((s) => Math.min(12, s + 1))
          break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [player, skip, seek, toggleLoop, loop, loopOn])

  // Teclas multimedia del teclado / auriculares.
  useEffect(() => {
    if (!player || !song || !('mediaSession' in navigator)) return
    navigator.mediaSession.metadata = new MediaMetadata({ title: song.title, artist: song.artist ?? 'MoiMoi' })
    navigator.mediaSession.setActionHandler('play', () => void player.play())
    navigator.mediaSession.setActionHandler('pause', () => player.pause())
    navigator.mediaSession.setActionHandler('seekbackward', () => skip(-5))
    navigator.mediaSession.setActionHandler('seekforward', () => skip(5))
    return () => {
      for (const action of ['play', 'pause', 'seekbackward', 'seekforward'] as MediaSessionAction[]) {
        navigator.mediaSession.setActionHandler(action, null)
      }
    }
  }, [player, song, skip])

  const saveMeta = async (patch: { title?: string; artist?: string | null }) => {
    if (!song) return
    try {
      setSong(await api.updateSong(song.id, patch))
    } catch (err) {
      toast.error(err)
    }
  }

  // Volver a analizar descarta las correcciones de tempo (la grilla nueva puede ser otra).
  const reanalyze = async () => {
    if (!song) return
    if ((tempoEdits.length > 0 || beatScale || downbeatShift)
        && !window.confirm('Volver a analizar borra las correcciones de tempo que hiciste. ¿Seguir?')) return
    try {
      await api.reanalyze(song.id)
      toast.show('Volviendo a analizar tempo, acordes y partes…')
      setSong({ ...song, status: 'analyzing', stage: 'En cola para analizar', progress: 0 })
    } catch (err) {
      toast.error(err)
    }
  }

  // ---- vistas ------------------------------------------------------------------------------------
  if (error) {
    return (
      <main className="page">
        <div className="empty">
          <h2 style={{ marginBottom: 8 }}>No se pudo abrir la canción</h2>
          <p>{error}</p>
          <a className="btn" href="#/"><ArrowLeft size={16} />Volver a la biblioteca</a>
        </div>
      </main>
    )
  }
  if (song && song.status !== 'ready') {
    const busy = PROCESSING.has(song.status)
    return (
      <main className="page">
        <div className="card pad loading-overlay">
          <h2>{song.title}</h2>
          <div className="muted">{busy ? song.stage || 'Procesando…' : song.error || 'La canción no está lista'}</div>
          {busy && <div className="progress"><div style={{ width: `${Math.max(3, song.progress * 100)}%` }} /></div>}
          <a className="btn" href="#/"><ArrowLeft size={16} />Biblioteca</a>
        </div>
      </main>
    )
  }
  if (loading || !song || !player) {
    return (
      <main className="page">
        <div className="card pad loading-overlay">
          <Loader2 size={30} className="spin" color="#b7a3ff" />
          <div>{loading?.message ?? 'Cargando…'}</div>
          <div className="progress"><div style={{ width: `${Math.max(3, (loading?.fraction ?? 0) * 100)}%` }} /></div>
        </div>
      </main>
    )
  }

  const duration = player.duration
  const oldAnalysis = !!analysis && (analysis.version ?? 0) < ANALYSIS_VERSION

  return (
    <main className="page player-page">
      <div className="player-head">
        <a className="btn ghost icon" href="#/" aria-label="Volver a la biblioteca"><ArrowLeft size={18} /></a>
        {song.thumbnailUrl
          ? <img className="thumb" src={apiUrl(song.thumbnailUrl)} alt="" />
          : <div className="thumb">{song.title.slice(0, 1).toUpperCase()}</div>}
        <div className="grow player-title">
          <EditableText className="title-edit" value={song.title} key={`t-${song.updatedAt}`} ariaLabel="Título" required
            onSave={(title) => void saveMeta({ title })} />
          <EditableText className="artist-edit" value={song.artist ?? ''} placeholder="Artista" key={`a-${song.updatedAt}`}
            ariaLabel="Artista" onSave={(artist) => void saveMeta({ artist })} />
        </div>
        <div className="facts">
          {key && analysis && (
            <KeyFacts player={player} analysis={analysis} semitones={semitones} notation={settings.notation} />
          )}
          <TempoFact player={player} grid={grid} rate={rate} onOpen={() => setTempoOpen(true)} />
          <div className="fact"><span>Compás</span><b>{grid.beatsPerBar}/4</b></div>
        </div>
        <button className="btn primary" onClick={() => setExportOpen(true)}><Package size={16} />Exportar</button>
        <Menu button={(open) => <button className="btn icon" onClick={open} aria-label="Más opciones"><MoreHorizontal size={18} /></button>}>
          {(close) => (
            <>
              {song.sourceUrl && (
                <button onClick={() => { close(); window.open(song.sourceUrl!, '_blank', 'noopener') }}>
                  <ExternalLink size={15} />Abrir link original
                </button>
              )}
              {canAnalyze && <button onClick={() => { close(); void reanalyze() }}>
                <RefreshCw size={15} />Volver a analizar
              </button>}
              <div className="sep" />
              <button onClick={async () => {
                close()
                if (!window.confirm(`¿Borrar "${song.title}" y sus pistas?`)) return
                try {
                  await api.deleteSong(song.id)
                  navigate('/')
                } catch (err) {
                  toast.error(err)
                }
              }}><Trash2 size={15} color="#ff9b9b" />Borrar canción</button>
            </>
          )}
        </Menu>
      </div>

      {oldAnalysis && canAnalyze && (
        <div className="tiny faint" style={{ margin: '-8px 0 14px' }}>
          Esta canción se analizó con una versión anterior.{' '}
          <button className="linkish" onClick={() => void reanalyze()}>Volver a analizarla</button> para tener mejores
          acordes, tonalidades y cambios de tempo.
        </div>
      )}

      {player.reduced && (
        <div className="tiny faint" style={{ margin: '-8px 0 14px' }}>
          Canción larga: se escucha en calidad reducida{player.mono ? ' (mono)' : ''} para que no se cierre la app. Lo que
          exportes sale en calidad completa.
        </div>
      )}

      <div className="player-grid">
        <div className="player-main">
          <ChordPanel player={player} chords={analysis?.chords ?? []} grid={grid} transposition={transposition} />
          <ChordStrip player={player} chords={analysis?.chords ?? []} downbeats={grid.downbeats} transposition={transposition} onSeek={seek} />
          <Timeline
            player={player}
            peaks={mixPeaks}
            duration={duration}
            sections={sections}
            beats={grid.beats}
            loop={loop}
            loopOn={loopOn}
            onSeek={seek}
            onLoop={setLoopRange}
          />
          <Mixer
            player={player}
            stems={song.stems}
            peaks={peaks?.peaks ?? {}}
            instruments={analysis?.instruments ?? {}}
            mixer={mixer}
            duration={duration}
            band={settings.band}
            onChange={(id, patch) => setMixer((m) => ({ ...m, [id]: { volume: 1, pan: 0, mute: false, solo: false, ...(m[id] ?? {}), ...patch } }))}
            onReplace={setMixer}
            extras={[
              {
                id: 'click', name: 'Click', color: '#c9ced9', on: metronome, volume: metronomeVolume,
                detail: !grid.beats.length ? 'No se detectó el pulso' : `Sonido ${guideInfo?.clickName ?? 'MoiMoi'}`,
                onToggle: () => setMetronome((v) => !v), onVolume: setMetronomeVolume,
              },
              {
                id: 'guide', name: 'Guía', color: '#f0c05a', on: guideOn, volume: guideVolume,
                detail: guideInfo === null ? 'Preparando…'
                  : guideInfo.voices ? `Anuncia las partes${guideInfo.voiceSet ? ` · ${guideInfo.voiceSet}` : ''}`
                    : 'Sin voces para esta canción',
                onToggle: () => setGuideOn((v) => !v), onVolume: setGuideVolume,
              },
            ]}
          />
        </div>
        <aside className="side">
          <SectionsPanel
            player={player}
            sections={sections}
            edited={!!sectionsEdited}
            loop={loop}
            loopOn={loopOn}
            onSeek={seek}
            onLoop={(range) => {
              const same = loop && loopOn && Math.abs(loop.start - range.start) < 0.05 && Math.abs(loop.end - range.end) < 0.05
              if (same) setLoopOn(false)
              else setLoopRange(range, true)
            }}
            onRename={(index, label) => setSectionsEdited(sections.map((s, i) => (i === index ? { ...s, label } : s)))}
            onReset={() => setSectionsEdited(null)}
          />
          {/* En el celular todavía no hay letra automática: sin panel vacío. */}
          {!(health?.standalone && !health.features.lyrics) && (
            <LyricsPanel song={song} player={player} onSeek={seek} onRefreshSong={refreshSong} />
          )}
        </aside>
      </div>

      <Transport
        player={player}
        playing={player.playing}
        rate={rate}
        semitones={semitones}
        keyLabel={keyLabel}
        originalKeyLabel={originalKeyLabel}
        bpmAt={() => tempoAt(grid, player.position)}
        loopOn={loopOn}
        metronome={metronome}
        guide={guideOn}
        metronomeVolume={metronomeVolume}
        countIn={countIn}
        masterVolume={masterVolume}
        tuningCents={tuningCents}
        tuneTo440={tune440}
        onToggle={() => player.toggle()}
        onSkip={skip}
        onRestart={() => seek(loopOn && loop ? loop.start : 0)}
        onRate={(r) => setRate(clamp(r, 0.5, 1.5))}
        onSemitones={setSemitones}
        onLoop={toggleLoop}
        onMetronome={() => setMetronome((v) => !v)}
        onGuide={() => setGuideOn((v) => !v)}
        onMetronomeVolume={setMetronomeVolume}
        onCountIn={setCountIn}
        onMasterVolume={setMasterVolume}
        onTune440={setTune440}
      />

      {tempoOpen && analysis && (
        <TempoPanel
          parts={grid.parts}
          current={partIndexAt(grid.parts, player.position)}
          detected={detected}
          edits={tempoEdits}
          rate={rate}
          songTitle={song.title}
          songArtist={song.artist}
          oldAnalysis={oldAnalysis}
          onEdit={(start, patch) => {
            // Las correcciones viejas (para toda la canción) pasan a ser del tramo.
            if (beatScale || downbeatShift) {
              setBeatScale(null)
              setDownbeatShift(0)
            }
            editTempo(start, patch)
          }}
          onSeek={seek}
          onReanalyze={canAnalyze ? () => { setTempoOpen(false); void reanalyze() } : undefined}
          onClose={() => setTempoOpen(false)}
        />
      )}

      {exportOpen && (
        <ExportDialog
          song={song}
          analysis={analysis}
          mixer={mixer}
          rate={rate}
          semitones={semitones}
          keyLabel={keyLabel}
          band={settings.band}
          onClose={() => setExportOpen(false)}
        />
      )}
    </main>
  )
}

/** Tempo del tramo que suena (se actualiza solo, sin volver a dibujar todo el reproductor). */
function TempoFact({ player, grid, rate, onOpen }: { player: StemPlayer; grid: DerivedGrid; rate: number; onOpen: () => void }) {
  const bpm = useSampled(() => tempoAt(grid, player.position), 250)
  const changes = grid.parts.length > 1
  return (
    <button className="fact" style={{ cursor: 'pointer', textAlign: 'left' }} onClick={onOpen}
      title={changes ? 'Tempo de la parte que suena · tocar para ver los cambios o corregirlo' : 'Corregir el tempo'}>
      <span>Tempo{changes ? ' ahora' : ''}</span>
      <b>{bpm ? Math.round(bpm * rate) : '—'}<small>BPM</small></b>
    </button>
  )
}

/** Tonalidad de la parte que suena y el próximo cambio de tonalidad (si hay). */
function KeyFacts({ player, analysis, semitones, notation }: {
  player: StemPlayer
  analysis: Analysis
  semitones: number
  notation: 'american' | 'latin'
}) {
  const changes = analysis.keyChanges
  const index = useSampled(() => {
    const t = player.position
    let i = -1
    while (i + 1 < changes.length && t >= changes[i + 1].time) i++
    return i
  }, 250)
  const now = index >= 0 ? changes[index] : analysis.keyStart ?? analysis.key
  const next = changes[index + 1]
  const label = keyName(now.tonic, now.mode, semitones, notation)
  const original = keyName(now.tonic, now.mode, 0, notation)
  return (
    <>
      <div className="fact" title={`Tonalidad detectada: ${now.label}`}>
        <span>Tonalidad{changes.length ? ' ahora' : ''}</span>
        <b>{label}{semitones !== 0 && <small>orig. {original}</small>}</b>
      </div>
      {next && (
        <div className="fact" title="Próximo cambio de tonalidad">
          <span>Luego</span>
          <b>{keyName(next.tonic, next.mode, semitones, notation)}<small>{formatTime(next.time)}</small></b>
        </div>
      )}
    </>
  )
}
