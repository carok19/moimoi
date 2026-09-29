/*
 * Prueba de punta a punta de la interfaz en Chromium (Playwright).
 *
 * 1. Levanta el servidor de prueba (motor de separación falso, no necesita modelos):
 *      .venv/bin/python backend/tests/e2e_server.py --port 4799 --data /tmp/moimoi-e2e \
 *        --wav /tmp/demo.wav --pack /tmp/voces.zip
 * 2. Corre esta prueba (Playwright instalado de forma global: npm i -g playwright):
 *      NODE_PATH="$(npm root -g)" node scripts/prueba_navegador.cjs /tmp/demo.wav /tmp/voces.zip
 *
 * Sube la canción, carga las voces guía, abre la canción, reproduce, cambia velocidad y tono,
 * usa mute/solo, metrónomo y loop, exporta el paquete para Multitrack (con Click, Guía y
 * cuenta), lo envía a Multitrack Alabanza si está abierto y revisa todo en tamaño celular.
 * Guarda capturas en ./capturas-prueba/.
 */
const { chromium } = require('playwright')
const fs = require('fs')
const path = require('path')

const WAV = process.argv[2]
const PACK = process.argv[3]
const BASE = process.env.MOIMOI_URL || 'http://127.0.0.1:4799'
const OUT = path.resolve('capturas-prueba')

if (!WAV || !fs.existsSync(WAV)) {
  console.error('Uso: node scripts/prueba_navegador.cjs <archivo.wav>')
  process.exit(2)
}
fs.mkdirSync(OUT, { recursive: true })

;(async () => {
  const browser = await chromium.launch({
    // Micrófono falso (un tono) para probar la grabación de voces guía.
    args: ['--autoplay-policy=no-user-gesture-required', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
  })
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))
  let failed = false
  const step = async (name, fn) => {
    const started = Date.now()
    try {
      await fn()
      console.log(`OK    ${name} (${Date.now() - started} ms)`)
    } catch (err) {
      failed = true
      console.log(`FALLA ${name}: ${String(err.message || err).split('\n')[0]}`)
      await page.screenshot({ path: path.join(OUT, `falla-${name.replace(/\W+/g, '_')}.png`) })
      throw err
    }
  }
  try {
    await step('biblioteca', async () => {
      await page.goto(BASE + '/')
      await page.getByRole('heading', { name: 'Biblioteca' }).waitFor()
    })
    await step('subir archivo', async () => {
      await page.getByRole('tab', { name: /Subir archivo/ }).click()
      await page.setInputFiles('input[type=file]', WAV)
      await page.getByText(/subida: separando/).waitFor({ timeout: 60000 })
    })
    await step('canción lista', async () => {
      await page.locator('.song-card .chip.accent').first().waitFor({ timeout: 300000 })
      await page.screenshot({ path: path.join(OUT, 'biblioteca.png') })
    })
    if (PACK) {
      await step('cargar voces guía', async () => {
        await page.goto(BASE + '/#/ajustes')
        await page.getByRole('heading', { name: 'Voz guía y click' }).waitFor()
        await page.locator('.guide input[type=file]').setInputFiles(PACK)
        await page.getByText(/Se cargaron/).waitFor({ timeout: 60000 })
        await page.locator('.chip.on', { hasText: 'Español' }).waitFor()
        await page.getByText(/venían dañados/).waitFor()
        const assigned = await page.locator('.cue-row:not(.unset)').count()
        if (assigned < 8) throw new Error('pocas voces asignadas: ' + assigned)
        // Grabar una voz que falta (Verso 3) con el micrófono.
        const row = page.locator('.cue-row', { hasText: 'Verso 3' })
        await row.getByRole('button', { name: /Grabar Verso 3/ }).click()
        await page.waitForTimeout(1500)
        await row.getByRole('button', { name: /Terminar grabación/ }).click()
        await page.locator('.cue-row:not(.unset)', { hasText: 'Verso 3' }).waitFor({ timeout: 20000 })
        await page.locator('.chip', { hasText: 'Classic' }).locator('.chip-main').click()
        await page.locator('.qr svg').first().waitFor()
        await page.screenshot({ path: path.join(OUT, 'ajustes-voz-guia.png'), fullPage: true })
        await page.goto(BASE + '/')
      })
    }
    await step('abrir reproductor', async () => {
      await page.locator('.song-card').first().click()
      await page.locator('button.play').waitFor({ timeout: 120000 })
    })
    await step('reproducir', async () => {
      await page.locator('button.play').click()
      await page.waitForTimeout(2500)
      const t = await page.locator('.transport .time span').textContent()
      if (!t || t === '0:00') throw new Error('el tiempo no avanza: ' + t)
    })
    await step('velocidad y tono', async () => {
      await page.getByRole('button', { name: /Más rápido/ }).click()
      await page.getByRole('button', { name: /Subir medio tono/ }).click()
      const speed = await page.locator('.stepper .label b').first().textContent()
      if (!speed.includes('105%')) throw new Error('velocidad: ' + speed)
    })
    await step('mute y solo', async () => {
      const rows = page.locator('.stem-row')
      await rows.nth(0).locator('button.mute').click()
      await rows.nth(1).locator('button.solo').click()
      if (!(await rows.nth(2).getAttribute('class')).includes('dim')) throw new Error('solo no silenció las demás')
      await rows.nth(1).locator('button.solo').click()
    })
    await step('metrónomo y loop', async () => {
      await page.getByRole('button', { name: /Metrónomo/ }).first().click()
      await page.locator('.timeline .section').nth(1).dblclick()
      await page.waitForTimeout(500)
      if (!/Loop/.test(await page.locator('.timeline .times').textContent())) throw new Error('no se marcó el loop')
      await page.screenshot({ path: path.join(OUT, 'reproductor.png') })
    })
    await step('exportar para Multitrack', async () => {
      await page.getByRole('button', { name: /^Exportar$/ }).click()
      if (PACK && !(await page.getByRole('checkbox', { name: /Pista Guía/ }).isChecked())) throw new Error('la Guía no quedó marcada')
      await page.getByRole('button', { name: /Crear paquete para Multitrack/ }).click()
      await page.locator('.export-result').waitFor({ timeout: 300000 })
      const send = page.getByRole('button', { name: /Enviar a Multitrack Alabanza/ })
      if (await send.isEnabled()) {
        await send.click()
        await page.getByText(/Enviado a Multitrack Alabanza/).first().waitFor({ timeout: 60000 })
        console.log('      → enviado a Multitrack Alabanza')
      } else {
        console.log('      → Multitrack Alabanza no está abierto: solo se descarga')
      }
      await page.screenshot({ path: path.join(OUT, 'exportar.png') })
      const [download] = await Promise.all([
        page.waitForEvent('download', { timeout: 120000 }),
        page.locator('.export-result').getByRole('button', { name: /Descargar/ }).click(),
      ])
      const target = path.join(OUT, download.suggestedFilename())
      await download.saveAs(target)
      console.log('      →', target)
      await page.keyboard.press('Escape')
    })
    await step('ajustes', async () => {
      await page.goto(BASE + '/#/ajustes')
      for (const name of ['Mi banda', 'Voz guía y click', 'Multitrack Alabanza (AI Tracks)', 'Celulares y tablets']) {
        await page.getByRole('heading', { name }).waitFor()
      }
    })
    await step('celular: biblioteca y reproductor', async () => {
      const phone = await browser.newContext({
        viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, acceptDownloads: true,
      })
      const mobile = await phone.newPage()
      mobile.on('pageerror', (e) => errors.push('celular: ' + e.message))
      // Nada puede salirse de la pantalla (salvo dentro de algo que se desplaza o recorta).
      const noOverflow = async (where) => {
        const outside = await mobile.evaluate(() => {
          // El navegador del celular ensancha la página si algo no entra: se compara con la pantalla.
          const limit = Math.min(window.innerWidth, screen.width) + 1
          const clipped = (el) => {
            for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
              if (getComputedStyle(p).overflowX !== 'visible') return p.getBoundingClientRect().right <= limit
            }
            return false
          }
          return [...document.querySelectorAll('body *')]
            .filter((el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.right > limit && !clipped(el) })
            .slice(0, 4)
            .map((el) => `${el.tagName.toLowerCase()}.${[...el.classList].join('.')} (${Math.round(el.getBoundingClientRect().right)}px)`)
        })
        if (outside.length) throw new Error(`${where}: se sale de la pantalla: ${outside.join(', ')}`)
      }
      await mobile.goto(BASE + '/')
      await mobile.locator('.song-card').first().waitFor()
      await noOverflow('biblioteca')
      await mobile.screenshot({ path: path.join(OUT, 'celular-biblioteca.png') })
      await mobile.locator('.song-card').first().click()
      await mobile.locator('button.play').waitFor({ timeout: 120000 })
      await noOverflow('reproductor')
      await mobile.screenshot({ path: path.join(OUT, 'celular-reproductor.png') })
      await mobile.getByRole('button', { name: /^Exportar$/ }).click()
      await mobile.getByRole('button', { name: /Crear paquete para Multitrack/ }).waitFor()
      await noOverflow('exportar')
      await mobile.screenshot({ path: path.join(OUT, 'celular-exportar.png') })
      await mobile.keyboard.press('Escape')
      await mobile.goto(BASE + '/#/ajustes')
      await mobile.getByRole('heading', { name: 'Voz guía y click' }).waitFor()
      await noOverflow('ajustes')
      await mobile.screenshot({ path: path.join(OUT, 'celular-ajustes.png'), fullPage: true })
      await phone.close()
    })
  } catch {
    // ya se informó el paso que falló
  } finally {
    console.log('Errores en la consola del navegador:', errors.length ? errors : 'ninguno')
    await browser.close()
    process.exit(failed || errors.length ? 1 : 0)
  }
})()
