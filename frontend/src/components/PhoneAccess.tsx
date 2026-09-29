import { useEffect, useMemo, useState } from 'react'
import { Smartphone } from 'lucide-react'
import qrcode from 'qrcode-generator'
import { api } from '../api/client'
import { isNativeApp, serverBase } from '../api/base'
import type { NetworkInfo } from '../api/types'
import { useApp } from '../context'
import { navigate } from '../hooks/useHashRoute'
import { ANDROID_APK_URL } from '../links'
import { useToast } from './Toasts'

export function Qr({ text, size = 150 }: { text: string; size?: number }) {
  const svg = useMemo(() => {
    const qr = qrcode(0, 'M')
    qr.addData(text)
    qr.make()
    return qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true })
  }, [text])
  return <div className="qr" style={{ width: size, height: size }} role="img" aria-label={`Código QR: ${text}`}
    dangerouslySetInnerHTML={{ __html: svg }} />
}

/** Ajustes → Celulares: permiso, direcciones y códigos QR para abrir MoiMoi desde el celular. */
export function PhoneAccess() {
  const toast = useToast()
  const { settings, saveSettings } = useApp()
  const [info, setInfo] = useState<NetworkInfo | null>(null)

  useEffect(() => {
    api.network().then(setInfo).catch(() => setInfo(null))
  }, [settings.lanAccess])

  if (isNativeApp) {
    return (
      <div className="stack">
        <div className="small">Conectado a MoiMoi en <b>{serverBase().replace(/^https?:\/\//, '')}</b>.</div>
        <div className="small muted">
          Las canciones se separan en esa computadora; desde el celular puedes agregarlas (links o archivos del celular),
          escucharlas, exportarlas y compartirlas por WhatsApp.
        </div>
        <button className="btn" style={{ alignSelf: 'flex-start' }} onClick={() => navigate('#/conectar')}>
          <Smartphone size={16} />Cambiar de computadora
        </button>
      </div>
    )
  }

  if (!info) return <div className="small muted">Consultando la red…</div>

  const ip = info.http[0]?.replace(/^http:\/\//, '').replace(/:\d+$/, '')
  const browserUrl = info.https[0] ?? info.http[0]

  return (
    <div className="stack">
      <div className="small muted">
        Con el celular conectado a la <b>misma red WiFi</b> que esta computadora puedes usar MoiMoi desde el celular:
        agregar canciones (links o archivos del celular), escuchar y mezclar las pistas, exportar y compartir por WhatsApp.
        La separación la sigue haciendo esta computadora.
      </div>
      <label className="toggle">
        <input type="checkbox" checked={settings.lanAccess} disabled={!info.local}
          onChange={(e) => saveSettings({ lanAccess: e.target.checked }).catch((err) => toast.error(err))} />
        <span className="track" />
        <span>Permitir celulares y otras computadoras de la red</span>
      </label>
      {!info.local && <div className="tiny muted">Este permiso solo se cambia desde la computadora donde corre MoiMoi.</div>}
      {!info.listening && (
        <div className="banner err small" style={{ marginBottom: 0 }}>
          MoiMoi se abrió solo para esta computadora. Ciérralo y ábrelo de nuevo sin <code>--host 127.0.0.1</code>.
        </div>
      )}
      {info.listening && settings.lanAccess && !ip && (
        <div className="small muted">No se encontró la red WiFi de esta computadora. Conéctala a la red y recarga esta página.</div>
      )}
      {info.listening && settings.lanAccess && ip && (
        <div className="phone-grid">
          <div className="phone-card">
            <h3>App MoiMoi (Android)</h3>
            <div className="small muted">Instala la app, ábrela y escribe esta dirección:</div>
            <div className="big-address">{ip}</div>
            <Qr text={ANDROID_APK_URL} size={132} />
            <a className="small" href={ANDROID_APK_URL} target="_blank" rel="noopener">Descargar la app (APK)</a>
            <div className="tiny muted">Escanea el código con la cámara del celular para bajar la app. Android pide permiso para instalar apps de fuera de Play Store.</div>
          </div>
          <div className="phone-card">
            <h3>Navegador del celular</h3>
            <div className="small muted">Escanea el código o escribe:</div>
            <div className="big-address small-text">{browserUrl}</div>
            <Qr text={browserUrl} size={132} />
            {info.https.length > 0 && (
              <div className="tiny muted">
                La primera vez el navegador avisa que la conexión "no es privada": toca <i>Configuración avanzada → Continuar</i>.
                Es normal: el certificado lo creó MoiMoi en esta computadora. Para compartir .zip por WhatsApp usa la app.
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
