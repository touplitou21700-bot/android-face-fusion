package com.pv.androidfacefusion;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Etape 25 - bouton autonome pour diagnostiquer cheveux et peau du visage.
 * Ne change ni le moteur Face Swap V2, ni la resolution des MP4.
 */
public final class HeadSwapMaskPreview {
    private HeadSwapMaskPreview() { }

    public static void addButton(
            AppCompatActivity activity,
            ViewGroup container,
            ExecutorService executor,
            Supplier<Bitmap> sourceSupplier) {
        MaterialButton button = new MaterialButton(activity);
        button.setText("Test Head Swap - masque tete");
        button.setOnClickListener(v -> start(activity, executor, sourceSupplier));
        container.addView(button);
    }

    private static void start(AppCompatActivity activity,
                              ExecutorService executor,
                              Supplier<Bitmap> sourceSupplier) {
        if (!active(activity)) return;
        if (executor == null || executor.isShutdown()) {
            message(activity, "Moteur indisponible", "Le traitement est arrete.");
            return;
        }
        Bitmap source = sourceSupplier == null ? null : sourceSupplier.get();
        if (source == null || source.isRecycled()) {
            message(activity, "Photo source manquante",
                    "Charge d'abord une photo source en mode Standard.");
            return;
        }

        final Bitmap copy;
        try {
            copy = source.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) throw new IllegalStateException("Copie impossible");
        } catch (Exception error) {
            message(activity, "Image invalide", error.getMessage());
            return;
        }

        AlertDialog waiting = new AlertDialog.Builder(activity)
                .setTitle("Test du masque de tete")
                .setMessage("Analyse locale des cheveux et du visage...")
                .setCancelable(false)
                .create();
        waiting.show();

        try {
            executor.execute(() -> {
                HeadSwapMaskSegmenter.Result result = null;
                String error = null;
                try {
                    result = HeadSwapMaskSegmenter.analyze(
                            activity.getApplicationContext(), copy);
                } catch (Exception exception) {
                    error = exception.getMessage();
                    if (error == null) error = exception.toString();
                } finally {
                    if (!copy.isRecycled()) copy.recycle();
                }

                final HeadSwapMaskSegmenter.Result finalResult = result;
                final String finalError = error;
                activity.runOnUiThread(() -> {
                    waiting.dismiss();
                    if (!active(activity)) {
                        if (finalResult != null && !finalResult.preview.isRecycled()) {
                            finalResult.preview.recycle();
                        }
                        return;
                    }
                    if (finalError != null) {
                        message(activity, "Segmentation impossible", finalError
                                + "\nVerifie le modele .tflite dans assets et la dependance MediaPipe.");
                    } else if (finalResult != null) {
                        showPreview(activity, finalResult);
                    }
                });
            });
        } catch (Exception error) {
            waiting.dismiss();
            if (!copy.isRecycled()) copy.recycle();
            message(activity, "Lancement impossible", error.getMessage());
        }
    }

    private static void showPreview(AppCompatActivity activity,
                                    HeadSwapMaskSegmenter.Result result) {
        int pad = Math.round(14f * activity.getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);

        TextView label = new TextView(activity);
        label.setText(String.format(Locale.FRANCE,
                "Rose = cheveux | Bleu = peau du visage\n"
                        + "Zone detectee : %.1f%% de cheveux, %.1f%% de visage.\n"
                        + "Le contour n'inclut pas encore toutes les oreilles et la nuque.",
                result.hairPercent, result.facePercent));
        layout.addView(label);

        ImageView image = new ImageView(activity);
        image.setImageBitmap(result.preview);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(330f * activity.getResources().getDisplayMetrics().density));
        layout.addView(image, params);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Head Swap - masque source")
                .setView(layout)
                .setPositiveButton("Fermer", null)
                .create();
        dialog.setOnDismissListener(d -> {
            image.setImageDrawable(null);
            if (!result.preview.isRecycled()) result.preview.recycle();
        });
        dialog.show();
    }

    private static boolean active(AppCompatActivity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void message(AppCompatActivity activity,
                                String title, String body) {
        if (active(activity)) {
            new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(body == null ? "Erreur inconnue" : body)
                    .setPositiveButton("OK", null)
                    .show();
        }
    }
}
