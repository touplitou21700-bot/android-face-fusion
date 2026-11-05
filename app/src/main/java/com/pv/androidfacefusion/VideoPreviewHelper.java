package com.pv.androidfacefusion;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * Android Face Fusion V2 - aperçu local d'une video.
 *
 * Lit les metadonnees avec VideoFrameReader et affiche une seule image,
 * extraite vers le milieu de la video. Ne realise aucun face swap.
 */
public final class VideoPreviewHelper {

    private static final String TAG = "VideoPreviewHelper";
    private static final int MAX_PREVIEW_SIDE = 640;

    private VideoPreviewHelper() {
        // Classe utilitaire : pas d'instance.
    }

    /**
     * Appeler depuis le thread principal apres selection d'une video.
     * Le travail de decodage se fait dans l'executor fourni.
     */
    public static void analyze(
            AppCompatActivity activity,
            Uri videoUri,
            ExecutorService executor
    ) {
        if (videoUri == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        Toast.makeText(
                activity,
                "Lecture de l'aperçu vidéo...",
                Toast.LENGTH_SHORT
        ).show();

        executor.execute(() -> {
            Bitmap preview = null;
            try (VideoFrameReader reader = new VideoFrameReader(activity, videoUri)) {
                long durationMs = reader.getDurationMs();
                int width = reader.getWidth();
                int height = reader.getHeight();
                int rotation = reader.getRotationDegrees();

                preview = reader.getFrameAtMs(durationMs / 2, MAX_PREVIEW_SIDE);
                if (preview == null) {
                    throw new IllegalStateException("Aucune image extraite");
                }

                final Bitmap displayedFrame = preview;
                final String description = String.format(
                        Locale.FRANCE,
                        "Durée : %.1f s\nRésolution : %d × %d\nRotation : %d°\n\n"
                                + "Aperçu extrait au milieu de la vidéo."
                                + "\nLe face swap vidéo sera ajouté ensuite.",
                        durationMs / 1000.0,
                        width,
                        height,
                        rotation
                );

                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        if (!displayedFrame.isRecycled()) {
                            displayedFrame.recycle();
                        }
                        return;
                    }

                    int padding = (int) (16 * activity.getResources()
                            .getDisplayMetrics().density + 0.5f);

                    LinearLayout layout = new LinearLayout(activity);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setPadding(padding, padding, padding, padding);

                    TextView details = new TextView(activity);
                    details.setText(description);
                    layout.addView(details);

                    ImageView imageView = new ImageView(activity);
                    imageView.setAdjustViewBounds(true);
                    imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    imageView.setImageBitmap(displayedFrame);
                    layout.addView(imageView, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    ));

                    AlertDialog dialog = new AlertDialog.Builder(activity)
                            .setTitle("Vidéo MP4 — aperçu V2")
                            .setView(layout)
                            .setPositiveButton("Fermer", null)
                            .create();

                    dialog.setOnDismissListener(ignored -> {
                        imageView.setImageDrawable(null);
                        if (!displayedFrame.isRecycled()) {
                            displayedFrame.recycle();
                        }
                    });
                    dialog.show();
                });
            } catch (Exception error) {
                if (preview != null && !preview.isRecycled()) {
                    preview.recycle();
                }
                Log.e(TAG, "Lecture vidéo impossible", error);
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        new AlertDialog.Builder(activity)
                                .setTitle("Erreur de lecture vidéo")
                                .setMessage(error.getMessage())
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            }
        });
    }
}
