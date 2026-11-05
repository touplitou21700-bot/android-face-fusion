package com.pv.androidfacefusion;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * Android Face Fusion V2 - etape 9.
 *
 * Teste le face swap sur UNE image extraite au milieu d'une video MP4.
 * Ne modifie pas la video et ne produit pas encore de MP4.
 *
 * Les fichiers restent sur l'appareil ; les modeles doivent deja etre charges.
 */
public final class VideoPreviewHelper {

    private static final String TAG = "VideoPreviewHelper";
    private static final int MAX_FRAME_SIDE = 640;

    private VideoPreviewHelper() {
        // Classe utilitaire.
    }

    /**
     * Methode appelee sur le thread principal depuis MainActivity.
     * Le Bitmap source est copie avant de lancer le traitement en arriere-plan.
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
        if (activity.isFinishing() || activity.isDestroyed() || videoUri == null) {
            return;
        }

        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            showMessage(activity, "Photo source manquante",
                    "Choisis d'abord une photo dans Source (mode Standard), "
                            + "puis sélectionne la vidéo MP4.");
            return;
        }

        if (detector == null || embedder == null || processor == null) {
            showMessage(activity, "Modèles non prêts",
                    "Attends le chargement complet des modèles IA, puis réessaie.");
            return;
        }

        // L'utilisateur peut changer ou réinitialiser la photo pendant le calcul.
        // Une copie distincte empêche un accès à un Bitmap recyclé.
        final Bitmap sourceCopy;
        try {
            sourceCopy = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (sourceCopy == null) {
                throw new IllegalStateException("Copie de la source impossible");
            }
        } catch (RuntimeException error) {
            showMessage(activity, "Photo source indisponible", error.getMessage());
            return;
        }

        Toast.makeText(activity, "Traitement de l'aperçu vidéo...",
                Toast.LENGTH_LONG).show();

        executor.execute(() -> {
            Bitmap frame = null;
            Bitmap swappedFrame = null;
            VideoFaceSwapEngine engine = null;

            try (VideoFrameReader reader = new VideoFrameReader(activity, videoUri)) {
                long durationMs = reader.getDurationMs();
                int videoWidth = reader.getWidth();
                int videoHeight = reader.getHeight();

                // Un seul Bitmap est extrait pour ce test.
                frame = reader.getFrameAtMs(durationMs / 2L, MAX_FRAME_SIDE);
                if (frame == null) {
                    throw new IllegalStateException("Impossible d'extraire une image vidéo");
                }

                engine = new VideoFaceSwapEngine(detector, embedder, processor);
                engine.prepareSource(sourceCopy);
                swappedFrame = engine.swapFrame(frame);
                if (swappedFrame == null) {
                    throw new IllegalStateException("Le moteur IA n'a pas produit d'image");
                }

                final Bitmap originalForDialog = frame;
                final Bitmap resultForDialog = swappedFrame;
                final String metadata = String.format(Locale.FRANCE,
                        "Durée : %.1f s  •  Résolution : %d × %d\n"
                                + "Image traitée : milieu de la vidéo\n"
                                + "Test sur une seule image ; aucun MP4 n'est créé.",
                        durationMs / 1000.0, videoWidth, videoHeight);

                // Les deux Bitmap sont désormais pris en charge par le dialogue.
                frame = null;
                swappedFrame = null;

                activity.runOnUiThread(() -> showComparison(
                        activity, metadata, originalForDialog, resultForDialog));

            } catch (Exception error) {
                Log.e(TAG, "Erreur de traitement sur l'image vidéo", error);
                final String description = error.getMessage() != null
                        ? error.getMessage() : error.getClass().getSimpleName();
                activity.runOnUiThread(() -> showMessage(activity,
                        "Test vidéo impossible", description));

            } finally {
                if (engine != null) {
                    engine.clearSource();
                }
                recycle(frame);
                recycle(swappedFrame);
                recycle(sourceCopy);
            }
        });
    }

    /** Affiche l'image originale et l'image traitée. */
    private static void showComparison(
            AppCompatActivity activity,
            String metadata,
            Bitmap original,
            Bitmap swapped
    ) {
        if (activity.isFinishing() || activity.isDestroyed()) {
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
        before.setText("AVANT — image extraite");
        before.setPadding(0, padding, 0, dp(activity, 6));
        content.addView(before);

        ImageView originalView = addPreview(activity, content, original);

        TextView after = new TextView(activity);
        after.setText("APRÈS — face swap IA");
        after.setPadding(0, padding, 0, dp(activity, 6));
        content.addView(after);

        ImageView swappedView = addPreview(activity, content, swapped);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("V2 — Essai sur une image vidéo")
                .setView(scrollView)
                .setPositiveButton("Fermer", null)
                .create();

        dialog.setOnDismissListener(unused -> {
            originalView.setImageDrawable(null);
            swappedView.setImageDrawable(null);
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
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void showMessage(
            AppCompatActivity activity,
            String title,
            String message
    ) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
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
