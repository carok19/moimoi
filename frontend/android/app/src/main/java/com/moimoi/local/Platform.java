package com.moimoi.local;

import com.moimoi.engine.DemucsSeparator;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/** Lo que el "servidor" del celular necesita del sistema (Android o, en las pruebas, la computadora). */
public interface Platform {

    /** Formato del audio decodificado. */
    final class Decoded {
        public final int sampleRate;
        public final int channels;

        public Decoded(int sampleRate, int channels) {
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    interface Progress {
        void report(double fraction);
    }

    /** Carpeta de datos de la app (canciones y ajustes). */
    File dataDir();

    /** Carpeta de archivos temporales (audio decodificado, exportaciones). */
    File cacheDir();

    /**
     * Decodifica un archivo de audio o de video (su pista de audio) a float32 intercalado en `out`
     * (ver Pcm.Writer), a su frecuencia y cantidad de canales originales.
     */
    Decoded decode(File source, File out, Pcm.Cancel cancel, Progress progress) throws IOException;

    /** Carga el modelo de separación. */
    DemucsSeparator openSeparator() throws Exception;

    /** Título y artista guardados en el archivo ("title", "artist"), si tiene. */
    Map<String, String> readTags(File source);

    /** URL con la que la interfaz lee un archivo del celular. */
    String fileUrl(File file);

    /** Empezó (true) o terminó (false) el trabajo pesado: en Android, el servicio en primer plano. */
    void working(boolean active);

    /** Avance del trabajo pesado en curso, para la notificación. */
    void progress(String title, double fraction, String message);

    /** Versión de la app. */
    String version();

    /** Descripción del motor para "health" (p. ej. "Demucs 6 pistas · 4 núcleos"). */
    String engineDetail();
}
