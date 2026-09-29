import { useEffect, useState } from 'react'
import { Cpu, Zap } from 'lucide-react'
import { isNativeApp, serverBase } from './api/base'
import { AppProvider, useApp } from './context'
import { navigate, useHashRoute } from './hooks/useHashRoute'
import { startNative } from './native'
import { ToastProvider } from './components/Toasts'
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
  const gpu = engine.device === 'cuda' || engine.device === 'mps'
  return (
    <div className="engine-pill" title={engine.detail}>
      <span className={`dot${gpu ? '' : ' warn'}`} />
      {gpu ? <Zap size={14} /> : <Cpu size={14} />}
      {gpu ? `GPU${engine.gpu ? ` · ${engine.gpu}` : ''}` : 'Procesador (CPU)'}
    </div>
  )
}

function Shell() {
  const route = useHashRoute()
  return (
    <ToastProvider lifted={route.name === 'player'}>
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

  // App de Android sin computadora elegida (o pidiendo cambiarla): pantalla para conectar.
  if (isNativeApp && (!server || route.name === 'connect')) {
    return (
      <ConnectPage
        onConnected={(url) => { setServer(url); navigate('#/') }}
        onCancel={server ? () => navigate('#/ajustes') : undefined}
      />
    )
  }
  return (
    <AppProvider key={server}>
      <Shell />
    </AppProvider>
  )
}
