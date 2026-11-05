package com.pv.androidfacefusion;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Android Face Fusion V2 - ETAPE 14 : encodeur MP4/H.264 sans audio.
 *
 * Recoit des Bitmap ARGB_8888 UN PAR UN (par exemple depuis l'etape 13)
 * et les encode en AVC/H.264 dans un conteneur MP4. Les horodatages des
 * images sont conserves, en prenant le premier comme origine zero.
 *
 * IMPORTANT :
 * - Cette classe n'est pas encore branchee sur l'interface Android.
 * - Le son original sera ajoute lors d'une etape ulterieure.
 * - Ne pas utiliser depuis le thread principal ; ne pas partager entre threads.
 * - Certaines implementations MediaCodec refusent l'entree YUV flexible ;
 *   une incompatibilite est signalee explicitement, pas masquee.
 * - close() finalise un MP4 normal ; abort() efface un MP4 incomplet.
 */
public final class VideoMp4Encoder implements AutoCloseable {

    private static final String TAG = "VideoMp4Encoder";
    private static final long TIMEOUT_US = 10_000L;
    private static final int MAX_WAIT = 1000;

    private final MediaCodec encoder;
    private final MediaMuxer muxer;
    private final File outputFile;
    private final int width;
    private final int height;
    private final int[] argb;

    private boolean codecStarted;
    private boolean muxerStarted;
    private boolean closed;
    private boolean aborted;
    private boolean sawEnd;
    private int videoTrack = -1;
    private int queuedFrames;
    private int writtenFrames;
    private long firstSourcePtsUs = -1L;
    private long lastInputPtsUs = -1L;

    /**
     * @param outputFile emplacement prive accessible a l'application, *.mp4.
     * @param width largeur de sortie PAIRE (128..1024)
     * @param height hauteur de sortie PAIRE (128..1024)
     * @param nominalFps cadence indicative 1..60 (les vrais PTS sont preserves)
     * @param bitrateBitsPerSecond debit AVC recommande : 1 a 4 millions pour 480p
     */
    public VideoMp4Encoder(File outputFile, int width, int height,
                           int nominalFps, int bitrateBitsPerSecond) throws IOException {
        if (outputFile == null) throw new IllegalArgumentException("Fichier de sortie requis");
        if (width < 128 || width > 1024 || (width & 1) != 0
                || height < 128 || height > 1024 || (height & 1) != 0) {
            throw new IllegalArgumentException("Dimensions paires requises entre 128 et 1024");
        }
        if (nominalFps < 1 || nominalFps > 60) {
            throw new IllegalArgumentException("Frequence nominale : 1 a 60 FPS");
        }
        if (bitrateBitsPerSecond < 100_000 || bitrateBitsPerSecond > 20_000_000) {
            throw new IllegalArgumentException("Debit H.264 hors plage");
        }
        if (outputFile.exists()) {
            throw new IOException("Le fichier existe deja : " + outputFile);
        }
        File parent = outputFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("Dossier de sortie absent");
        }

        this.outputFile = outputFile;
        this.width = width;
        this.height = height;
        this.argb = new int[width * height];

        MediaCodec newEncoder = null;
        MediaMuxer newMuxer = null;
        boolean newStarted = false;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBitsPerSecond);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, nominalFps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            newEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            newEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            newMuxer = new MediaMuxer(outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            newEncoder.start();
            newStarted = true;
        } catch (Exception error) {
            if (newEncoder != null) {
                if (newStarted) try { newEncoder.stop(); } catch (Exception ignored) {}
                newEncoder.release();
            }
            if (newMuxer != null) newMuxer.release();
            if (outputFile.exists()) outputFile.delete();
            throw new IOException("Initialisation encodeur AVC impossible : "
                    + error.getMessage(), error);
        }
        this.encoder = newEncoder;
        this.muxer = newMuxer;
        this.codecStarted = true;
    }

    /**
     * Ajoute un Bitmap a son horodatage original (microsecondes).
     * L'appelant conserve la propriete du Bitmap et doit le recycler.
     */
    public void addFrame(Bitmap bitmap, long sourcePtsUs) throws IOException {
        ensureOpen();
        if (bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            throw new IllegalArgumentException("Bitmap invalide ou dimensions differentes");
        }
        if (sourcePtsUs < 0L) throw new IllegalArgumentException("Horodatage negatif");
        if (firstSourcePtsUs < 0L) firstSourcePtsUs = sourcePtsUs;
        long ptsUs = sourcePtsUs - firstSourcePtsUs;
        if (ptsUs <= lastInputPtsUs) {
            throw new IllegalArgumentException("Horodatages video non strictement croissants");
        }

        // getPixels ne garde pas de nouvelle image 2D en RAM.
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);

        int inputIndex = -1;
        for (int tries = 0; tries < MAX_WAIT && inputIndex < 0; tries++) {
            checkInterrupted();
            inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
            if (inputIndex < 0) drain(false);
        }
        if (inputIndex < 0) throw new IOException("Encodeur bloque : aucune entree libre");

        Image inputImage = null;
        try {
            inputImage = encoder.getInputImage(inputIndex);
            if (inputImage == null || inputImage.getFormat() != ImageFormat.YUV_420_888
                    || inputImage.getWidth() != width || inputImage.getHeight() != height) {
                throw new IOException("Encodeur incompatible avec l'entree YUV_420_888");
            }
            writeYuv420(inputImage);
        } catch (Exception error) {
            // Il n'est plus possible de recuperer ce buffer de maniere sure.
            // L'appelant doit abort() et ne pas reutiliser l'encodeur.
            throw new IOException("Conversion bitmap vers YUV impossible", error);
        } finally {
            if (inputImage != null) inputImage.close();
        }

        encoder.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, ptsUs, 0);
        lastInputPtsUs = ptsUs;
        queuedFrames++;
        drain(false);
    }

    /** Nombre d'images remises a l'encodeur. */
    public int getQueuedFrames() { return queuedFrames; }

    /** Nombre de paquets H.264 ecrits dans le conteneur. */
    public int getWrittenFrames() { return writtenFrames; }

    /**
     * Convertit les pixels RGB en YUV420 selon BT.601 (plage limitee).
     * Respecte rowStride/pixelStride des trois plans du codec Android.
     */
    private void writeYuv420(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) {
            throw new IllegalStateException("Encodeur sans trois plans YUV");
        }
        ByteBuffer y = planes[0].getBuffer();
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int yBase = y.position(), uBase = u.position(), vBase = v.position();
        int yRow = planes[0].getRowStride(), yPixel = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride(), uPixel = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride(), vPixel = planes[2].getPixelStride();

        for (int row = 0; row < height; row += 2) {
            for (int col = 0; col < width; col += 2) {
                int totalR = 0, totalG = 0, totalB = 0;
                for (int dy = 0; dy < 2; dy++) {
                    int yy = row + dy;
                    for (int dx = 0; dx < 2; dx++) {
                        int xx = col + dx;
                        int pixel = argb[yy * width + xx];
                        int r = (pixel >>> 16) & 255;
                        int g = (pixel >>> 8) & 255;
                        int b = pixel & 255;
                        int luminance = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                        y.put(yBase + yy * yRow + xx * yPixel, (byte) clamp(luminance));
                        totalR += r; totalG += g; totalB += b;
                    }
                }
                int r = (totalR + 2) / 4;
                int g = (totalG + 2) / 4;
                int b = (totalB + 2) / 4;
                int chromaU = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int chromaV = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                u.put(uBase + (row / 2) * uRow + (col / 2) * uPixel,
                        (byte) clamp(chromaU));
                v.put(vBase + (row / 2) * vRow + (col / 2) * vPixel,
                        (byte) clamp(chromaV));
            }
        }
    }

    private static int clamp(int val) { return Math.max(0, Math.min(255, val)); }

    private void drain(boolean waitForEnd) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idle = 0;
        while (true) {
            checkInterrupted();
            int outputIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEnd || ++idle > MAX_WAIT) {
                    if (waitForEnd) throw new IOException("Timeout de finalisation AVC");
                    return;
                }
                continue;
            }
            idle = 0;
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw new IOException("Format AVC modifie plusieurs fois");
                videoTrack = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxerStarted = true;
            } else if (outputIndex >= 0) {
                try {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && info.size > 0) {
                        if (!muxerStarted) throw new IOException("Piste MP4 non initialisee");
                        ByteBuffer buffer = encoder.getOutputBuffer(outputIndex);
                        if (buffer == null) throw new IOException("Sortie AVC indisponible");
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        muxer.writeSampleData(videoTrack, buffer, info);
                        writtenFrames++;
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawEnd = true;
                    }
                } finally {
                    encoder.releaseOutputBuffer(outputIndex, false);
                }
                if (sawEnd) return;
            }
        }
    }

    private void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Encodage video interrompu");
        }
    }

    private void ensureOpen() {
        if (closed || aborted) throw new IllegalStateException("Encodeur ferme ou annule");
    }

    /** Termine la piste video et ferme le MP4. */
    @Override
    public void close() throws IOException {
        if (closed) return;
        if (aborted) return;
        if (queuedFrames == 0) {
            abort();
            throw new IOException("Aucune image fournie : fichier MP4 supprime");
        }
        try {
            int inputIndex = -1;
            for (int tries = 0; tries < MAX_WAIT && inputIndex < 0; tries++) {
                inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                if (inputIndex < 0) drain(false);
            }
            if (inputIndex < 0) throw new IOException("Pas de buffer pour fin de flux AVC");
            encoder.queueInputBuffer(inputIndex, 0, 0, lastInputPtsUs + 1L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(true);
            if (!sawEnd || !muxerStarted || writtenFrames == 0) {
                throw new IOException("Aucun MP4 exploitable genere");
            }
            muxer.stop();
            closed = true;
        } catch (Exception error) {
            abort();
            throw new IOException("Finalisation MP4 impossible", error);
        } finally {
            releaseResources();
        }
    }

    /** Efface un conteneur partiel. A utiliser en cas d'exception. */
    public void abort() {
        if (closed || aborted) return;
        aborted = true;
        releaseResources();
        if (outputFile.exists() && !outputFile.delete()) {
            Log.w(TAG, "Impossible de supprimer le MP4 incomplet");
        }
    }

    private boolean resourcesReleased;

    private void releaseResources() {
        if (resourcesReleased) return;
        resourcesReleased = true;
        if (codecStarted) {
            try { encoder.stop(); } catch (Exception ignored) {}
        }
        try { encoder.release(); } catch (Exception ignored) {}
        try { muxer.release(); } catch (Exception ignored) {}
    }
}
