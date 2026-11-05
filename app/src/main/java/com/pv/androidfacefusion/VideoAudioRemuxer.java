package com.pv.androidfacefusion;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Android Face Fusion V2 - ETAPE 16.
 *
 * Ajoute la piste sonore originale AAC au MP4 silencieux produit par
 * VideoMp4Pipeline (etape 15). Aucun reencodage des pistes : remultiplexage
 * des donnees compressees avec MediaExtractor et MediaMuxer.
 *
 * LIMITES ACTUELLES :
 * - Videos sources MP4 avec audio AAC (audio/mp4a-latm) uniquement.
 * - Pas de traitement des pistes chiffrees, multicanaux speciaux ou du son
 *   en dehors de la duree video traitee.
 * - Le fichier MP4 silencieux de l'etape 15 doit etre VALIDE et FINALISE.
 * - Pas encore connecte a l'interface MainActivity.
 * - A appeler sur un thread de travail (pas sur le thread UI).
 */
public final class VideoAudioRemuxer {

    private static final String TAG = "VideoAudioRemuxer";
    private static final int DEFAULT_SAMPLE_BUFFER = 4 * 1024 * 1024;
    private static final int MAX_SAMPLE_BUFFER = 16 * 1024 * 1024;
    private static final long FALLBACK_FRAME_DURATION_US = 33_333L;

    private VideoAudioRemuxer() { }

    public static final class Result {
        public final File mp4File;
        public final boolean originalAudioPresent;
        public final boolean audioCopied;
        public final int videoSamples;
        public final int audioSamples;
        public final long videoDurationUs;

        private Result(File mp4File, boolean originalAudioPresent,
                       boolean audioCopied, int videoSamples,
                       int audioSamples, long videoDurationUs) {
            this.mp4File = mp4File;
            this.originalAudioPresent = originalAudioPresent;
            this.audioCopied = audioCopied;
            this.videoSamples = videoSamples;
            this.audioSamples = audioSamples;
            this.videoDurationUs = videoDurationUs;
        }
    }

    private static final class VideoBounds {
        final long firstUs;
        final long durationUs;
        final int samples;

        VideoBounds(long firstUs, long durationUs, int samples) {
            this.firstUs = firstUs;
            this.durationUs = durationUs;
            this.samples = samples;
        }
    }

    /**
     * Cree outputMp4 en conservant la piste video H.264 traitee et la piste
     * audio AAC du fichier source. Le MP4 silencieux est laisse intact.
     *
     * Si la source n'a aucune piste audio, outputMp4 est une copie du MP4
     * silencieux et Result.audioCopied vaut false.
     *
     * @param originalVideoUri video source choisie avec le selecteur Android
     * @param silentMp4      sortie finalisee de VideoMp4Pipeline
     * @param outputMp4      nouveau chemin de sortie inexistant (fichier .mp4)
     */
    public static Result mergeOriginalAudio(
            Context context,
            Uri originalVideoUri,
            File silentMp4,
            File outputMp4
    ) throws IOException {
        if (context == null || originalVideoUri == null || silentMp4 == null
                || outputMp4 == null) {
            throw new IllegalArgumentException("Contexte, source et fichiers requis");
        }
        if (!silentMp4.isFile() || silentMp4.length() == 0L) {
            throw new IOException("MP4 silencieux introuvable ou vide");
        }
        if (!"mp4".equalsIgnoreCase(extension(outputMp4.getName()))) {
            throw new IOException("Le fichier de sortie doit porter l'extension .mp4");
        }
        if (silentMp4.getCanonicalFile().equals(outputMp4.getCanonicalFile())) {
            throw new IOException("La sortie ne doit pas remplacer le MP4 silencieux");
        }
        if (outputMp4.exists()) {
            throw new IOException("Le fichier de sortie existe deja");
        }
        File parent = outputMp4.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("Le dossier de sortie doit deja exister");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Operation annulee");
        }

        MediaExtractor silentVideo = null;
        MediaExtractor originalAudio = null;
        MediaMuxer muxer = null;
        File temporary = null;
        boolean muxerStarted = false;
        boolean muxerStopped = false;
        boolean success = false;

        try {
            VideoBounds bounds = scanVideoBounds(silentMp4);
            silentVideo = new MediaExtractor();
            silentVideo.setDataSource(silentMp4.getAbsolutePath());
            int silentVideoIndex = findTrack(silentVideo, "video/");
            if (silentVideoIndex < 0) {
                throw new IOException("Aucune piste video dans le MP4 traite");
            }
            MediaFormat videoFormat = silentVideo.getTrackFormat(silentVideoIndex);
            if (!MediaFormat.MIMETYPE_VIDEO_AVC.equals(
                    videoFormat.getString(MediaFormat.KEY_MIME))) {
                throw new IOException("La piste traitee doit etre en H.264/AVC");
            }
            silentVideo.selectTrack(silentVideoIndex);

            originalAudio = new MediaExtractor();
            originalAudio.setDataSource(context, originalVideoUri, null);
            int originalVideoIndex = findTrack(originalAudio, "video/");
            int audioIndex = findTrack(originalAudio, "audio/");
            if (originalVideoIndex < 0) {
                throw new IOException("La video source est invalide");
            }

            // L'origine de temps pour l'audio est celle de la VIDEO source.
            originalAudio.selectTrack(originalVideoIndex);
            long originalVideoFirstUs = originalAudio.getSampleTime();
            originalAudio.unselectTrack(originalVideoIndex);
            if (originalVideoFirstUs < 0) {
                throw new IOException("Horodatage initial source indisponible");
            }

            temporary = File.createTempFile("ffv2_audio_", ".mp4", parent);
            if (audioIndex < 0) {
                // Une video muette ne peut pas recevoir une piste inexistante.
                copyFile(silentMp4, temporary);
                publish(temporary, outputMp4);
                success = true;
                return new Result(outputMp4, false, false,
                        bounds.samples, 0, bounds.durationUs);
            }

            MediaFormat audioFormat = originalAudio.getTrackFormat(audioIndex);
            if (!MediaFormat.MIMETYPE_AUDIO_AAC.equals(
                    audioFormat.getString(MediaFormat.KEY_MIME))) {
                throw new IOException("Audio non AAC : transcodage necessaire, non pris en charge");
            }
            originalAudio.selectTrack(audioIndex);
            originalAudio.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            // MediaMuxer recrée un nouveau MP4 ; on ne peut pas simplement
            // ajouter une piste a un MP4 deja finalise.
            muxer = new MediaMuxer(temporary.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int destinationVideoTrack = muxer.addTrack(videoFormat);
            int destinationAudioTrack = muxer.addTrack(audioFormat);
            muxer.start();
            muxerStarted = true;

            int bufferCapacity = computeBufferCapacity(videoFormat, audioFormat);
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferCapacity);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastVideoPts = -1L;
            long lastAudioPts = -1L;
            int writtenVideo = 0;
            int writtenAudio = 0;

            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Remultiplexage audio annule");
                }
                long videoSourcePts = silentVideo.getSampleTime();
                long audioSourcePts = originalAudio.getSampleTime();
                long videoPts = videoSourcePts < 0 ? -1L
                        : videoSourcePts - bounds.firstUs;
                long audioPts = audioSourcePts < 0 ? -1L
                        : audioSourcePts - originalVideoFirstUs;

                // Ne pas recuperer le son anterieur au debut de la video.
                if (audioPts < 0 && audioSourcePts >= 0) {
                    originalAudio.advance();
                    continue;
                }
                // Ne pas laisser le son depasser la fin du rendu traite.
                if (audioPts >= bounds.durationUs) audioPts = -1L;
                if (videoPts < 0 && videoSourcePts >= 0) {
                    throw new IOException("Horodatage video invalide");
                }
                if (videoPts < 0 && audioPts < 0) break;

                boolean writeVideo = videoPts >= 0
                        && (audioPts < 0 || videoPts <= audioPts);

                if (writeVideo) {
                    writeSample(silentVideo, destinationVideoTrack, videoPts,
                            buffer, info, muxer, lastVideoPts);
                    lastVideoPts = videoPts;
                    writtenVideo++;
                    silentVideo.advance();
                } else {
                    writeSample(originalAudio, destinationAudioTrack, audioPts,
                            buffer, info, muxer, lastAudioPts);
                    lastAudioPts = audioPts;
                    writtenAudio++;
                    originalAudio.advance();
                }
            }

            if (writtenVideo != bounds.samples || writtenAudio <= 0) {
                throw new IOException("Remultiplexage incomplet (video="
                        + writtenVideo + ", audio=" + writtenAudio + ")");
            }
            muxer.stop();
            muxerStopped = true;
            muxer.release();
            muxer = null;
            if (!temporary.isFile() || temporary.length() == 0L) {
                throw new IOException("MP4 avec son vide ou absent");
            }
            publish(temporary, outputMp4);
            success = true;
            Log.i(TAG, "MP4 avec audio AAC cree : " + outputMp4.getAbsolutePath());
            return new Result(outputMp4, true, true,
                    writtenVideo, writtenAudio, bounds.durationUs);

        } catch (RuntimeException e) {
            throw new IOException("Remultiplexage audio impossible : " + e.getMessage(), e);
        } finally {
            if (muxer != null) {
                if (muxerStarted && !muxerStopped) {
                    try { muxer.stop(); } catch (Exception ignored) { }
                }
                try { muxer.release(); } catch (Exception ignored) { }
            }
            if (silentVideo != null) {
                try { silentVideo.release(); } catch (Exception ignored) { }
            }
            if (originalAudio != null) {
                try { originalAudio.release(); } catch (Exception ignored) { }
            }
            if (!success && temporary != null && temporary.exists()) {
                if (!temporary.delete()) {
                    Log.w(TAG, "Fichier temporaire non supprime");
                }
            }
        }
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1);
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static VideoBounds scanVideoBounds(File videoMp4) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(videoMp4.getAbsolutePath());
            int videoIndex = findTrack(extractor, "video/");
            if (videoIndex < 0) throw new IOException("Piste video absente");
            extractor.selectTrack(videoIndex);
            long first = -1L;
            long previous = -1L;
            long last = -1L;
            long delta = FALLBACK_FRAME_DURATION_US;
            int count = 0;
            while (extractor.getSampleTime() >= 0L) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Analyse video annulee");
                }
                long pts = extractor.getSampleTime();
                if (first < 0L) first = pts;
                if (previous >= 0L) {
                    if (pts < previous) {
                        throw new IOException("PTS video non chronologiques");
                    }
                    if (pts > previous) delta = pts - previous;
                }
                previous = pts;
                last = pts;
                count++;
                extractor.advance();
            }
            if (count == 0) throw new IOException("MP4 traite sans images");
            delta = Math.max(1L, Math.min(500_000L, delta));
            long durationUs = last - first + delta;
            return new VideoBounds(first, durationUs, count);
        } finally {
            extractor.release();
        }
    }

    private static int computeBufferCapacity(MediaFormat a, MediaFormat b) {
        int size = DEFAULT_SAMPLE_BUFFER;
        for (MediaFormat format : new MediaFormat[]{a, b}) {
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                int requested = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                if (requested > 0) {
                    long suggested = (long) requested + 262_144L;
                    size = (int) Math.min(MAX_SAMPLE_BUFFER,
                            Math.max((long) size, suggested));
                }
            }
        }
        return size;
    }

    private static void writeSample(MediaExtractor extractor, int destinationTrack,
                                    long pts, ByteBuffer buffer, MediaCodec.BufferInfo info,
                                    MediaMuxer muxer, long previousPts) throws IOException {
        if (pts < 0L || (previousPts >= 0L && pts < previousPts)) {
            throw new IOException("PTS non chronologiques dans une piste");
        }
        int flags = extractor.getSampleFlags();
        if ((flags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
            throw new IOException("Piste chiffree non prise en charge");
        }
        if ((flags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            throw new IOException("Trames partielles non prises en charge");
        }
        long reportedSize = extractor.getSampleSize();
        if (reportedSize > buffer.capacity()) {
            throw new IOException("Echantillon trop grand : " + reportedSize);
        }
        buffer.clear();
        int size = extractor.readSampleData(buffer, 0);
        if (size < 0 || size > buffer.capacity()) {
            throw new IOException("Lecture echantillon compresse impossible");
        }
        buffer.position(0);
        buffer.limit(size);
        int muxFlags = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
        info.set(0, size, pts, muxFlags);
        muxer.writeSampleData(destinationTrack, buffer, info);
    }

    private static void copyFile(File source, File target) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] chunk = new byte[256 * 1024];
            int n;
            while ((n = input.read(chunk)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Copie annulee");
                }
                output.write(chunk, 0, n);
            }
            output.getFD().sync();
        }
    }

    private static void publish(File temp, File destination) throws IOException {
        if (destination.exists() || !temp.renameTo(destination)) {
            throw new IOException("Impossible de publier le MP4 final");
        }
    }
}
