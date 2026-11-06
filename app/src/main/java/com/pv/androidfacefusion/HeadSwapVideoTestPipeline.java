package com.pv.androidfacefusion;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * ETAPE 36 - premier Head Swap VIDEO experimental, local, tres court.
 *
 * Chaines reutilisees : VideoSequentialDecoder (etape 12),
 * VideoMp4Encoder (etape 14), VideoAudioRemuxer (etape 16),
 * HeadSwapAlignmentEngine / HeadSwapPose3D / HeadSwapPoseCompositor.
 *
 * Limites importantes :
 * - 2,3 s au maximum, 150 images, 480 px sur le plus grand cote.
 * - source fixe, segmentation et pose recalculées pour chaque frame : lent.
 * - transformation 2D guidee par des reperes 3D, PAS reconstruction 3D.
 * - si une frame ne peut pas etre traitee, elle reste identique a l'originale.
 * - Audio AAC d'origine exclusivement (via l'etape 16).
 * - ne doit jamais etre appele sur le thread UI.
 */
public final class HeadSwapVideoTestPipeline {
    private static final String TAG = "HeadSwapVideoTest";
    public static final long MAX_DURATION_MS = 2_300L;
    public static final int MAX_FRAMES = 150;
    public static final int MAX_SIDE = 480;

    private HeadSwapVideoTestPipeline() { }

    public interface ProgressListener {
        void onStage(String description);
        void onFrame(int completed, int replaced, int unchanged);
    }

    /** Corrections supplementaires appliquees a toutes les images du test. */
    public static final class Options {
        public final float offsetX;
        public final float offsetY;
        public final float sizeFactor;
        public final float extraAngle;
        public final int featherPx;
        public final int skinColorPercent;

        public Options(float offsetX, float offsetY, float sizeFactor,
                       float extraAngle, int featherPx, int skinColorPercent) {
            if (!Float.isFinite(offsetX) || !Float.isFinite(offsetY)
                    || !Float.isFinite(sizeFactor) || !Float.isFinite(extraAngle)
                    || Math.abs(offsetX) > .5f || Math.abs(offsetY) > .5f
                    || sizeFactor < .7f || sizeFactor > 1.3f
                    || Math.abs(extraAngle) > 90f
                    || featherPx < 0 || featherPx > 24
                    || skinColorPercent < 0 || skinColorPercent > 100) {
                throw new IllegalArgumentException("Reglages Head Swap video hors limites.");
            }
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.sizeFactor = sizeFactor;
            this.extraAngle = extraAngle;
            this.featherPx = featherPx;
            this.skinColorPercent = skinColorPercent;
        }

        public static Options defaults() {
            return new Options(0f, 0f, 1f, 0f, 8, 60);
        }
    }

    public static final class Result {
        public final File mp4File;
        public final int framesDecoded;
        public final int framesReplaced;
        public final int framesUnchanged;
        public final int incompatiblePoses;
        public final int framesEncoded;
        public final boolean audioCopied;
        public final String firstFrameError;
        public final int width;
        public final int height;

        Result(File mp4File, int decoded, int replaced, int unchanged,
               int incompatible, int encoded, boolean audioCopied,
               String firstFrameError, int width, int height) {
            this.mp4File = mp4File;
            this.framesDecoded = decoded;
            this.framesReplaced = replaced;
            this.framesUnchanged = unchanged;
            this.incompatiblePoses = incompatible;
            this.framesEncoded = encoded;
            this.audioCopied = audioCopied;
            this.firstFrameError = firstFrameError;
            this.width = width;
            this.height = height;
        }
    }

    public static Result create(Context context, Uri originalVideo, Bitmap source,
                                ProgressListener listener) throws Exception {
        return create(context, originalVideo, source, Options.defaults(), listener);
    }

    public static Result create(Context context, Uri originalVideo, Bitmap source,
                                Options options, ProgressListener listener) throws Exception {
        if (context == null || originalVideo == null || source == null
                || source.isRecycled() || options == null) {
            throw new IllegalArgumentException("Photo source ou video manquante.");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Traitement annule.");
        }
        long duration = readDurationMs(context, originalVideo);
        if (duration <= 0 || duration > MAX_DURATION_MS) {
            throw new IOException("Choisir une video MP4 de 1 a 2 secondes (max 2,3 s). "
                    + "Duree detectee : " + duration + " ms. "
                    + "Ce mode ne decoupe pas les videos longues.");
        }
        File cache = context.getCacheDir();
        if (cache == null || !cache.isDirectory()) {
            throw new IOException("Cache prive Android indisponible.");
        }
        String id = UUID.randomUUID().toString();
        File silent = new File(cache, "hs_v2_" + id + "_silent.mp4");
        File finalMp4 = new File(cache, "hs_v2_" + id + "_avec_son.mp4");
        final VideoMp4Encoder[] encoder = new VideoMp4Encoder[1];
        final int[] frames = {0, 0, 0, 0}; // decoded, replaced, unchanged, pose incompatible
        final int[] dimensions = {0, 0};
        final String[] firstFailure = {""};
        boolean success = false;
        // MediaPipe travaille plus vite sur 640 px que sur une photo 1024 px.
        Bitmap resizedSource = null;
        Bitmap workingSource = source;
        if (Math.max(source.getWidth(), source.getHeight()) > 640) {
            float factor = 640f / Math.max(source.getWidth(), source.getHeight());
            resizedSource = Bitmap.createScaledBitmap(source,
                    Math.max(1, Math.round(source.getWidth() * factor)),
                    Math.max(1, Math.round(source.getHeight() * factor)), true);
            workingSource = resizedSource;
        }
        final Bitmap effectiveSource = workingSource;
        try {
            stage(listener, "Decodage, segmentation et pose 3D image par image...");
            VideoSequentialDecoder.Report decoded = VideoSequentialDecoder.decode(
                    context, originalVideo, MAX_SIDE, MAX_FRAMES,
                    (index, ptsUs, original) -> {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException("Traitement annule.");
                        }
                        if (encoder[0] == null) {
                            int width = original.getWidth() & ~1;
                            int height = original.getHeight() & ~1;
                            if (width < 128 || height < 128) {
                                throw new IOException("Image trop petite pour l'encodeur AVC.");
                            }
                            dimensions[0] = width;
                            dimensions[1] = height;
                            encoder[0] = new VideoMp4Encoder(silent, width, height,
                                    30, 2_000_000);
                        }

                        Bitmap targetCopy = null;
                        HeadSwapAlignmentEngine.Result alignment = null;
                        HeadSwapPoseCompositor.Prepared prepared = null;
                        Bitmap composite = null;
                        Bitmap adjusted = null;
                        try {
                            // Le decodeur recycle 'original' au retour du callback.
                            // L'alignement recoit donc une copie independante.
                            try {
                                targetCopy = original.copy(Bitmap.Config.ARGB_8888, false);
                                if (targetCopy == null) {
                                    throw new IOException("Copie de frame impossible.");
                                }
                                alignment = HeadSwapAlignmentEngine.prepare(context, effectiveSource, targetCopy);
                                targetCopy = null; // propriete transferee a alignment
                                HeadSwapPose3D.Match pose = HeadSwapPose3D.estimate(
                                        context, effectiveSource, alignment.targetFrame, alignment);
                                if (!pose.compatible) frames[3]++;
                                prepared = HeadSwapPoseCompositor.prepare(alignment, pose);
                                // Mode force2D=true : une pose differente ne masque plus
                                // silencieusement la tete source. Pas de reconstruction 3D.
                                HeadSwapCompositor.Settings settings =
                                        new HeadSwapCompositor.Settings(
                                                options.offsetX, options.offsetY,
                                                options.sizeFactor,
                                                pose.correctionRoll + options.extraAngle,
                                                options.featherPx, options.skinColorPercent);
                                composite = HeadSwapPoseCompositor.compose(
                                        prepared, settings, false, true);
                                frames[1]++;
                            } catch (Exception frameError) {
                                frames[2]++;
                                if (firstFailure[0].isEmpty()) {
                                    firstFailure[0] = frameError.getMessage() == null
                                            ? frameError.toString() : frameError.getMessage();
                                }
                                Log.w(TAG, "Image " + index + " conservee sans Head Swap", frameError);
                            }
                            Bitmap toEncode = composite != null ? composite : original;
                            if (toEncode.getWidth() != dimensions[0]
                                    || toEncode.getHeight() != dimensions[1]) {
                                adjusted = Bitmap.createScaledBitmap(toEncode,
                                        dimensions[0], dimensions[1], true);
                                toEncode = adjusted;
                            }
                            encoder[0].addFrame(toEncode, Math.max(0L, ptsUs));
                            frames[0]++;
                            if (listener != null && (frames[0] % 3 == 0 || frames[0] == 1)) {
                                listener.onFrame(frames[0], frames[1], frames[2]);
                            }
                        } finally {
                            if (adjusted != null && adjusted != original
                                    && !adjusted.isRecycled()) adjusted.recycle();
                            if (composite != null && !composite.isRecycled()) composite.recycle();
                            if (prepared != null) prepared.close();
                            if (alignment != null) alignment.close();
                            if (targetCopy != null && !targetCopy.isRecycled()) targetCopy.recycle();
                        }
                    });
            if (decoded.decodedFrames <= 0 || encoder[0] == null) {
                throw new IOException("Aucune image video decodee.");
            }
            if (frames[1] <= 0) {
                throw new IOException("Aucune tete n'a pu etre superposee. "
                        + "Essaie une photo source et une video avec un seul visage net. "
                        + (firstFailure[0].isEmpty() ? "" : "Detail : " + firstFailure[0]));
            }
            int encoded = encoder[0].getQueuedFrames();
            stage(listener, "Finalisation du MP4 H.264...");
            encoder[0].close();
            encoder[0] = null;
            stage(listener, "Recopie de la piste audio AAC d'origine...");
            VideoAudioRemuxer.Result remux = VideoAudioRemuxer.mergeOriginalAudio(
                    context, originalVideo, silent, finalMp4);
            success = true;
            return new Result(finalMp4, decoded.decodedFrames, frames[1], frames[2],
                    frames[3], encoded, remux.audioCopied, firstFailure[0],
                    dimensions[0], dimensions[1]);
        } finally {
            if (resizedSource != null && resizedSource != source
                    && !resizedSource.isRecycled()) resizedSource.recycle();
            if (encoder[0] != null) encoder[0].abort();
            if (silent.exists() && !silent.delete()) {
                Log.w(TAG, "Impossible de supprimer le MP4 temporaire silencieux.");
            }
            if (!success && finalMp4.exists() && !finalMp4.delete()) {
                Log.w(TAG, "Impossible de supprimer le MP4 temporaire final.");
            }
        }
    }

    private static void stage(ProgressListener listener, String message) {
        if (listener != null) listener.onStage(message);
    }

    private static long readDurationMs(Context context, Uri videoUri) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, videoUri);
            String value = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? -1 : Long.parseLong(value);
        } catch (Exception error) {
            throw new IOException("Lecture de la duree video impossible.", error);
        } finally {
            try {
                retriever.release();
            } catch (IOException releaseError) {
                Log.w(TAG, "Erreur lors de la liberation des metadonnees", releaseError);
            }
        }
    }
}
