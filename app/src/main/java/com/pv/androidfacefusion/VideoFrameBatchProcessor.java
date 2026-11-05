package com.pv.androidfacefusion;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

/**
 * Android Face Fusion V2 - etape 10.
 *
 * Prepare un traitement IA progressif sur plusieurs images d'une video.
 * Le traitement est LOCAL ; ce composant ne cree PAS de video MP4.
 *
 * Pour la validation initiale, il echantillonne des images a des instants
 * reguliers. MediaMetadataRetriever n'est pas un decodeur video sequentiel :
 * un veritable export continu exigera MediaExtractor/MediaCodec/MediaMuxer.
 *
 * ATTENTION : executez process() sur un thread de travail, jamais sur le
 * thread principal Android. Le moteur n'est pas concu pour des appels concurrents.
 */
public final class VideoFrameBatchProcessor {

    /** Rapport final du traitement. */
    public static final class Report {
        public final int imagesProcessed;
        public final int imagesSwapped;
        public final int imagesWithoutFace;
        public final long durationMs;

        private Report(int imagesProcessed, int imagesSwapped,
                       int imagesWithoutFace, long durationMs) {
            this.imagesProcessed = imagesProcessed;
            this.imagesSwapped = imagesSwapped;
            this.imagesWithoutFace = imagesWithoutFace;
            this.durationMs = durationMs;
        }
    }

    /**
     * Callbacks synchrones executes sur le meme thread de travail.
     *
     * Les Bitmap sont valides UNIQUEMENT pendant onFrame(). Si une image doit
     * etre conservee (apercu, stockage, encodeur asynchrone), il faut la copier
     * dans onFrame() et gerer ensuite la liberation de cette copie.
     */
    public interface Listener {
        void onFrame(int index, int total, long timestampMs,
                     Bitmap original, Bitmap processed, boolean swapped)
                throws Exception;

        void onProgress(int completed, int total);
    }

    private VideoFrameBatchProcessor() {
        // Classe utilitaire.
    }

    /**
     * Traite un ensemble d'images reparties sur la duree de la video.
     *
     * @param fpsRequested    frequence d'echantillonnage : 1 a 30 images/s
     * @param maxFrames       nombre maximal d'images a traiter (1 a 900)
     * @param maxSide         dimension maximale d'une image (128 a 1024 pixels)
     * @param listener        obligatoire : les images ne sont pas stockees
     * @return rapport des images traitees
     */
    public static Report process(
            Context context,
            Uri videoUri,
            Bitmap sourceBitmap,
            FaceDetector detector,
            FaceEmbedder embedder,
            FaceFusionProcessor processor,
            int fpsRequested,
            int maxFrames,
            int maxSide,
            Listener listener
    ) throws Exception {

        if (context == null || videoUri == null) {
            throw new IllegalArgumentException("Video ou contexte manquant");
        }
        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            throw new IllegalArgumentException("Photo source absente ou recyclee");
        }
        if (detector == null || embedder == null || processor == null) {
            throw new IllegalArgumentException("Modeles IA non disponibles");
        }
        if (listener == null) {
            throw new IllegalArgumentException("Listener obligatoire");
        }
        if (fpsRequested < 1 || fpsRequested > 30) {
            throw new IllegalArgumentException("fpsRequested doit etre entre 1 et 30");
        }
        if (maxFrames < 1 || maxFrames > 900) {
            throw new IllegalArgumentException("maxFrames doit etre entre 1 et 900");
        }
        if (maxSide < 128 || maxSide > 1024) {
            throw new IllegalArgumentException("maxSide doit etre entre 128 et 1024");
        }

        VideoFaceSwapEngine engine = new VideoFaceSwapEngine(
                detector, embedder, processor);

        try (VideoFrameReader reader = new VideoFrameReader(context, videoUri)) {
            final long durationMs = reader.getDurationMs();
            if (durationMs <= 0) {
                throw new IllegalArgumentException("Duree video invalide");
            }

            // Plafond pour eviter un traitement interminable sur le Galaxy A36.
            // Si le nombre est plafonne, les images sont reparties sur TOUTE
            // la duree de la video : ce n'est pas une extraction image par image.
            final long requested = Math.max(1L,
                    (long) Math.ceil(durationMs * (double) fpsRequested / 1000.0));
            final int total = (int) Math.min((long) maxFrames, requested);

            engine.prepareSource(sourceBitmap);

            int swappedCount = 0;
            int withoutFaceCount = 0;

            for (int i = 0; i < total; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Traitement interrompu");
                }

                // Point au milieu de chaque intervalle pour eviter la fin exacte.
                long timestampMs = Math.min(durationMs - 1,
                        (long) (((i + 0.5) * durationMs) / total));

                Bitmap original = null;
                Bitmap processed = null;
                boolean swapped = false;
                try {
                    original = reader.getFrameAtMs(timestampMs, maxSide);
                    if (original == null) {
                        throw new IllegalStateException("Image video indisponible");
                    }

                    try {
                        processed = engine.swapFrame(original);
                        swapped = true;
                        swappedCount++;
                    } catch (Exception error) {
                        // Si le visage disparait ponctuellement, on conserve
                        // l'image non modifiee. Les autres erreurs remontent.
                        String message = error.getMessage();
                        if (message != null && message.contains(
                                "No face detected in target image")) {
                            processed = original;
                            withoutFaceCount++;
                        } else {
                            throw error;
                        }
                    }

                    listener.onFrame(i, total, timestampMs,
                            original, processed, swapped);
                } finally {
                    if (processed != null && processed != original
                            && !processed.isRecycled()) {
                        processed.recycle();
                    }
                    if (original != null && !original.isRecycled()) {
                        original.recycle();
                    }
                }
                listener.onProgress(i + 1, total);
            }

            return new Report(total, swappedCount, withoutFaceCount, durationMs);
        } finally {
            engine.clearSource();
        }
    }
}
