import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api } from './api/client'
import type { Health, Presets, Settings } from './api/types'

export const DEFAULT_SETTINGS: Settings = {
  defaultPreset: '6stems',
  defaultQuality: 'normal',
  band: [],
  notation: 'american',
  countInBars: 1,
  metronomeVolume: 0.7,
  metronomeSound: 'click',
}

interface AppData {
  health: Health | null
  presets: Presets | null
  settings: Settings
  saveSettings: (patch: Partial<Settings>) => Promise<void>
  offline: boolean
}

const AppContext = createContext<AppData>({
  health: null, presets: null, settings: DEFAULT_SETTINGS, saveSettings: async () => {}, offline: false,
})

export function useApp(): AppData {
  return useContext(AppContext)
}

export function AppProvider({ children }: { children: ReactNode }) {
  const [health, setHealth] = useState<Health | null>(null)
  const [presets, setPresets] = useState<Presets | null>(null)
  const [settings, setSettings] = useState<Settings>(DEFAULT_SETTINGS)
  const [offline, setOffline] = useState(false)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const [h, p, s] = await Promise.all([api.health(), api.presets(), api.settings()])
        if (cancelled) return
        setHealth(h)
        setPresets(p)
        setSettings({ ...DEFAULT_SETTINGS, ...s })
        setOffline(false)
      } catch {
        if (!cancelled) setOffline(true)
      }
    }
    void load()
    const timer = window.setInterval(() => {
      api.health().then((h) => { setHealth(h); setOffline(false) }).catch(() => setOffline(true))
    }, 15000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [])

  const saveSettings = useCallback(async (patch: Partial<Settings>) => {
    setSettings((s) => ({ ...s, ...patch }))
    const saved = await api.saveSettings(patch)
    setSettings({ ...DEFAULT_SETTINGS, ...saved })
  }, [])

  return (
    <AppContext.Provider value={{ health, presets, settings, saveSettings, offline }}>
      {children}
    </AppContext.Provider>
  )
}
