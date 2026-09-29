package com.moimoi.local;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/** Archivos WAV: escritura en PCM de 16 bits por bloques y lectura (PCM 16/24/32 bits y float). */
public final class Wav {

    private Wav() {}

    public static byte[] header(int channels, int sampleRate, int bitsPerSample, long frames, boolean floatFormat) {
        long dataBytes = frames * channels * (bitsPerSample / 8);
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put(new byte[] {'R', 'I', 'F', 'F'});
        h.putInt((int) Math.min(0xFFFFFFFFL, 36 + dataBytes));
        h.put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        h.putInt(16);
        h.putShort((short) (floatFormat ? 3 : 1));
        h.putShort((short) channels);
        h.putInt(sampleRate);
        h.putInt(sampleRate * channels * bitsPerSample / 8);
        h.putShort((short) (channels * bitsPerSample / 8));
        h.putShort((short) bitsPerSample);
        h.put(new byte[] {'d', 'a', 't', 'a'});
        h.putInt((int) Math.min(0xFFFFFFFFL, dataBytes));
        return h.array();
    }

    /** Escribe PCM de 16 bits estéreo o mono de a bloques; el encabezado se completa al cerrar. */
    public static final class Writer implements Closeable {
        private final File file;
        private final int channels;
        private final int sampleRate;
        private final OutputStream out;
        private final byte[] buffer = new byte[1 << 16];
        private int used;
        private long frames;

        public Writer(File file, int channels, int sampleRate) throws IOException {
            this.file = file;
            this.channels = channels;
            this.sampleRate = sampleRate;
            out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
            out.write(header(channels, sampleRate, 16, 0, false));
        }

        /** Se escribe directo en un flujo (por ejemplo una entrada de un .zip) con el largo ya conocido. */
        public Writer(OutputStream stream, int channels, int sampleRate, long totalFrames) throws IOException {
            this.file = null;
            this.channels = channels;
            this.sampleRate = sampleRate;
            out = stream;
            out.write(header(channels, sampleRate, 16, totalFrames, false));
        }

        private void putSample(float v) throws IOException {
            if (used + 2 > buffer.length) {
                out.write(buffer, 0, used);
                used = 0;
            }
            int s = Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
            buffer[used++] = (byte) s;
            buffer[used++] = (byte) (s >> 8);
        }

        public void write(float[] left, float[] right, int count) throws IOException {
            for (int i = 0; i < count; i++) {
                putSample(left[i]);
                if (channels > 1) {
                    putSample(right != null ? right[i] : left[i]);
                }
            }
            frames += count;
        }

        public long frames() {
            return frames;
        }

        public void finish() throws IOException {
            if (used > 0) {
                out.write(buffer, 0, used);
                used = 0;
            }
            out.flush();
        }

        @Override
        public void close() throws IOException {
            finish();
            if (file == null) {
                return;
            }
            out.close();
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.seek(0);
                raf.write(header(channels, sampleRate, 16, frames, false));
            }
        }
    }

    /** WAV abierto para lectura aleatoria (mapeado en memoria: no ocupa la memoria de Java). */
    public static final class Reader implements Closeable {
        public final int channels;
        public final int sampleRate;
        public final int bits;
        public final boolean floatFormat;
        public final long frames;
        private final RandomAccessFile raf;
        private final MappedByteBuffer data;
        private final int frameBytes;

        public Reader(File file) throws IOException {
            raf = new RandomAccessFile(file, "r");
            FileChannel channel = raf.getChannel();
            ByteBuffer head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(head, 0);
            head.flip();
            if (head.getInt() != 0x46464952 || head.getInt(8) != 0x45564157) { // RIFF, WAVE
                raf.close();
                throw new IOException("No es un archivo WAV");
            }
            long pos = 12;
            int ch = 0, rate = 0, bitsPer = 0, format = 0;
            long dataStart = -1, dataSize = 0;
            ByteBuffer chunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            while (pos + 8 <= channel.size()) {
                chunk.clear();
                channel.read(chunk, pos);
                chunk.flip();
                int id = chunk.getInt();
                long size = chunk.getInt() & 0xFFFFFFFFL;
                if (id == 0x20746d66) { // "fmt "
                    ByteBuffer fmt = ByteBuffer.allocate((int) Math.min(size, 40)).order(ByteOrder.LITTLE_ENDIAN);
                    channel.read(fmt, pos + 8);
                    fmt.flip();
                    format = fmt.getShort() & 0xFFFF;
                    ch = fmt.getShort();
                    rate = fmt.getInt();
                    fmt.getInt();
                    fmt.getShort();
                    bitsPer = fmt.getShort();
                    if (format == 0xFFFE && size >= 26) { // WAVE_FORMAT_EXTENSIBLE: el subformato
                        fmt.position(24);
                        format = fmt.getShort() & 0xFFFF;
                    }
                } else if (id == 0x61746164) { // "data"
                    dataStart = pos + 8;
                    dataSize = Math.min(size, channel.size() - dataStart);
                    if (size == 0 || size == 0xFFFFFFFFL) {
                        dataSize = channel.size() - dataStart; // WAV escrito por partes
                    }
                    break;
                }
                pos += 8 + size + (size & 1);
            }
            if (dataStart < 0 || ch <= 0 || rate <= 0) {
                raf.close();
                throw new IOException("WAV sin datos de audio");
            }
            channels = ch;
            sampleRate = rate;
            bits = bitsPer;
            floatFormat = format == 3;
            frameBytes = channels * bits / 8;
            if (!(format == 1 || format == 3) || !(bits == 16 || bits == 24 || bits == 32)) {
                raf.close();
                throw new IOException("Formato de WAV no soportado");
            }
            frames = dataSize / frameBytes;
            data = channel.map(FileChannel.MapMode.READ_ONLY, dataStart, frames * frameBytes);
            data.order(ByteOrder.LITTLE_ENDIAN);
        }

        /** Canal `channel` (si el archivo es mono, siempre el único) de [start, start+count); ceros fuera. */
        public void read(int channel, long start, float[] dst, int dstOffset, int count) {
            int c = Math.min(channel, channels - 1);
            int bytes = bits / 8;
            for (int i = 0; i < count; i++) {
                long frame = start + i;
                if (frame < 0 || frame >= frames) {
                    dst[dstOffset + i] = 0f;
                    continue;
                }
                int p = (int) (frame * frameBytes) + c * bytes;
                float v;
                if (floatFormat) {
                    v = data.getFloat(p);
                } else if (bits == 16) {
                    v = data.getShort(p) / 32768f;
                } else if (bits == 24) {
                    int s = (data.get(p) & 0xFF) | ((data.get(p + 1) & 0xFF) << 8) | (data.get(p + 2) << 16);
                    v = s / 8388608f;
                } else {
                    v = data.getInt(p) / 2147483648f;
                }
                dst[dstOffset + i] = v;
            }
        }

        public double duration() {
            return frames / (double) sampleRate;
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }

    static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                throw new EOFException();
            }
            off += n;
        }
    }
}
