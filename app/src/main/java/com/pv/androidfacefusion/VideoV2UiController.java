package com.pv.androidfacefusion;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * Android Face Fusion V2 - ETAPE 31 : ajout du test de fusion sur une image cible.
 *
 * Raccordement de l'interface video au pipeline IA + MP4 + audio des
 * etapes 12 a 17, tout en conservant l'apercu des etapes 9 a 11.
 *
 * Premier test seulement : video locale MP4 <= 3,2 secondes,
 * 150 images maximum, cote d'image de sortie <= 480 pixels.
 * Les videos plus longues sont REFUSEES et non decoupees automatiquement.
 *
 * Le nouveau mode 7 produit un Head Swap VIDEO experimental de 5 secondes.
 * Ne garantit pas la compatibilite de tous les codecs Android.
 * Ne pas publier de faux contenus intimes de personnes non consentantes.
 */
public final class VideoV2UiController {

    private static final String TAG = "VideoV2UiController";
    private static final long MAX_TEST_DURATION_MS = 3_200L;

    private VideoV2UiController() { }

    /** A appeler depuis le launcher video de MainActivity, sur le thread UI. */
    public static void open(
            AppCompatActivity activity,
            Uri videoUri,
            ExecutorService executor,
            Bitmap sourceBitmap,
            FaceDetector detector,
            FaceEmbedder embedder,
            FaceFusionProcessor processor
    ) {
        if (!isActive(activity) || videoUri == null) return;
        if (executor == null || executor.isShutdown()) {
            showMessage(activity, "Moteur indisponible", "Le traitement est arrete.");
            return;
        }
        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            showMessage(activity, "Photo source manquante",
                    "Charge d'abord une photo Source dans le mode Standard.");
            return;
        }
        if (detector == null || embedder == null || processor == null) {
            showMessage(activity, "Modeles IA non charges",
                    "Attends la fin du chargement des trois modeles.");
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle("Face Fusion V2 - video")
                .setItems(new String[]{
                        "Apercus IA (1 ou 6 images)",
                        "Creer un MP4 de test (2 a 3 s)",
                        "Test Head Swap - masque tete (photo source)",
                        "Test Head Swap - extraction tete source",
                        "Test Head Swap - alignement sur video",
                        "Test Head Swap - fusion sur image cible",
                        "Creer un MP4 Head Swap (max 5 s)"
                }, (dialog, which) -> {
                    if (which == 0) {
                        VideoPreviewHelper.analyze(activity, videoUri, executor,
                                sourceBitmap, detector, embedder, processor);
                    } else if (which == 1) {
                        startMp4Test(activity, videoUri, executor, sourceBitmap,
                                detector, embedder, processor);
                    } else if (which == 2) {
                        HeadSwapMaskPreview.showForSource(activity, executor, sourceBitmap);
                    } else if (which == 3) {
                        HeadSwapMaskPreview.showExtractionForSource(
                                activity, executor, sourceBitmap);
                    } else if (which == 4) {
                        HeadSwapAlignmentPreview.showForVideo(
                                activity, executor, sourceBitmap, videoUri);
                    } else if (which == 5) {
                        HeadSwapCompositePreview.showForVideo(
                                activity, executor, sourceBitmap, videoUri);
                    } else if (which == 6) {
                        HeadSwapVideoTestUi.start(activity, videoUri,
                                executor, sourceBitmap);
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private static void startMp4Test(
            AppCompatActivity activity,
            Uri videoUri,
            ExecutorService executor,
            Bitmap sourceBitmap,
            FaceDetector detector,
            FaceEmbedder embedder,
            FaceFusionProcessor processor
    ) {
        // Faire une copie sur le thread UI : la source de MainActivity peut
        // etre reinitialisee pendant la creation du MP4.
        final Bitmap sourceCopy;
        try {
            if (sourceBitmap == null || sourceBitmap.isRecycled()) {
                throw new IllegalArgumentException("Photo source indisponible");
            }
            sourceCopy = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (sourceCopy == null) {
                throw new IOException("Copie du visage source impossible");
            }
        } catch (Exception error) {
            showMessage(activity, "Photo source invalide", error.getMessage());
            return;
        }

        final int pad = (int) (16 * activity.getResources()
                .getDisplayMetrics().density + .5f);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView info = new TextView(activity);
        info.setText("Lecture du fichier video...\n"
                + "Garde l'application ouverte pendant le test.\n"
                + "Limite : 3,2 secondes / 150 images / 480 pixels.");
        box.addView(info);

        ProgressBar progress = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setMax(VideoMp4WithAudioPipeline.FIRST_TEST_MAX_FRAMES);
        box.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle("Creation MP4 local - V2")
                .setView(box)
                .setCancelable(false)
                .create();
        progressDialog.show();

        executor.execute(() -> {
            try {
                long durationMs = getDurationMs(activity, videoUri);
                if (durationMs <= 0L || durationMs > MAX_TEST_DURATION_MS) {
                    throw new IOException("Pour ce premier test, selectionne une video "
                            + "de 2 a 3 secondes maximum (duree detectee : "
                            + String.format(Locale.FRANCE, "%.1f s", durationMs / 1000.0)
                            + "). Le composant ne coupe pas les videos longues.");
                }

                VideoMp4WithAudioPipeline.Result result =
                        VideoMp4WithAudioPipeline.processFirstTest(
                                activity, videoUri, sourceCopy,
                                detector, embedder, processor,
                                new VideoMp4WithAudioPipeline.ProgressListener() {
                                    @Override
                                    public void onStage(String stage) {
                                        activity.runOnUiThread(() -> {
                                            if (isActive(activity)) info.setText(stage);
                                        });
                                    }

                                    @Override
                                    public void onFrameProgress(
                                            int completedFrames, long presentationTimeUs) {
                                        if (completedFrames % 5 != 0 && completedFrames != 1) {
                                            return;
                                        }
                                        activity.runOnUiThread(() -> {
                                            if (isActive(activity)) {
                                                progress.setIndeterminate(false);
                                                progress.setProgress(completedFrames);
                                                info.setText("Images traitees : "
                                                        + completedFrames + " / max 150");
                                            }
                                        });
                                    }
                                });

                // Le pipeline ecrit d'abord dans le dossier prive de V2.
                // Publier une COPIE du MP4 final dans la galerie Samsung.
                Uri galleryUri = copyToGallery(activity, result.finalMp4);
                String summary = "MP4 enregistre dans la galerie (Films / FaceFusionV2).\n\n"
                        + "Images lues : " + result.framesDecoded
                        + "\nVisages remplaces : " + result.framesSwapped
                        + "\nImages encodees : " + result.framesEncoded
                        + "\nResolution : " + result.outputWidth + " x " + result.outputHeight
                        + "\nAudio original copie : " + (result.audioCopied ? "oui" : "non");

                activity.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (!isActive(activity)) return;
                    new AlertDialog.Builder(activity)
                            .setTitle("Test MP4 termine")
                            .setMessage(summary)
                            .setPositiveButton("Lire la video", (dialog, which) ->
                                    openVideo(activity, galleryUri))
                            .setNegativeButton("Fermer", null)
                            .show();
                });
            } catch (Exception error) {
                Log.e(TAG, "Echec du test MP4", error);
                String message = error.getMessage() == null
                        ? error.toString() : error.getMessage();
                activity.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    showMessage(activity, "Test MP4 interrompu", message);
                });
            } finally {
                if (!sourceCopy.isRecycled()) sourceCopy.recycle();
            }
        });
    }

    private static long getDurationMs(AppCompatActivity activity, Uri videoUri)
            throws IOException {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(activity, videoUri);
            String value = r.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? -1L : Long.parseLong(value);
        } catch (Exception e) {
            throw new IOException("Duree de la video illisible", e);
        } finally {
            r.release();
        }
    }

    private static Uri copyToGallery(AppCompatActivity activity, File mp4)
            throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("Export galerie prevu pour Android 10 ou plus");
        }
        if (mp4 == null || !mp4.isFile() || mp4.length() == 0) {
            throw new IOException("MP4 produit vide ou introuvable");
        }
        ContentResolver resolver = activity.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME,
                "FaceFusionV2_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/FaceFusionV2");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Impossible de creer le fichier galerie");
        boolean success = false;
        try (FileInputStream input = new FileInputStream(mp4);
             OutputStream output = resolver.openOutputStream(uri, "w")) {
            if (output == null) throw new IOException("Ecriture galerie impossible");
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) >= 0) {
                if (n > 0) output.write(buffer, 0, n);
            }
            output.flush();
            success = true;
        } finally {
            if (!success) resolver.delete(uri, null, null);
        }
        values.clear();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    private static void openVideo(AppCompatActivity activity, Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception error) {
            showMessage(activity, "Video enregistree",
                    "Ouvre Galerie ou Mes fichiers > Films > FaceFusionV2.");
        }
    }

    private static boolean isActive(AppCompatActivity activity) {
        return activity != null && !activity.isFinishing()
                && !activity.isDestroyed();
    }

    private static void showMessage(
            AppCompatActivity activity, String title, String message) {
        if (!isActive(activity)) return;
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message == null ? "Erreur inconnue" : message)
                .setPositiveButton("OK", null)
                .show();
    }
}
