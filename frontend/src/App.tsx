import { useEffect, useState } from 'react'
import { Cpu, Smartphone, Zap } from 'lucide-react'
import { isNativeApp, isStandalone, serverBase } from './api/base'
import { onImported } from './api/local'
import { AppProvider, useApp } from './context'
import { navigate, useHashRoute } from './hooks/useHashRoute'
import { startNative } from './native'
import { ToastProvider, useToast } from './components/Toasts'
import { ConnectPage } from './pages/ConnectPage'
import { LibraryPage } from './pages/LibraryPage'
import { PlayerPage } from './pages/PlayerPage'
import { SettingsPage } from './pages/SettingsPage'

function EnginePill() {
  const { health, offline } = useApp()
  if (offline) {
    return <div className="engine-pill"><span className="dot err" />Sin conexión con MoiMoi</div>
  }
  if (!health) return null
  const engine = health.engine
  if (!engine.available) {
    return <div className="engine-pill"><span className="dot err" />IA no instalada</div>
  }
  if (engine.device === 'phone') {
    return (
      <div className="engine-pill" title={engine.detail}>
        <span className="dot" /><Smartphone size={14} />En este celular
      </div>
    )
  }
  const gpu = engine.device === 'cuda' || engine.device === 'mps'
  return (
    <div className="engine-pill" title={engine.detail}>
      <span className={`dot${gpu ? '' : ' warn'}`} />
      {gpu ? <Zap size={14} /> : <Cpu size={14} />}
      {gpu ? `GPU${engine.gpu ? ` · ${engine.gpu}` : ''}` : 'Procesador (CPU)'}
    </div>
  )
}

/** Audios compartidos con MoiMoi desde otras apps (modo celular): se avisa y se va a la biblioteca. */
function SharedImports() {
  const toast = useToast()
  useEffect(() => {
    if (!isStandalone()) return
    return onImported(({ songs, errors }) => {
      if (songs.length) {
        toast.show(songs.length === 1 ? `"${songs[0].title}" agregada: separando pistas…`
          : `${songs.length} canciones agregadas: separando pistas…`, 'ok')
        navigate('#/')
        window.dispatchEvent(new Event('moimoi:canciones'))
      }
      for (const e of errors) toast.show(`${e.name}: ${e.error}`, 'err', 7000)
    })
  }, [toast])
  return null
}

function Shell() {
  const route = useHashRoute()
  return (
    <ToastProvider lifted={route.name === 'player'}>
      <SharedImports />
      <div className="app">
        <header className="topbar">
          <a className="brand" href="#/">
            <img src="/favicon.svg" alt="" />
            <span>Moi<b>Moi</b></span>
          </a>
          <nav className="nav">
            <a href="#/" className={route.name === 'library' ? 'active' : ''}>Biblioteca</a>
            <a href="#/ajustes" className={route.name === 'settings' ? 'active' : ''}>Ajustes</a>
          </nav>
          <div className="spacer" />
          <EnginePill />
        </header>
        {(route.name === 'library' || route.name === 'connect') && <LibraryPage />}
        {route.name === 'player' && <PlayerPage key={route.id} songId={route.id} />}
        {route.name === 'settings' && <SettingsPage />}
      </div>
    </ToastProvider>
  )
}

export function App() {
  const route = useHashRoute()
  const [server, setServer] = useState(serverBase())

  useEffect(() => startNative(), [])

  // App de Android: todo se hace en el celular. Opcionalmente se conecta a MoiMoi en una computadora.
  if (isNativeApp && route.name === 'connect') {
    return (
      <ConnectPage
        onConnected={(url) => { setServer(url); navigate('#/') }}
        onCancel={() => navigate('#/ajustes')}
      />
    )
  }
  return (
    <AppProvider key={server || 'celular'}>
      <Shell />
    </AppProvider>
  )
}
