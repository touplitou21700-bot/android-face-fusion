package com.pv.androidfacefusion;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

/**
 * Android Face Fusion V2 - ETAPE 13.
 *
 * Relie le decodeur video sequentiel de l'etape 12 au moteur de face swap
 * de l'etape 8. Le traitement est effectue sur UNE image a la fois.
 *
 * Ne cree PAS encore de fichier MP4 et ne traite PAS la tete entiere.
 * A executer sur un thread de travail, jamais sur le thread principal Android.
 */
public final class VideoSequentialFaceSwapProcessor {

    private static final String TAG = "VideoSequentialFaceSwap";

    private VideoSequentialFaceSwapProcessor() {
        // Classe utilitaire : aucune instance necessaire.
    }

    /**
     * Les Bitmap transmis sont valides uniquement pendant onFrame().
     * Le decodeur recyclera l'image d'origine apres le callback ; cette
     * classe recyclera le resultat IA apres le callback.
     *
     * Pour conserver une image, faire une copie dans onFrame() et en
     * assumer la liberation. Ne pas executer d'operations d'interface
     * directement ici : utiliser runOnUiThread().
     */
    public interface Listener {
        void onFrame(int index, long presentationTimeUs,
                     Bitmap original, Bitmap processed, boolean swapped)
                throws Exception;

        /** Le nombre total d'images n'est pas connu a l'avance. */
        void onProgress(int completedFrames, long presentationTimeUs);
    }

    public static final class Report {
        public final int decodedFrames;
        public final int swappedFrames;
        public final int unchangedFrames;
        public final int sourceWidth;
        public final int sourceHeight;
        public final int rotationDegrees;
        public final long lastPresentationTimeUs;
        public final String videoMime;

        private Report(VideoSequentialDecoder.Report decoded,
                       int swappedFrames, int unchangedFrames) {
            this.decodedFrames = decoded.decodedFrames;
            this.swappedFrames = swappedFrames;
            this.unchangedFrames = unchangedFrames;
            this.sourceWidth = decoded.sourceWidth;
            this.sourceHeight = decoded.sourceHeight;
            this.rotationDegrees = decoded.rotationDegrees;
            this.lastPresentationTimeUs = decoded.lastPresentationTimeUs;
            this.videoMime = decoded.videoMime;
        }
    }

    /**
     * Prepare le visage source une seule fois, puis traite les images
     * decodees successivement a leur horodatage d'origine.
     *
     * @param maxSide   cote maximal du Bitmap transmis a l'IA (128..1024)
     * @param maxFrames limite de securite (1..3600) : le traitement S'ARRETE
     *                  par exception si la video contient davantage d'images
     * @param listener  appele sur le thread de travail ; ne pas garder les Bitmap
     */
    public static Report process(
            Context context,
            Uri videoUri,
            Bitmap sourceBitmap,
            FaceDetector detector,
            FaceEmbedder embedder,
            FaceFusionProcessor processor,
            int maxSide,
            int maxFrames,
            Listener listener
    ) throws Exception {

        if (context == null || videoUri == null || listener == null) {
            throw new IllegalArgumentException(
                    "Contexte, video et listener obligatoires");
        }
        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            throw new IllegalArgumentException("Photo source invalide");
        }
        if (detector == null || embedder == null || processor == null) {
            throw new IllegalArgumentException("Modeles IA non initialises");
        }
        if (maxSide < 128 || maxSide > 1024) {
            throw new IllegalArgumentException("maxSide doit etre entre 128 et 1024");
        }
        if (maxFrames < 1 || maxFrames > 3600) {
            throw new IllegalArgumentException("maxFrames doit etre entre 1 et 3600");
        }

        VideoFaceSwapEngine engine = new VideoFaceSwapEngine(
                detector, embedder, processor);
        final int[] counters = new int[2]; // [0] modifiees ; [1] sans visage

        try {
            // Une seule detection du visage source pour la video entiere.
            engine.prepareSource(sourceBitmap);

            VideoSequentialDecoder.Report decoded = VideoSequentialDecoder.decode(
                    context, videoUri, maxSide, maxFrames,
                    (index, timestampUs, original) -> {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException("Traitement video annule");
                        }

                        Bitmap processed = null;
                        boolean swapped = false;
                        try {
                            try {
                                processed = engine.swapFrame(original);
                                swapped = true;
                                counters[0]++;
                            } catch (Exception error) {
                                // Une image sans visage reste intacte ; les autres
                                // erreurs (motele, memoire...) ne sont pas masquees.
                                if (isNoFaceError(error)) {
                                    processed = original;
                                    counters[1]++;
                                } else {
                                    throw error;
                                }
                            }

                            listener.onFrame(index, timestampUs,
                                    original, processed, swapped);
                        } finally {
                            // Le decodeur libere lui-meme original.
                            if (processed != null && processed != original
                                    && !processed.isRecycled()) {
                                processed.recycle();
                            }
                        }

                        listener.onProgress(index + 1, timestampUs);
                    });

            Log.i(TAG, "Termine : " + decoded.decodedFrames
                    + " images, " + counters[0] + " remplacees, "
                    + counters[1] + " conservees");

            return new Report(decoded, counters[0], counters[1]);
        } finally {
            engine.clearSource();
        }
    }

    private static boolean isNoFaceError(Exception error) {
        String message = error.getMessage();
        return message != null &&
                message.startsWith("No face detected in target image");
    }
}
