package com.pv.androidfacefusion;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android Face Fusion V2 - etape 11.
 *
 * Conserve l'essai IA sur une seule image (etape 9) et ajoute un test
 * sur un petit echantillon de la video (etape 10) avec progression.
 *
 * AUCUN MP4 n'est produit : MediaMetadataRetriever ne garantit pas
 * un decodage image par image a la cadence de la video.
 */
public final class VideoPreviewHelper {

    private static final String TAG = "VideoPreviewHelper";
    private static final int SINGLE_MAX_SIDE = 640;
    private static final int BATCH_FPS = 1;
    private static final int BATCH_MAX_FRAMES = 6;
    private static final int BATCH_MAX_SIDE = 480;

    private VideoPreviewHelper() {}

    /**
     * Point d'entree deja utilise par MainActivity depuis l'etape 9.
     * Aucun changement de MainActivity.java n'est necessaire.
     */
    public static void analyze(
            AppCompatActivity activity,
            Uri videoUri,
            ExecutorService executor,
            Bitmap sourceBitmap,
            FaceDetector detector,
            FaceEmbedder embedder,
            FaceFusionProcessor processor
    ) {
        if (!isActive(activity) || videoUri == null) {
            return;
        }
        if (executor == null || executor.isShutdown()) {
            showMessage(activity, "Traitement indisponible",
                    "Le moteur d'execution est arrete.");
            return;
        }
        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            showMessage(activity, "Photo source manquante",
                    "Charge d'abord une photo Source dans le mode Standard.");
            return;
        }
        if (detector == null || embedder == null || processor == null) {
            showMessage(activity, "Modeles IA non prets",
                    "Attends la fin du chargement des modeles.");
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle("Face Fusion V2 - test video")
                .setItems(new String[] {
                        "Apercu IA sur 1 image",
                        "Test IA sur 6 images (progression)"
                }, (dialog, selection) -> {
                    if (selection == 0) {
                        runSinglePreview(activity, videoUri, executor,
                                sourceBitmap, detector, embedder, processor);
                    } else {
                        runBatchPreview(activity, videoUri, executor,
                                sourceBitmap, detector, embedder, processor);
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private static void runSinglePreview(
            AppCompatActivity activity,
            Uri videoUri,
            ExecutorService executor,
            Bitmap sourceBitmap,
            FaceDetector detector,
            FaceEmbedder embedder,
            FaceFusionProcessor processor
    ) {
        final Bitmap sourceCopy = copySource(activity, sourceBitmap);
        if (sourceCopy == null) return;

        executor.execute(() -> {
            Bitmap original = null;
            Bitmap swapped = null;
            VideoFaceSwapEngine engine = null;
            try (VideoFrameReader reader = new VideoFrameReader(activity, videoUri)) {
                long durationMs = reader.getDurationMs();
                original = reader.getFrameAtMs(durationMs / 2L, SINGLE_MAX_SIDE);
                if (original == null) {
                    throw new IllegalStateException("Image video indisponible");
                }

                engine = new VideoFaceSwapEngine(detector, embedder, processor);
                engine.prepareSource(sourceCopy);
                swapped = engine.swapFrame(original);
                if (swapped == null) {
                    throw new IllegalStateException("Pas de resultat IA");
                }

                String metadata = String.format(Locale.FRANCE,
                        "Duree : %.1f s  |  Resolution : %d x %d\n"
                                + "Image situee au milieu de la video.\n"
                                + "Aucun MP4 n'a ete cree.",
                        durationMs / 1000.0, reader.getWidth(), reader.getHeight());
                final Bitmap originalForUi = original;
                final Bitmap swappedForUi = swapped;
                original = null;
                swapped = null;
                activity.runOnUiThread(() ->
                        showComparison(activity, metadata, originalForUi, swappedForUi));
            } catch (Exception error) {
                Log.e(TAG, "Essai sur une image impossible", error);
                String message = error.getMessage();
                activity.runOnUiThread(() -> showMessage(activity,
                        "Essai video impossible",
                        message == null ? "Erreur inconnue" : message));
            } finally {
                if (engine != null) engine.clearSource();
                recycle(original);
                recycle(swapped);
                recycle(sourceCopy);
            }
        });
    }

    private static void runBatchPreview(
            AppCompatActivity activity,
            Uri videoUri,
            ExecutorService executor,
            Bitmap sourceBitmap,
            FaceDetector detector,
            FaceEmbedder embedder,
            FaceFusionProcessor processor
    ) {
        final Bitmap sourceCopy = copySource(activity, sourceBitmap);
        if (sourceCopy == null) return;

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final int padding = dp(activity, 18);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView state = new TextView(activity);
        state.setText("Lecture et analyse locale des images video...");
        content.addView(state);

        ProgressBar bar = new ProgressBar(
                activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(false);
        bar.setMax(BATCH_MAX_FRAMES);
        bar.setProgress(0);
        content.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle("Test IA - plusieurs images")
                .setView(content)
                .setNegativeButton("Annuler", (dialog, which) -> cancelled.set(true))
                .setCancelable(false)
                .create();
        progressDialog.show();

        // Un seul avant/apres est conserve en RAM, au milieu de l'echantillon.
        // Les autres images sont liberees par VideoFrameBatchProcessor.
        final Bitmap[] preview = new Bitmap[2];

        executor.execute(() -> {
            try {
                VideoFrameBatchProcessor.Report report =
                        VideoFrameBatchProcessor.process(
                                activity,
                                videoUri,
                                sourceCopy,
                                detector,
                                embedder,
                                processor,
                                BATCH_FPS,
                                BATCH_MAX_FRAMES,
                                BATCH_MAX_SIDE,
                                new VideoFrameBatchProcessor.Listener() {
                                    @Override
                                    public void onFrame(
                                            int index, int total, long timestampMs,
                                            Bitmap original, Bitmap processed,
                                            boolean swapped) throws Exception {
                                        if (cancelled.get()) {
                                            throw new InterruptedException("Test annule");
                                        }
                                        if (index == total / 2) {
                                            preview[0] = original.copy(
                                                    Bitmap.Config.ARGB_8888, false);
                                            preview[1] = processed.copy(
                                                    Bitmap.Config.ARGB_8888, false);
                                            if (preview[0] == null || preview[1] == null) {
                                                throw new IllegalStateException(
                                                        "Impossible de copier l'apercu");
                                            }
                                        }
                                    }

                                    @Override
                                    public void onProgress(int completed, int total) {
                                        activity.runOnUiThread(() -> {
                                            if (isActive(activity) && !cancelled.get()
                                                    && progressDialog.isShowing()) {
                                                bar.setMax(total);
                                                bar.setProgress(completed);
                                                state.setText("Images analysees : "
                                                        + completed + " / " + total);
                                            }
                                        });
                                    }
                                });

                final Bitmap originalForUi = preview[0];
                final Bitmap resultForUi = preview[1];
                preview[0] = null;
                preview[1] = null;

                final String message = String.format(Locale.FRANCE,
                        "Duree : %.1f s\nImages examinees : %d\n"
                                + "Visages remplaces : %d\nImages sans visage : %d\n"
                                + "Apercu : image centrale du test.\n"
                                + "Echantillonnage seulement : aucun MP4 cree.",
                        report.durationMs / 1000.0,
                        report.imagesProcessed,
                        report.imagesSwapped,
                        report.imagesWithoutFace);

                activity.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (cancelled.get()) {
                        recycle(originalForUi);
                        recycle(resultForUi);
                    } else {
                        showComparison(activity, message, originalForUi, resultForUi);
                    }
                });
            } catch (Exception error) {
                Log.e(TAG, "Echec du test video par lots", error);
                String message = error.getMessage();
                activity.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (!cancelled.get()) {
                        showMessage(activity, "Test video interrompu",
                                message == null ? "Erreur inconnue" : message);
                    }
                });
            } finally {
                recycle(preview[0]);
                recycle(preview[1]);
                recycle(sourceCopy);
            }
        });
    }

    private static Bitmap copySource(AppCompatActivity activity, Bitmap source) {
        try {
            if (source == null || source.isRecycled()) {
                throw new IllegalStateException("Photo source indisponible");
            }
            Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) {
                throw new IllegalStateException("Copie de la source impossible");
            }
            return copy;
        } catch (Exception error) {
            showMessage(activity, "Photo source indisponible", error.getMessage());
            return null;
        }
    }

    private static void showComparison(
            AppCompatActivity activity,
            String metadata,
            Bitmap original,
            Bitmap swapped
    ) {
        if (!isActive(activity)) {
            recycle(original);
            recycle(swapped);
            return;
        }
        int padding = dp(activity, 16);
        ScrollView scrollView = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        scrollView.addView(content);

        TextView info = new TextView(activity);
        info.setText(metadata);
        content.addView(info);
        TextView before = new TextView(activity);
        before.setText("AVANT - image video");
        before.setPadding(0, padding, 0, dp(activity, 6));
        content.addView(before);
        ImageView beforeView = addPreview(activity, content, original);

        TextView after = new TextView(activity);
        after.setText("APRES - face swap IA");
        after.setPadding(0, padding, 0, dp(activity, 6));
        content.addView(after);
        ImageView afterView = addPreview(activity, content, swapped);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("V2 - Comparatif video")
                .setView(scrollView)
                .setPositiveButton("Fermer", null)
                .create();
        dialog.setOnDismissListener(unused -> {
            beforeView.setImageDrawable(null);
            afterView.setImageDrawable(null);
            recycle(original);
            recycle(swapped);
        });
        dialog.show();
    }

    private static ImageView addPreview(
            AppCompatActivity activity,
            LinearLayout parent,
            Bitmap bitmap
    ) {
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        image.setImageBitmap(bitmap);
        parent.addView(image, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 220)));
        return image;
    }

    private static int dp(AppCompatActivity activity, int value) {
        return (int) (value * activity.getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private static boolean isActive(AppCompatActivity activity) {
        return activity != null && !activity.isFinishing()
                && !activity.isDestroyed();
    }

    private static void showMessage(
            AppCompatActivity activity,
            String title,
            String message
    ) {
        if (!isActive(activity)) return;
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message == null ? "Erreur inconnue" : message)
                .setPositiveButton("OK", null)
                .show();
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
