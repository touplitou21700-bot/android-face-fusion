package com.pv.androidfacefusion;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * Etape 33 - alignement par reperes du visage + verification de pose 3D.
 * Pose pour limiter les collages incoherents, sans reconstruire de nouvelles vues.
 * Ne change pas la video source et n'encode aucun MP4.
 */
public final class HeadSwapCompositePreview {

    private static final String TAG = "HeadSwapComposite";
    private static final int MAX_FRAME_SIDE = 640;

    private HeadSwapCompositePreview() { }

    public static void showForVideo(AppCompatActivity activity,
                                    ExecutorService executor,
                                    Bitmap sourceBitmap, Uri videoUri) {
        if (!active(activity) || videoUri == null || executor == null
                || executor.isShutdown()) return;
        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            message(activity, "Photo source manquante",
                    "Charge d'abord une photo source dans le mode Standard.");
            return;
        }
        final Bitmap sourceCopy;
        try {
            sourceCopy = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (sourceCopy == null) throw new IOException("Copie de la photo impossible.");
        } catch (Exception error) {
            message(activity, "Photo source invalide", error.getMessage());
            return;
        }

        int pad = dp(activity, 18);
        LinearLayout loadingBox = new LinearLayout(activity);
        loadingBox.setOrientation(LinearLayout.VERTICAL);
        loadingBox.setPadding(pad, pad, pad, pad);
        TextView loading = new TextView(activity);
        loading.setText("Lecture d'une image de la video, extraction des deux tetes, "
                + "puis preparation du fondu.\nAucun MP4 n'est modifie.");
        loadingBox.addView(loading);
        loadingBox.addView(new ProgressBar(activity));
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("Head Swap - fusion image")
                .setView(loadingBox)
                .setCancelable(false)
                .create();
        progress.show();

        try {
            executor.execute(() -> {
                Bitmap target = null;
                HeadSwapAlignmentEngine.Result alignment = null;
                HeadSwapPoseCompositor.Prepared prepared = null;
                try {
                    target = firstVideoFrame(activity, videoUri);
                    alignment = HeadSwapAlignmentEngine.prepare(
                            activity.getApplicationContext(), sourceCopy, target);
                    // prepare() transfere la propriete de target a alignment.
                    target = null;
                    // Reperes 3D calcules uniquement sur cette image fixe.
                    // Pose trop differente : on affiche la cible, sans faux collage.
                    HeadSwapPose3D.Match pose = HeadSwapPose3D.estimate(
                            activity.getApplicationContext(), sourceCopy,
                            alignment.targetFrame, alignment);
                    prepared = HeadSwapPoseCompositor.prepare(alignment, pose);
                    HeadSwapAlignmentEngine.Result readyAlignment = alignment;
                    HeadSwapPoseCompositor.Prepared readyPrepared = prepared;
                    HeadSwapPose3D.Match readyPose = pose;
                    activity.runOnUiThread(() -> {
                        progress.dismiss();
                        if (!active(activity)) {
                            readyPrepared.close();
                            readyAlignment.close();
                            return;
                        }
                        try {
                            showDialog(activity, executor, readyAlignment,
                                    readyPrepared, readyPose);
                        } catch (Exception displayError) {
                            readyPrepared.close();
                            readyAlignment.close();
                            message(activity, "Apercu indisponible", displayError.getMessage());
                        }
                    });
                } catch (Exception error) {
                    Log.e(TAG, "Echec de la composition", error);
                    if (prepared != null) prepared.close();
                    if (alignment != null) alignment.close();
                    String details = error.getMessage() == null
                            ? error.toString() : error.getMessage();
                    activity.runOnUiThread(() -> {
                        progress.dismiss();
                        message(activity, "Fusion impossible", details
                                + "\n\nChoisis une video avec une seule tete de face.");
                    });
                } finally {
                    if (target != null && !target.isRecycled()) target.recycle();
                    if (!sourceCopy.isRecycled()) sourceCopy.recycle();
                }
            });
        } catch (RuntimeException rejected) {
            progress.dismiss();
            if (!sourceCopy.isRecycled()) sourceCopy.recycle();
            message(activity, "Traitement indisponible", rejected.getMessage());
        }
    }

    private static void showDialog(AppCompatActivity activity, ExecutorService executor,
                                   HeadSwapAlignmentEngine.Result alignment,
                                   HeadSwapPoseCompositor.Prepared prepared,
                                   HeadSwapPose3D.Match pose) {
        int pad = dp(activity, 12);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView info = new TextView(activity);
        info.setText("Etape 33 : repères faciaux 3D et alignement sur les yeux. "
                + "Une seule image fixe est modifiee, jamais le MP4.\n"
                + "La pose 3D n'invente pas les cheveux ni le profil cache. "
                + "Pour de meilleurs resultats, prends une source orientee comme la cible.");
        column.addView(info);

        CompositeView preview = new CompositeView(activity, prepared);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 290));
        imageParams.topMargin = dp(activity, 8);
        column.addView(preview, imageParams);

        TextView poseText = new TextView(activity);
        poseText.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
        poseText.setText(String.format(Locale.FRANCE,
                "Pose source : yaw %+.0f°, pitch %+.0f°, inclinaison %+.0f°\n"
                + "Pose cible : yaw %+.0f°, pitch %+.0f°, inclinaison %+.0f°\n"
                + "Alignement initial par les yeux : rotation %+.0f°\n%s",
                pose.sourceYaw, pose.sourcePitch, pose.sourceRoll,
                pose.targetYaw, pose.targetPitch, pose.targetRoll,
                pose.correctionRoll,
                pose.compatible ? "Poses compatibles (estimation)."
                        : "ATTENTION : " + pose.warning));
        column.addView(poseText);

        TextView values = new TextView(activity);
        values.setPadding(0, dp(activity, 8), 0, dp(activity, 5));
        column.addView(values);

        int initialAngle = Math.max(-90, Math.min(90,
                Math.round(pose.correctionRoll)));
        SeekBar x = slider(activity, column, "Ajustement horizontal X", 100, 200);
        SeekBar y = slider(activity, column, "Ajustement vertical Y", 100, 200);
        SeekBar size = slider(activity, column, "Taille relative (base sur les yeux)", 100, 200);
        SeekBar angle = slider(activity, column, "Angle 2D (-90 a +90 degres)",
                90 + initialAngle, 180);
        SeekBar feather = slider(activity, column,
                "Adoucissement contour (0 = masque original)", 8, 24);
        SeekBar color = slider(activity, column,
                "Harmonisation de la peau (0 = desactivee)", 60, 100);
        CheckBox trimNeck = new CheckBox(activity);
        trimNeck.setText("Eviter les debordements sous le menton");
        trimNeck.setChecked(true);
        column.addView(trimNeck);

        Runnable refresh = () -> {
            float dx = (x.getProgress() - 100) / 100f;
            float dy = (y.getProgress() - 100) / 100f;
            float sizeFactor = .5f + size.getProgress() * .005f;
            float degrees = angle.getProgress() - 90f;
            int featherPx = feather.getProgress();
            int colorStrength = color.getProgress();
            HeadSwapCompositor.Settings settings = new HeadSwapCompositor.Settings(
                    dx, dy, sizeFactor, degrees, featherPx, colorStrength);
            preview.setSettings(settings, trimNeck.isChecked());
            values.setText(String.format(Locale.FRANCE,
                    "X : %+d%% | Y : %+d%% | Taille : %d%% | Angle : %+.0f° "
                            + "| Fondu : %d px | Couleur : %d%%",
                    x.getProgress() - 100, y.getProgress() - 100,
                    Math.round(sizeFactor * 100), degrees, featherPx,
                    colorStrength));
        };
        attach(x, refresh); attach(y, refresh); attach(size, refresh);
        attach(angle, refresh); attach(feather, refresh); attach(color, refresh);
        trimNeck.setOnCheckedChangeListener((buttonView, checked) -> refresh.run());
        refresh.run();

        Button reapplyAngle = new Button(activity);
        reapplyAngle.setAllCaps(false);
        reapplyAngle.setText("Retablir l'orientation automatique (yeux)");
        reapplyAngle.setOnClickListener(v -> angle.setProgress(90 + initialAngle));
        column.addView(reapplyAngle);

        Button reset = new Button(activity);
        reset.setAllCaps(false);
        reset.setText("Recentrer et retablir les valeurs recommandees");
        reset.setOnClickListener(v -> {
            x.setProgress(100); y.setProgress(100); size.setProgress(100);
            angle.setProgress(90 + initialAngle);
            feather.setProgress(8); color.setProgress(60);
            trimNeck.setChecked(true);
            refresh.run();
        });
        column.addView(reset);

        TextView notes = new TextView(activity);
        notes.setPadding(0, dp(activity, 8), 0, 0);
        notes.setText(String.format(Locale.FRANCE,
                "Surface detectee : source %.1f%%, cible %.1f%%.\n"
                        + "L'aperçu et le PNG utilisent les mêmes reglages. "
                        + "Pour une pose 3D differente, le collage est desactive "
                        + "et il faut choisir une photo source plus proche.",
                alignment.sourceHeadPercent, alignment.targetHeadPercent));
        column.addView(notes);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(column);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Etape 33 - pose 3D (image fixe)")
                .setView(scroll)
                .setNeutralButton("Enregistrer PNG", null)
                .setPositiveButton("Fermer", null)
                .create();
        dialog.setOnDismissListener(d -> {
            prepared.close();
            alignment.close();
        });
        dialog.setOnShowListener(d -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (save != null) {
                save.setEnabled(pose.compatible);
                save.setOnClickListener(v -> savePreview(
                        activity, executor, prepared, preview.settings,
                        preview.trimNeck));
            }
        });
        dialog.show();
    }

    /** Capture les pixels sur le thread UI, puis ecrit le PNG hors de ce thread. */
    private static void savePreview(AppCompatActivity activity,
                                    ExecutorService executor,
                                    HeadSwapPoseCompositor.Prepared prepared,
                                    HeadSwapCompositor.Settings settings,
                                    boolean trimNeck) {
        if (!active(activity)) return;
        if (executor == null || executor.isShutdown()) {
            message(activity, "Export indisponible", "Le moteur de travail est arrete.");
            return;
        }
        final Bitmap picture;
        try {
            picture = HeadSwapPoseCompositor.compose(prepared, settings, trimNeck);
        } catch (Exception error) {
            message(activity, "Image non creee", error.getMessage());
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    String location = writePng(activity, picture);
                    activity.runOnUiThread(() -> message(activity,
                            "Image fusionnee enregistree", location));
                } catch (Exception error) {
                    Log.e(TAG, "Echec enregistrement PNG", error);
                    String detail = error.getMessage() == null
                            ? error.toString() : error.getMessage();
                    activity.runOnUiThread(() -> message(activity,
                            "Enregistrement impossible", detail));
                } finally {
                    if (!picture.isRecycled()) picture.recycle();
                }
            });
        } catch (RuntimeException rejected) {
            picture.recycle();
            message(activity, "Export indisponible", rejected.getMessage());
        }
    }

    private static String writePng(AppCompatActivity activity, Bitmap picture)
            throws IOException {
        String filename = "HeadSwapV2_" + System.currentTimeMillis() + ".png";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = activity.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/FaceFusionV2");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Creation du fichier image impossible.");
            boolean written = false;
            try (OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null || !picture.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("Ecriture PNG impossible.");
                }
                output.flush();
                written = true;
            } finally {
                if (!written) resolver.delete(uri, null, null);
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return "Galerie > Images > FaceFusionV2 > " + filename;
        }
        // API 26 a 28 : dossier specifique a l'application, sans permission.
        File directory = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (directory == null) directory = activity.getFilesDir();
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Dossier de sortie indisponible.");
        }
        File file = new File(directory, filename);
        try (OutputStream output = new FileOutputStream(file)) {
            if (!picture.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("Compression PNG impossible.");
            }
            output.flush();
        }
        return "Image enregistree dans le dossier prive : " + file.getAbsolutePath();
    }

    /** Composition et export PNG utilisent exactement les memes transformations. */
    private static final class CompositeView extends View {
        private final HeadSwapPoseCompositor.Prepared prepared;
        private HeadSwapCompositor.Settings settings = HeadSwapCompositor.Settings.defaults();
        private boolean trimNeck = true;

        CompositeView(AppCompatActivity activity,
                      HeadSwapPoseCompositor.Prepared prepared) {
            super(activity);
            this.prepared = prepared;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setSettings(HeadSwapCompositor.Settings next, boolean trim) {
            settings = next;
            trimNeck = trim;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (prepared.alignment.targetFrame.isRecycled()
                    || prepared.alignment.sourceHead.isRecycled()) return;
            canvas.drawColor(Color.rgb(28, 28, 32));
            int width = prepared.alignment.targetFrame.getWidth();
            int height = prepared.alignment.targetFrame.getHeight();
            if (width <= 0 || height <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
            float scale = Math.min(getWidth() / (float) width,
                    getHeight() / (float) height);
            float left = (getWidth() - scale * width) * .5f;
            float top = (getHeight() - scale * height) * .5f;
            canvas.save();
            try {
                canvas.translate(left, top);
                canvas.scale(scale, scale);
                HeadSwapPoseCompositor.draw(canvas, prepared, settings, trimNeck);
            } finally {
                canvas.restore();
            }
        }
    }

    private static Bitmap firstVideoFrame(AppCompatActivity activity, Uri videoUri)
            throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap decoded = null;
        try {
            retriever.setDataSource(activity, videoUri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                int w0 = parseDimension(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int h0 = parseDimension(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                if (w0 > 0 && h0 > 0) {
                    float ratio = Math.min(1f, MAX_FRAME_SIDE / (float) Math.max(w0, h0));
                    int width = Math.max(2, Math.round(w0 * ratio));
                    int height = Math.max(2, Math.round(h0 * ratio));
                    decoded = retriever.getScaledFrameAtTime(0,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height);
                }
            }
            if (decoded == null) decoded = retriever.getFrameAtTime(0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (decoded == null) decoded = retriever.getFrameAtTime();
            if (decoded == null) throw new IOException("Image de video introuvable.");
            float ratio = Math.min(1f, MAX_FRAME_SIDE / (float)
                    Math.max(decoded.getWidth(), decoded.getHeight()));
            int width = Math.max(2, Math.round(decoded.getWidth() * ratio));
            int height = Math.max(2, Math.round(decoded.getHeight() * ratio));
            Bitmap scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
            try {
                Bitmap software = scaled.copy(Bitmap.Config.ARGB_8888, false);
                if (software == null) throw new IOException("Conversion image impossible.");
                return software;
            } finally {
                if (scaled != decoded && !scaled.isRecycled()) scaled.recycle();
            }
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Lecture de la video impossible : " + error.getMessage(), error);
        } finally {
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
            try {
                retriever.release();
            } catch (IOException releaseError) {
                Log.w(TAG, "Erreur liberation MediaMetadataRetriever", releaseError);
            }
        }
    }

    private static int parseDimension(String value) {
        try {
            return value == null ? -1 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static SeekBar slider(AppCompatActivity activity, LinearLayout column,
                                  String label, int progress, int max) {
        TextView description = new TextView(activity);
        description.setText(label);
        column.addView(description);
        SeekBar seek = new SeekBar(activity);
        seek.setMax(max);
        seek.setProgress(progress);
        column.addView(seek);
        return seek;
    }

    private static void attach(SeekBar seek, Runnable refresh) {
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                refresh.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
    }

    private static int dp(AppCompatActivity activity, int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    private static boolean active(AppCompatActivity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void message(AppCompatActivity activity, String title, String details) {
        if (!active(activity)) return;
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(details == null ? "Erreur inconnue" : details)
                .setPositiveButton("OK", null)
                .show();
    }
}
