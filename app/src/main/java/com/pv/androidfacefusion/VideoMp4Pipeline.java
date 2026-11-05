package com.pv.androidfacefusion;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Android Face Fusion V2 - ETAPE 15.
 *
 * Assemble le decodeur video sequentiel (etape 12), le moteur IA (etape 13)
 * et l'encodeur MP4 H.264 (etape 14).
 *
 * RESULTAT : fichier MP4 silencieux dans l'espace prive de l'application.
 * Ne conserve pas encore l'audio, ne fait pas de head swap et n'est pas
 * encore branche sur MainActivity. La compatibilite materielle MediaCodec
 * doit etre verifiee sur un appareil reel avant une utilisation normale.
 *
 * Executer uniquement depuis un thread de travail (jamais le thread UI).
 */
public final class VideoMp4Pipeline {

    private static final String TAG = "VideoMp4Pipeline";
    private static final int OUTPUT_FPS_HINT = 30;
    private static final int OUTPUT_BITRATE = 1_500_000;

    private VideoMp4Pipeline() {}

    public interface ProgressListener {
        /** Appele depuis le thread de travail. */
        void onProgress(int completedFrames, long presentationTimeUs);
    }

    public static final class Result {
        public final File mp4File;
        public final int framesDecoded;
        public final int framesSwapped;
        public final int framesUnchanged;
        public final int framesEncoded;
        public final int outputWidth;
        public final int outputHeight;
        public final long lastPresentationTimeUs;

        private Result(File mp4File, VideoSequentialFaceSwapProcessor.Report report,
                       int framesEncoded, int outputWidth, int outputHeight) {
            this.mp4File = mp4File;
            this.framesDecoded = report.decodedFrames;
            this.framesSwapped = report.swappedFrames;
            this.framesUnchanged = report.unchangedFrames;
            this.framesEncoded = framesEncoded;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.lastPresentationTimeUs = report.lastPresentationTimeUs;
        }
    }

    /**
     * Transforme la video en MP4 H.264 sans son.
     *
     * maxSide : 128..1024, pour commencer 480.
     * maxFrames : 1..3600 ; ATTENTION : si la video contient davantage
     * d'images, le traitement ECHOUE et le MP4 partiel est supprime.
     * Pour commencer : une video de 3 a 5 secondes, maxFrames=150.
     *
     * Ne modifie ni ne recycle la photo source.
     */
    public static Result processToSilentMp4(
            Context context,
            Uri videoUri,
            Bitmap sourceBitmap,
            FaceDetector faceDetector,
            FaceEmbedder faceEmbedder,
            FaceFusionProcessor processor,
            int maxSide,
            int maxFrames,
            ProgressListener progressListener
    ) throws Exception {

        if (context == null || videoUri == null || sourceBitmap == null
                || sourceBitmap.isRecycled()) {
            throw new IllegalArgumentException("Contexte, video et photo source requis");
        }
        if (faceDetector == null || faceEmbedder == null || processor == null) {
            throw new IllegalArgumentException("Modeles IA non initialises");
        }
        if (maxSide < 128 || maxSide > 1024) {
            throw new IllegalArgumentException("maxSide doit etre entre 128 et 1024");
        }
        if (maxFrames < 1 || maxFrames > 3600) {
            throw new IllegalArgumentException("maxFrames doit etre entre 1 et 3600");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Traitement annule avant le debut");
        }

        File directory = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (directory == null) {
            directory = new File(context.getFilesDir(), "videos_v2");
        }
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Impossible de creer le dossier MP4 prive");
        }

        // Le nom ne doit pas ecraser un resultat existant.
        File outputFile = File.createTempFile("facefusion_v2_", ".mp4", directory);
        if (!outputFile.delete()) {
            throw new IOException("Impossible de preparer le fichier de sortie");
        }

        final VideoMp4Encoder[] encoderRef = new VideoMp4Encoder[1];
        final int[] outputWidth = new int[1];
        final int[] outputHeight = new int[1];
        final long[] lastSubmittedPtsUs = {-1L};
        boolean completed = false;

        try {
            VideoSequentialFaceSwapProcessor.Report report =
                    VideoSequentialFaceSwapProcessor.process(
                            context, videoUri, sourceBitmap,
                            faceDetector, faceEmbedder, processor,
                            maxSide, maxFrames,
                            new VideoSequentialFaceSwapProcessor.Listener() {
                                @Override
                                public void onFrame(int index, long presentationTimeUs,
                                                    Bitmap original, Bitmap processed,
                                                    boolean swapped) throws Exception {
                                    if (Thread.currentThread().isInterrupted()) {
                                        throw new InterruptedException("Traitement video annule");
                                    }
                                    if (processed == null || processed.isRecycled()) {
                                        throw new IOException("Image traitee invalide");
                                    }

                                    // Initialisation aux dimensions de la PREMIERE image,
                                    // apres la rotation realisee par le decodeur.
                                    if (encoderRef[0] == null) {
                                        outputWidth[0] = processed.getWidth() & ~1;
                                        outputHeight[0] = processed.getHeight() & ~1;
                                        if (outputWidth[0] < 128 || outputHeight[0] < 128) {
                                            throw new IOException(
                                                    "Video trop etroite pour l'encodeur H.264 V2");
                                        }
                                        encoderRef[0] = new VideoMp4Encoder(
                                                outputFile,
                                                outputWidth[0], outputHeight[0],
                                                OUTPUT_FPS_HINT, OUTPUT_BITRATE);
                                    }

                                    // Les buffers de MediaCodec exigent des dimensions paires.
                                    // Ne jamais recycler 'processed' : son proprietaire
                                    // reste le processeur de l'etape 13.
                                    Bitmap forEncoder = processed;
                                    boolean copyCreated = false;
                                    try {
                                        if (processed.getWidth() != outputWidth[0]
                                                || processed.getHeight() != outputHeight[0]) {
                                            forEncoder = Bitmap.createScaledBitmap(
                                                    processed, outputWidth[0], outputHeight[0], true);
                                            if (forEncoder == null) {
                                                throw new IOException("Redimensionnement impossible");
                                            }
                                            copyCreated = forEncoder != processed;
                                        }

                                        // Evite les horodatages identiques dans certains MP4.
                                        long ptsUs = Math.max(0L, presentationTimeUs);
                                        if (ptsUs <= lastSubmittedPtsUs[0]) {
                                            ptsUs = lastSubmittedPtsUs[0] + 1L;
                                            Log.w(TAG, "Horodatage duplique ajuste (+1 us)");
                                        }
                                        encoderRef[0].addFrame(forEncoder, ptsUs);
                                        lastSubmittedPtsUs[0] = ptsUs;
                                    } finally {
                                        if (copyCreated && forEncoder != null
                                                && !forEncoder.isRecycled()) {
                                            forEncoder.recycle();
                                        }
                                    }
                                }

                                @Override
                                public void onProgress(int completedFrames,
                                                       long presentationTimeUs) {
                                    if (progressListener != null) {
                                        progressListener.onProgress(
                                                completedFrames, presentationTimeUs);
                                    }
                                }
                            });

            if (encoderRef[0] == null) {
                throw new IOException("Aucune image video a encoder");
            }
            int encoded = encoderRef[0].getQueuedFrames();
            encoderRef[0].close();
            if (!outputFile.isFile() || outputFile.length() == 0L) {
                throw new IOException("Le MP4 final est absent ou vide");
            }
            completed = true;
            Log.i(TAG, "MP4 silencieux cree : " + outputFile.getAbsolutePath()
                    + " (" + encoded + " images)");
            return new Result(outputFile, report, encoded,
                    outputWidth[0], outputHeight[0]);

        } finally {
            if (!completed) {
                if (encoderRef[0] != null) {
                    try {
                        encoderRef[0].abort();
                    } catch (Exception error) {
                        Log.w(TAG, "Echec du nettoyage de l'encodeur", error);
                    }
                }
                if (outputFile.exists() && !outputFile.delete()) {
                    Log.w(TAG, "MP4 partiel non supprime");
                }
            }
        }
    }
}
