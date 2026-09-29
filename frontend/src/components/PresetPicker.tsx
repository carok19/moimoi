import type { PresetId, Quality } from '../api/types'
import { useApp } from '../context'
import { PRESET_STEMS, STEM_INFO } from '../stems'

const NAMES: Record<PresetId, string> = {
  '2stems': '2 pistas',
  '4stems': '4 pistas',
  '6stems': '6 pistas',
}

interface Props {
  preset: PresetId
  quality: Quality
  onPreset: (p: PresetId) => void
  onQuality: (q: Quality) => void
}

export function PresetPicker({ preset, quality, onPreset, onQuality }: Props) {
  const { presets } = useApp()
  // En el celular hay una sola calidad: no se muestra la opción.
  const qualities = presets?.qualities.length ?? 2
  const list = presets?.presets ?? (Object.keys(PRESET_STEMS) as PresetId[]).map((id) => ({
    id, name: NAMES[id], description: '', stems: PRESET_STEMS[id],
  }))
  return (
    <div className="options">
      <div>
        <div className="small muted" style={{ marginBottom: 8 }}>¿En qué pistas la separo?</div>
        <div className="presets">
          {list.map((p) => (
            <button key={p.id} type="button" className={`preset${preset === p.id ? ' active' : ''}`} onClick={() => onPreset(p.id)}>
              <span className="title">{NAMES[p.id]}</span>
              <span className="stem-dots">
                {p.stems.map((s) => (
                  <span key={s}><i style={{ background: STEM_INFO[s].color }} />{STEM_INFO[s].name}</span>
                ))}
              </span>
            </button>
          ))}
        </div>
        {/* En pantallas chicas los botones muestran solo el nombre: acá van las pistas de la opción elegida. */}
        <span className="stem-dots preset-stems">
          {(list.find((p) => p.id === preset)?.stems ?? []).map((s) => (
            <span key={s}><i style={{ background: STEM_INFO[s].color }} />{STEM_INFO[s].name}</span>
          ))}
        </span>
      </div>
      {qualities > 1 && <div>
        <div className="small muted" style={{ marginBottom: 8 }}>Calidad</div>
        <div className="segmented" role="radiogroup" aria-label="Calidad">
          <button type="button" className={quality === 'normal' ? 'active' : ''} onClick={() => onQuality('normal')}
            title="Rápida y con muy buena calidad">Normal</button>
          <button type="button" className={quality === 'alta' ? 'active' : ''} onClick={() => onQuality('alta')}
            title="Separación más limpia; tarda entre 2 y 4 veces más">Alta</button>
        </div>
      </div>}
    </div>
  )
}
