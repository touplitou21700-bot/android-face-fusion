package com.pv.androidfacefusion;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** Interface de l'ETAPE 36 : premier Head Swap video, 2 secondes environ. */
public final class HeadSwapVideoTestUi {
    private static final String TAG = "HeadSwapVideoTestUi";

    private HeadSwapVideoTestUi() { }

    public static void start(AppCompatActivity activity, Uri videoUri,
                             ExecutorService executor, Bitmap sourceBitmap) {
        if (!active(activity) || videoUri == null) return;
        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            message(activity, "Photo source manquante",
                    "Charge d'abord une photo source dans le mode Standard.");
            return;
        }
        int pad = (int) (12 * activity.getResources().getDisplayMetrics().density + .5f);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        TextView description = new TextView(activity);
        description.setText("Premier test Head Swap video : selectionne une video "
                + "de 1 a 2 secondes (max 2,3 s). Les reglages ci-dessous "
                + "s'appliquent a toutes les images. L'orientation 3D ajoute "
                + "sa correction automatiquement.");
        box.addView(description);
        SeekBar x = slider(activity, box, "Correction X (-50 a +50 %)", 100, 50);
        SeekBar y = slider(activity, box, "Correction Y (-50 a +50 %)", 100, 50);
        SeekBar size = slider(activity, box, "Taille (70 a 130 %)", 60, 30);
        SeekBar angle = slider(activity, box, "Angle ajoute (-90 a +90 degres)", 180, 90);
        SeekBar feather = slider(activity, box, "Fondu (0 a 24 px)", 24, 8);
        SeekBar color = slider(activity, box, "Harmonisation des couleurs (0 a 100 %)",
                100, 60);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        new AlertDialog.Builder(activity)
                .setTitle("Head Swap video - reglages de test")
                .setView(scroll)
                .setPositiveButton("Creer le MP4", (dialog, which) -> {
                    HeadSwapVideoTestPipeline.Options options =
                            new HeadSwapVideoTestPipeline.Options(
                                    (x.getProgress() - 50) / 100f,
                                    (y.getProgress() - 50) / 100f,
                                    (size.getProgress() + 70) / 100f,
                                    angle.getProgress() - 90f,
                                    feather.getProgress(), color.getProgress());
                    launch(activity, videoUri, executor, sourceBitmap, options);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private static SeekBar slider(AppCompatActivity activity, LinearLayout box,
                                  String label, int max, int initial) {
        TextView text = new TextView(activity);
        text.setText(label);
        box.addView(text);
        SeekBar seek = new SeekBar(activity);
        seek.setMax(max);
        seek.setProgress(initial);
        box.addView(seek);
        return seek;
    }

    private static void launch(AppCompatActivity activity, Uri videoUri,
                               ExecutorService executor, Bitmap sourceBitmap,
                               HeadSwapVideoTestPipeline.Options options) {
        if (!active(activity) || videoUri == null) return;
        if (executor == null || executor.isShutdown()) {
            message(activity, "Traitement indisponible", "Le moteur de travail est arrete.");
            return;
        }
        final Bitmap copy;
        try {
            if (sourceBitmap == null || sourceBitmap.isRecycled()) {
                throw new IOException("Charge une photo source dans le mode Standard.");
            }
            copy = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) throw new IOException("Copie de la photo source impossible.");
        } catch (Exception error) {
            message(activity, "Photo source indisponible", error.getMessage());
            return;
        }

        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density + .5f);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);
        TextView info = new TextView(activity);
        info.setText("Head Swap VIDEO experimental : 2 secondes environ, "
                + "150 images maximum, 480 pixels maximum.\n"
                + "Le traitement peut etre lent : garde l'application ouverte.");
        layout.addView(info);
        ProgressBar bar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(HeadSwapVideoTestPipeline.MAX_FRAMES);
        bar.setIndeterminate(true);
        layout.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("Test Head Swap video - V2")
                .setView(layout)
                .setCancelable(false)
                .create();
        progress.show();

        try {
            executor.execute(() -> {
                File temporaryResult = null;
                try {
                    HeadSwapVideoTestPipeline.Result result =
                            HeadSwapVideoTestPipeline.create(
                                    activity.getApplicationContext(), videoUri, copy,
                                    options, new HeadSwapVideoTestPipeline.ProgressListener() {
                                        @Override public void onStage(String stage) {
                                            activity.runOnUiThread(() -> {
                                                if (active(activity)) info.setText(stage);
                                            });
                                        }

                                        @Override public void onFrame(
                                                int total, int replaced, int unchanged) {
                                            activity.runOnUiThread(() -> {
                                                if (!active(activity)) return;
                                                bar.setIndeterminate(false);
                                                bar.setProgress(total);
                                                info.setText(String.format(Locale.FRANCE,
                                                        "Images traitees : %d / max %d\n"
                                                                + "Tetes superposees : %d\n"
                                                                + "Images d'origine conservees : %d",
                                                        total, HeadSwapVideoTestPipeline.MAX_FRAMES,
                                                        replaced, unchanged));
                                            });
                                        }
                                    });
                    temporaryResult = result.mp4File;
                    Uri galleryUri = publishToGallery(activity, temporaryResult);
                    String summary = String.format(Locale.FRANCE,
                            "Video Head Swap enregistree dans Films/FaceFusionV2.\n\n"
                                    + "Images lues : %d\n"
                                    + "Images avec tete source : %d\n"
                                    + "Images sans modification : %d\n"
                                    + "Poses 3D differentes : %d\n"
                                    + "Images encodees : %d\n"
                                    + "Resolution : %d x %d\n"
                                    + "Son original copie : %s\n\n"
                                    + "Le placement reste 2D ; des changements de pose "
                                    + "peuvent produire du scintillement.",
                            result.framesDecoded, result.framesReplaced,
                            result.framesUnchanged, result.incompatiblePoses,
                            result.framesEncoded, result.width, result.height,
                            result.audioCopied ? "oui" : "non (source muette)");
                    activity.runOnUiThread(() -> {
                        progress.dismiss();
                        if (!active(activity)) return;
                        new AlertDialog.Builder(activity)
                                .setTitle("Premier Head Swap video termine")
                                .setMessage(summary)
                                .setPositiveButton("Lire avec VLC", (d, w) -> openWithVlc(
                                        activity, galleryUri))
                                .setNegativeButton("Fermer", null)
                                .show();
                    });
                } catch (Exception error) {
                    Log.e(TAG, "Echec du Head Swap video experimental", error);
                    String detail = error.getMessage() == null
                            ? error.toString() : error.getMessage();
                    activity.runOnUiThread(() -> {
                        progress.dismiss();
                        message(activity, "Head Swap video non cree", detail);
                    });
                } finally {
                    if (temporaryResult != null && temporaryResult.exists()
                            && !temporaryResult.delete()) {
                        Log.w(TAG, "Impossible de vider le cache MP4 temporaire.");
                    }
                    if (!copy.isRecycled()) copy.recycle();
                }
            });
        } catch (RuntimeException rejected) {
            progress.dismiss();
            if (!copy.isRecycled()) copy.recycle();
            message(activity, "Traitement non lance", rejected.getMessage());
        }
    }

    private static Uri publishToGallery(AppCompatActivity activity, File mp4)
            throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("Export galerie disponible sur Android 10 ou plus.");
        }
        if (mp4 == null || !mp4.isFile() || mp4.length() <= 0) {
            throw new IOException("MP4 Head Swap introuvable ou vide.");
        }
        ContentResolver resolver = activity.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME,
                "HeadSwapV2_test_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/FaceFusionV2");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Creation du fichier dans la galerie impossible.");
        boolean written = false;
        try {
            try (FileInputStream input = new FileInputStream(mp4);
                 OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("Ouverture galerie impossible.");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) output.write(buffer, 0, count);
                }
                output.flush();
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) < 1) {
                throw new IOException("Impossible de finaliser le MP4 dans la galerie.");
            }
            written = true;
            return uri;
        } finally {
            if (!written) resolver.delete(uri, null, null);
        }
    }

    private static void openWithVlc(AppCompatActivity activity, Uri videoUri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(videoUri, "video/mp4");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setPackage("org.videolan.vlc");
        try {
            activity.startActivity(intent);
        } catch (Exception vlcUnavailable) {
            try {
                intent.setPackage(null);
                activity.startActivity(Intent.createChooser(intent, "Ouvrir le MP4 avec..."));
            } catch (Exception ignored) {
                message(activity, "MP4 enregistre",
                        "Ouvre VLC > Films > FaceFusionV2, puis choisis HeadSwapV2_test.");
            }
        }
    }

    private static boolean active(AppCompatActivity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void message(AppCompatActivity activity, String title, String detail) {
        if (!active(activity)) return;
        new AlertDialog.Builder(activity).setTitle(title)
                .setMessage(detail == null ? "Erreur inconnue" : detail)
                .setPositiveButton("OK", null).show();
    }
}
