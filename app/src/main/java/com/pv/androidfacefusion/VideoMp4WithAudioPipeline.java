package com.pv.androidfacefusion;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Android Face Fusion V2 - ETAPE 17.
 *
 * Enchaine :
 *  1) VideoMp4Pipeline.processToSilentMp4(...) (decodage, IA, encodage H.264)
 *  2) VideoAudioRemuxer.mergeOriginalAudio(...) (ajout eventuel de l'audio AAC)
 *
 * Cette classe ne modifie pas les photos, n'ouvre aucune interface utilisateur
 * et ne publie pas le resultat dans la galerie Android.
 *
 * IMPORTANT : la compilation Android reelle et le test sur Galaxy A36 restent
 * a effectuer. Utiliser exclusivement depuis un thread de travail.
 */
public final class VideoMp4WithAudioPipeline {

    private static final String TAG = "VideoMp4WithAudio";

    // Premier essai : cote maximal 480 pixels, PAS 480p au sens 854 x 480.
    public static final int FIRST_TEST_MAX_SIDE = 480;
    public static final int FIRST_TEST_MAX_FRAMES = 150;

    private VideoMp4WithAudioPipeline() { }

    /**
     * Les callbacks sont appeles depuis le thread de travail, pas depuis le
     * thread principal. MainActivity devra utiliser runOnUiThread() au besoin.
     */
    public interface ProgressListener {
        void onStage(String stage);
        void onFrameProgress(int completedFrames, long presentationTimeUs);
    }

    public static final class Result {
        /** MP4 final avec audio AAC, ou copie silencieuse si absence d'audio. */
        public final File finalMp4;
        /** MP4 intermediaire silencieux conserve pour diagnostic. */
        public final File silentMp4;
        public final int framesDecoded;
        public final int framesSwapped;
        public final int framesUnchanged;
        public final int framesEncoded;
        public final int outputWidth;
        public final int outputHeight;
        public final boolean originalAudioPresent;
        public final boolean audioCopied;
        public final int audioSamples;

        private Result(VideoMp4Pipeline.Result video,
                       VideoAudioRemuxer.Result audio) {
            this.finalMp4 = audio.mp4File;
            this.silentMp4 = video.mp4File;
            this.framesDecoded = video.framesDecoded;
            this.framesSwapped = video.framesSwapped;
            this.framesUnchanged = video.framesUnchanged;
            this.framesEncoded = video.framesEncoded;
            this.outputWidth = video.outputWidth;
            this.outputHeight = video.outputHeight;
            this.originalAudioPresent = audio.originalAudioPresent;
            this.audioCopied = audio.audioCopied;
            this.audioSamples = audio.audioSamples;
        }
    }

    /**
     * Cree le MP4 final dans le dossier prive de l'application.
     *
     * Si le remultiplexage audio echoue, le MP4 silencieux reste disponible
     * dans le dossier prive pour diagnostic. L'erreur est remontee, elle n'est
     * pas silencieusement transformee en succes.
     *
     * La source doit etre une photo avec visage utilisable. L'URI video doit
     * rester accessible pendant toute l'operation. Audio AAC uniquement.
     *
     * maxFrames n'est PAS une instruction de decoupage : au-dela de cette
     * limite, le pipeline echoue et supprime son export partiel. Utiliser un
     * petit clip reel (idealement 2 a 3 secondes a 30 images/s) pour tester.
     */
    public static Result processWithOriginalAudio(
            Context context,
            Uri originalVideoUri,
            Bitmap sourceBitmap,
            FaceDetector detector,
            FaceEmbedder embedder,
            FaceFusionProcessor processor,
            int maxSide,
            int maxFrames,
            ProgressListener progressListener
    ) throws Exception {

        if (context == null || originalVideoUri == null || sourceBitmap == null
                || sourceBitmap.isRecycled()) {
            throw new IllegalArgumentException(
                    "Contexte, video source et photo source valides requis");
        }
        if (detector == null || embedder == null || processor == null) {
            throw new IllegalArgumentException("Modeles IA non initialises");
        }
        if (maxSide < 128 || maxSide > 1024) {
            throw new IllegalArgumentException("maxSide doit etre compris entre 128 et 1024");
        }
        if (maxFrames < 1 || maxFrames > 3600) {
            throw new IllegalArgumentException("maxFrames doit etre compris entre 1 et 3600");
        }
        checkNotInterrupted();

        notifyStage(progressListener, "Decodage video et remplacement des visages");

        VideoMp4Pipeline.Result video = VideoMp4Pipeline.processToSilentMp4(
                context, originalVideoUri, sourceBitmap,
                detector, embedder, processor,
                maxSide, maxFrames,
                (frames, timeUs) -> {
                    if (progressListener != null) {
                        progressListener.onFrameProgress(frames, timeUs);
                    }
                }
        );

        if (video == null || video.mp4File == null || !video.mp4File.isFile()
                || video.mp4File.length() == 0L) {
            throw new IOException("Le traitement video n'a pas produit de MP4 valide");
        }
        checkNotInterrupted();

        File outputDir = video.mp4File.getParentFile();
        if (outputDir == null || !outputDir.isDirectory()) {
            throw new IOException("Dossier de sortie video indisponible");
        }
        File finalMp4 = new File(outputDir,
                "facefusion_v2_audio_" + UUID.randomUUID().toString() + ".mp4");

        notifyStage(progressListener, "Assemblage du MP4 et de l'audio AAC");
        try {
            VideoAudioRemuxer.Result audio = VideoAudioRemuxer.mergeOriginalAudio(
                    context, originalVideoUri, video.mp4File, finalMp4);
            if (audio == null || audio.mp4File == null
                    || !audio.mp4File.isFile() || audio.mp4File.length() == 0L) {
                throw new IOException("La sortie MP4 avec audio est absente ou vide");
            }
            notifyStage(progressListener, "Export termine dans le dossier prive");
            Log.i(TAG, "MP4 final cree : " + audio.mp4File.getAbsolutePath());
            return new Result(video, audio);
        } catch (Exception error) {
            // Conserver le MP4 silencieux en cas d'echec audio pour analyse.
            // Le remuxeur gere ses propres fichiers temporaires.
            Log.e(TAG, "Echec audio ; MP4 silencieux conserve : "
                    + video.mp4File.getAbsolutePath(), error);
            throw new IOException("Traitement video reussi mais assemblage audio en echec. "
                    + "Le MP4 silencieux reste dans l'espace prive de l'application. "
                    + "Cause : " + error.getMessage(), error);
        }
    }

    public static Result processFirstTest(
            Context context,
            Uri originalVideoUri,
            Bitmap sourceBitmap,
            FaceDetector detector,
            FaceEmbedder embedder,
            FaceFusionProcessor processor,
            ProgressListener progressListener
    ) throws Exception {
        return processWithOriginalAudio(
                context, originalVideoUri, sourceBitmap,
                detector, embedder, processor,
                FIRST_TEST_MAX_SIDE, FIRST_TEST_MAX_FRAMES,
                progressListener);
    }

    private static void notifyStage(ProgressListener listener, String stage) {
        if (listener != null) {
            listener.onStage(stage);
        }
    }

    private static void checkNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Traitement annule");
        }
    }
}
