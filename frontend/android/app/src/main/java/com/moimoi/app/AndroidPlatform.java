package com.moimoi.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import com.moimoi.engine.DemucsSeparator;
import com.moimoi.local.Json;
import com.moimoi.local.Pcm;
import com.moimoi.local.Platform;
import com.moimoi.local.Wav;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** El "servidor" del celular en Android: archivos, decodificador de audio, modelo y servicio. */
public final class AndroidPlatform implements Platform {

    static final String MODEL_ASSET = "models/htdemucs_6s.onnx";
    static final String MODEL_INFO_ASSET = "models/htdemucs_6s.json";

    private final Context context;

    public AndroidPlatform(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public File dataDir() {
        File dir = new File(context.getFilesDir(), "moimoi");
        dir.mkdirs();
        return dir;
    }

    @Override
    public File cacheDir() {
        File dir = new File(context.getCacheDir(), "moimoi");
        dir.mkdirs();
        return dir;
    }

    /** Núcleos para la separación: los "grandes" del celular (en general la mitad). */
    static int threads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(4, cores / 2));
    }

    @Override
    public int thermalLevel() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            return 0;
        }
        android.os.PowerManager power = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null) {
            return 0;
        }
        int level = power.getCurrentThermalStatus(); // 0 = sin calentar … 6 = apagándose
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // Pronóstico a 10 s: 1.0 = el sistema va a frenar la CPU por calor.
            float headroom = power.getThermalHeadroom(10);
            if (!Float.isNaN(headroom)) {
                level = Math.max(level, headroom >= 0.95f ? 2 : headroom >= 0.8f ? 1 : 0);
            }
        }
        return level;
    }

    @Override
    public InputStream openBundled(String path) {
        try {
            return context.getAssets().open(path);
        } catch (IOException e) {
            return null; // la app no trae ese archivo
        }
    }

    @Override
    public String engineDetail() {
        return "Demucs 6 pistas en este celular (" + threads() + " núcleos)";
    }

    @Override
    public String version() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    @Override
    public String fileUrl(File file) {
        return "/_capacitor_file_" + file.getAbsolutePath();
    }

    @Override
    public void working(boolean active) {
        if (active) {
            ProcessingService.start(context);
        } else {
            ProcessingService.stop(context);
        }
    }

    @Override
    public void progress(String title, double fraction, String message) {
        ProcessingService.update(context, title, fraction, message);
    }

    // ---- modelo -----------------------------------------------------------------------------

    @Override
    public DemucsSeparator openSeparator() throws Exception {
        JSONObject meta;
        try (InputStream in = context.getAssets().open(MODEL_INFO_ASSET)) {
            meta = new JSONObject(new String(Json.readAll(in), Json.UTF8));
        } catch (IOException e) {
            throw new IOException("Esta versión de la app no trae el modelo de separación", e);
        }
        JSONArray list = meta.getJSONArray("sources");
        String[] sources = new String[list.length()];
        for (int i = 0; i < sources.length; i++) {
            sources[i] = list.getString(i);
        }
        DemucsSeparator.ModelInfo info = new DemucsSeparator.ModelInfo(sources, meta.getInt("samplerate"),
                meta.getInt("segmentSamples"));
        // El modelo va sin comprimir dentro del APK: se lee directo de ahí, sin copiarlo.
        try (AssetFileDescriptor fd = context.getAssets().openFd(MODEL_ASSET);
             FileInputStream stream = fd.createInputStream()) {
            MappedByteBuffer model = stream.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(),
                    fd.getDeclaredLength());
            return new DemucsSeparator(model, info, threads());
        }
    }

    // ---- etiquetas ------------------------------------------------------------------------------

    @Override
    public Map<String, String> readTags(File source) {
        Map<String, String> tags = new HashMap<>();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(source.getAbsolutePath());
            String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (artist == null) {
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            }
            if (title != null) {
                tags.put("title", title);
            }
            if (artist != null) {
                tags.put("artist", artist);
            }
        } catch (RuntimeException ignored) {
            // sin etiquetas
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // nada
            }
        }
        return tags;
    }

    // ---- decodificar ----------------------------------------------------------------------------

    @Override
    public Decoded decode(File source, File out, Pcm.Cancel cancel, Progress progress) throws IOException {
        if (source.getName().toLowerCase(Locale.ROOT).endsWith(".wav")) {
            try {
                return decodeWav(source, out, cancel, progress);
            } catch (IOException e) {
                // WAV comprimido u otra variante: que lo intente Android
            }
        }
        try {
            return decodeMedia(source, out, cancel, progress);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IOException("No se pudo leer el audio de este archivo en el celular. Prueba con MP3, M4A o WAV.", e);
        }
    }

    private static Decoded decodeWav(File source, File out, Pcm.Cancel cancel, Progress progress) throws IOException {
        try (Wav.Reader in = new Wav.Reader(source); Pcm.Writer w = new Pcm.Writer(out, in.channels)) {
            int block = 1 << 14;
            float[][] ch = new float[in.channels][block];
            float[] inter = new float[block * in.channels];
            for (long start = 0; start < in.frames; start += block) {
                if (cancel.cancelled()) {
                    throw new DemucsSeparator.CancelledException();
                }
                int n = (int) Math.min(block, in.frames - start);
                for (int c = 0; c < in.channels; c++) {
                    in.read(c, start, ch[c], 0, n);
                }
                for (int i = 0; i < n; i++) {
                    for (int c = 0; c < in.channels; c++) {
                        inter[i * in.channels + c] = ch[c][i];
                    }
                }
                w.writeInterleaved(inter, n);
                progress.report((start + n) / (double) in.frames);
            }
            return new Decoded(in.sampleRate, in.channels);
        }
    }

    private static Decoded decodeMedia(File source, File out, Pcm.Cancel cancel, Progress progress) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        Pcm.Writer writer = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) {
                throw new IOException("El archivo no tiene audio");
            }
            extractor.selectTrack(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : -1;
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            try {
                codec = MediaCodec.createDecoderByType(mime);
            } catch (IOException | IllegalArgumentException e) {
                throw new IOException("Este celular no puede leer audio " + mime.replace("audio/", ""));
            }
            codec.configure(format, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            float[] samples = new float[0];
            long lastReport = 0;
            while (true) {
                if (cancel.cancelled()) {
                    throw new DemucsSeparator.CancelledException();
                }
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    if (writer == null) {
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    continue;
                }
                if (index < 0) {
                    continue;
                }
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    if (writer == null) {
                        writer = new Pcm.Writer(out, channels);
                    }
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    ByteBuffer data = buffer.slice().order(ByteOrder.nativeOrder());
                    int bytesPerSample = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4
                            : encoding == AudioFormat.ENCODING_PCM_8BIT ? 1
                            : encoding == 21 /* 24 bits */ ? 3
                            : encoding == 22 /* 32 bits */ ? 4 : 2;
                    int count = info.size / bytesPerSample;
                    if (samples.length < count) {
                        samples = new float[count];
                    }
                    for (int i = 0; i < count; i++) {
                        float v;
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            v = data.getFloat(i * 4);
                        } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
                            v = ((data.get(i) & 0xFF) - 128) / 128f;
                        } else if (encoding == 21) {
                            int p = i * 3;
                            int s = (data.get(p) & 0xFF) | ((data.get(p + 1) & 0xFF) << 8) | (data.get(p + 2) << 16);
                            v = s / 8388608f;
                        } else if (encoding == 22) {
                            v = data.getInt(i * 4) / 2147483648f;
                        } else {
                            v = data.getShort(i * 2) / 32768f;
                        }
                        samples[i] = v;
                    }
                    writer.writeInterleaved(samples, count / channels);
                }
                codec.releaseOutputBuffer(index, false);
                long now = System.currentTimeMillis();
                if (durationUs > 0 && now - lastReport > 200) {
                    lastReport = now;
                    progress.report(Math.min(1.0, info.presentationTimeUs / (double) durationUs));
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
            if (writer == null || writer.frames() == 0) {
                throw new IOException("El archivo no tiene audio");
            }
            return new Decoded(sampleRate, channels);
        } finally {
            if (writer != null) {
                writer.close();
            }
            if (codec != null) {
                try {
                    codec.stop();
                } catch (IllegalStateException ignored) {
                    // ya estaba detenido
                }
                codec.release();
            }
            extractor.release();
        }
    }
}
