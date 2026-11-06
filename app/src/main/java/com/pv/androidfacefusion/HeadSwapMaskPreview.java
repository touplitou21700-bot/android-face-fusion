package com.pv.androidfacefusion;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
 * Etapes 25 et 27 - diagnostic du masque et extraction de la tete source.
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

    /** Lance le diagnostic directement depuis le menu video, sans changer MainActivity. */
    public static void showForSource(
            AppCompatActivity activity,
            ExecutorService executor,
            Bitmap sourceBitmap) {
        start(activity, executor, () -> sourceBitmap);
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

    /**
     * Etape 27 : extraire cheveux + peau du visage dans un bitmap transparent.
     * Ne modifie pas la photo de MainActivity ni les videos MP4.
     */
    public static void showExtractionForSource(
            AppCompatActivity activity,
            ExecutorService executor,
            Bitmap sourceBitmap) {
        if (!active(activity)) return;
        if (executor == null || executor.isShutdown()) {
            message(activity, "Moteur indisponible", "Le traitement est arrete.");
            return;
        }
        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            message(activity, "Photo source manquante",
                    "Charge d'abord une photo source en mode Standard.");
            return;
        }

        final Bitmap copy;
        try {
            copy = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) throw new IllegalStateException("Copie impossible");
        } catch (Exception error) {
            message(activity, "Image invalide", error.getMessage());
            return;
        }

        AlertDialog waiting = new AlertDialog.Builder(activity)
                .setTitle("Head Swap - extraction source")
                .setMessage("Detection des cheveux et du visage, creation du contour transparent...")
                .setCancelable(false)
                .create();
        waiting.show();

        try {
            executor.execute(() -> {
                HeadSwapSourceExtractor.Result extraction = null;
                String error = null;
                try {
                    extraction = HeadSwapSourceExtractor.extract(
                            activity.getApplicationContext(), copy);
                } catch (Exception exception) {
                    error = exception.getMessage() == null
                            ? exception.toString() : exception.getMessage();
                } finally {
                    if (!copy.isRecycled()) copy.recycle();
                }
                final HeadSwapSourceExtractor.Result finalExtraction = extraction;
                final String finalError = error;
                activity.runOnUiThread(() -> {
                    waiting.dismiss();
                    if (!active(activity)) {
                        if (finalExtraction != null
                                && !finalExtraction.headBitmap.isRecycled()) {
                            finalExtraction.headBitmap.recycle();
                        }
                        return;
                    }
                    if (finalError != null) {
                        message(activity, "Extraction impossible", finalError);
                    } else if (finalExtraction != null) {
                        showExtractionPreview(activity, finalExtraction);
                    }
                });
            });
        } catch (Exception error) {
            waiting.dismiss();
            if (!copy.isRecycled()) copy.recycle();
            message(activity, "Lancement impossible", error.getMessage());
        }
    }

    private static void showExtractionPreview(
            AppCompatActivity activity, HeadSwapSourceExtractor.Result result) {
        final Bitmap checkerPreview;
        try {
            checkerPreview = buildCheckerPreview(result.headBitmap);
        } catch (Exception error) {
            if (!result.headBitmap.isRecycled()) result.headBitmap.recycle();
            message(activity, "Apercu impossible", error.getMessage());
            return;
        }
        int pad = Math.round(14f * activity.getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);

        TextView info = new TextView(activity);
        info.setText(String.format(Locale.FRANCE,
                "Tete extraite sur fond en damier (zones transparentes).\n"
                        + "Cheveux et peau du visage : %.1f%% de la photo.\n"
                        + "Extrait : %d x %d px (analyse a %d x %d).\n"
                        + "Prototype : oreilles, nuque et profil non garantis.",
                result.headPercent, result.cropWidth, result.cropHeight,
                result.sourceWidth, result.sourceHeight));
        layout.addView(info);

        ImageView image = new ImageView(activity);
        image.setImageBitmap(checkerPreview);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        layout.addView(image, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(350f * activity.getResources().getDisplayMetrics().density)));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Head Swap - tete source extraite")
                .setView(layout)
                .setPositiveButton("Fermer", null)
                .create();
        dialog.setOnDismissListener(d -> {
            image.setImageDrawable(null);
            if (!checkerPreview.isRecycled()) checkerPreview.recycle();
            if (!result.headBitmap.isRecycled()) result.headBitmap.recycle();
        });
        dialog.show();
    }

    /** Damier uniquement pour l'apercu ; l'extraction elle-meme reste RGBA. */
    private static Bitmap buildCheckerPreview(Bitmap transparentHead) {
        int w = transparentHead.getWidth();
        int h = transparentHead.getHeight();
        Bitmap display = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(display);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.rgb(223, 223, 223));
        int cell = 18;
        for (int y = 0; y < h; y += cell) {
            for (int x = 0; x < w; x += cell) {
                if (((x / cell) + (y / cell)) % 2 == 0) {
                    canvas.drawRect(x, y, Math.min(x + cell, w),
                            Math.min(y + cell, h), paint);
                }
            }
        }
        canvas.drawBitmap(transparentHead, 0f, 0f, null);
        return display;
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
