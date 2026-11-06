package com.pv.androidfacefusion;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * Etape 29 : apercu interactif de l'alignement sur UNE image cible.
 * Aucune sortie MP4 ni modification du moteur video existant.
 */
public final class HeadSwapAlignmentPreview {

    private static final String TAG = "HeadSwapAlignment";
    private static final int MAX_FRAME_SIDE = 640;

    private HeadSwapAlignmentPreview() { }

    public static void showForVideo(AppCompatActivity activity,
                                    ExecutorService executor,
                                    Bitmap sourceBitmap, Uri videoUri) {
        if (!active(activity) || executor == null || executor.isShutdown()
                || videoUri == null) return;
        if (sourceBitmap == null || sourceBitmap.isRecycled()) {
            message(activity, "Photo source manquante",
                    "Charge d'abord une photo source dans le mode Standard.");
            return;
        }

        final Bitmap sourceCopy;
        try {
            sourceCopy = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (sourceCopy == null) throw new IOException("Copie de source impossible.");
        } catch (Exception error) {
            message(activity, "Photo source invalide", error.getMessage());
            return;
        }

        int pad = dp(activity, 18);
        LinearLayout loadingBox = new LinearLayout(activity);
        loadingBox.setPadding(pad, pad, pad, pad);
        loadingBox.setOrientation(LinearLayout.VERTICAL);
        TextView loading = new TextView(activity);
        loading.setText("Extraction de la premiere image de la video...\n"
                + "Puis segmentation des deux tetes.\n"
                + "Ce test ne modifie pas le MP4.");
        loadingBox.addView(loading);
        ProgressBar spinner = new ProgressBar(activity);
        loadingBox.addView(spinner);
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("Head Swap - alignement")
                .setView(loadingBox)
                .setCancelable(false)
                .create();
        progress.show();

        try {
            executor.execute(() -> {
                Bitmap target = null;
                HeadSwapAlignmentEngine.Result result = null;
                try {
                    target = firstVideoFrame(activity, videoUri);
                    result = HeadSwapAlignmentEngine.prepare(
                            activity.getApplicationContext(), sourceCopy, target);
                    // A partir de ce point, le Result possede target.
                    target = null;
                    HeadSwapAlignmentEngine.Result ready = result;
                    activity.runOnUiThread(() -> {
                        progress.dismiss();
                        if (!active(activity)) {
                            ready.close();
                            return;
                        }
                        try {
                            showDialog(activity, ready);
                        } catch (Exception displayError) {
                            ready.close();
                            message(activity, "Apercu indisponible",
                                    displayError.getMessage());
                        }
                    });
                } catch (Exception error) {
                    Log.e(TAG, "Echec alignement", error);
                    if (result != null) result.close();
                    String details = error.getMessage() == null
                            ? error.toString() : error.getMessage();
                    activity.runOnUiThread(() -> {
                        progress.dismiss();
                        message(activity, "Alignement impossible", details
                                + "\n\nEssaie une video avec un seul visage net, de face.");
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

    private static Bitmap firstVideoFrame(AppCompatActivity activity, Uri videoUri)
            throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap decoded = null;
        try {
            retriever.setDataSource(activity, videoUri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                int originalW = parseDimension(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int originalH = parseDimension(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                if (originalW > 0 && originalH > 0) {
                    float s = Math.min(1f, MAX_FRAME_SIDE /
                            (float) Math.max(originalW, originalH));
                    int w = Math.max(1, Math.round(originalW * s));
                    int h = Math.max(1, Math.round(originalH * s));
                    // API 27+, reduit la RAM necessaire pour la vignette.
                    decoded = retriever.getScaledFrameAtTime(0,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h);
                }
            }
            if (decoded == null) {
                decoded = retriever.getFrameAtTime(0,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (decoded == null) {
                decoded = retriever.getFrameAtTime();
            }
            if (decoded == null) throw new IOException(
                    "Impossible de lire une image de la video.");

            float scale = Math.min(1f, MAX_FRAME_SIDE /
                    (float) Math.max(decoded.getWidth(), decoded.getHeight()));
            int width = Math.max(2, Math.round(decoded.getWidth() * scale));
            int height = Math.max(2, Math.round(decoded.getHeight() * scale));
            Bitmap smaller = Bitmap.createScaledBitmap(decoded, width, height, true);
            try {
                // L'extracteur travaille avec des pixels logiciels ARGB_8888.
                Bitmap software = smaller.copy(Bitmap.Config.ARGB_8888, false);
                if (software == null) throw new IOException("Conversion bitmap impossible.");
                return software;
            } finally {
                if (smaller != decoded && !smaller.isRecycled()) smaller.recycle();
            }
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Lecture de l'image cible impossible : "
                    + error.getMessage(), error);
        } finally {
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
            try {
                retriever.release();
            } catch (IOException releaseError) {
                Log.w(TAG, "Erreur de liberation MediaMetadataRetriever", releaseError);
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

    private static void showDialog(AppCompatActivity activity,
                                   HeadSwapAlignmentEngine.Result result) {
        int pad = dp(activity, 12);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView info = new TextView(activity);
        info.setText("Apercu sur la premiere image de la video.\n"
                + "Contour cyan : tete cible detectee.\n"
                + "Tete source semi-transparente : position automatique approximative.");
        column.addView(info);

        AlignmentView view = new AlignmentView(activity, result);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 290));
        imageParams.topMargin = dp(activity, 8);
        column.addView(view, imageParams);

        TextView positionInfo = new TextView(activity);
        positionInfo.setText("Decalage X : 0% | Y : 0% | Taille : 100%");
        positionInfo.setPadding(0, dp(activity, 9), 0, dp(activity, 4));
        column.addView(positionInfo);

        SeekBar x = slider(activity, column, "Deplacement horizontal", 100);
        SeekBar y = slider(activity, column, "Deplacement vertical", 100);
        SeekBar size = slider(activity, column, "Taille de la tete", 100);
        Runnable refresh = () -> {
            float offsetX = (x.getProgress() - 100) / 100f;
            float offsetY = (y.getProgress() - 100) / 100f;
            float sizeFactor = .5f + size.getProgress() * .005f;
            view.setAdjustments(offsetX, offsetY, sizeFactor);
            positionInfo.setText(String.format(Locale.FRANCE,
                    "Decalage X : %+d%% | Y : %+d%% | Taille : %d%%",
                    x.getProgress() - 100, y.getProgress() - 100,
                    Math.round(sizeFactor * 100)));
        };
        attach(x, refresh);
        attach(y, refresh);
        attach(size, refresh);

        Button reset = new Button(activity);
        reset.setAllCaps(false);
        reset.setText("Recentrer automatiquement");
        reset.setOnClickListener(v -> {
            x.setProgress(100);
            y.setProgress(100);
            size.setProgress(100);
            refresh.run();
        });
        column.addView(reset);

        TextView footnote = new TextView(activity);
        footnote.setText(String.format(Locale.FRANCE,
                "Detection source : %.1f%% des pixels | cible : %.1f%%.\n"
                        + "Alignement 2D seulement : ne corrige ni la rotation "
                        + "de la tete, ni les oreilles, ni l'eclairage. "
                        + "Les reglages ne sont pas enregistres dans le MP4.",
                result.sourceHeadPercent, result.targetHeadPercent));
        footnote.setPadding(0, dp(activity, 8), 0, 0);
        column.addView(footnote);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(column);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Etape 29 - alignement tete")
                .setView(scroll)
                .setPositiveButton("Fermer", null)
                .create();
        dialog.setOnDismissListener(d -> result.close());
        dialog.show();
    }

    private static SeekBar slider(AppCompatActivity activity,
                                  LinearLayout layout, String label, int value) {
        TextView text = new TextView(activity);
        text.setText(label);
        layout.addView(text);
        SeekBar bar = new SeekBar(activity);
        bar.setMax(200);
        bar.setProgress(value);
        layout.addView(bar);
        return bar;
    }

    private static void attach(SeekBar bar, Runnable refresh) {
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                refresh.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
    }

    private static final class AlignmentView extends View {
        private final HeadSwapAlignmentEngine.Result result;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private float dx;
        private float dy;
        private float sizeFactor = 1f;

        AlignmentView(AppCompatActivity activity, HeadSwapAlignmentEngine.Result result) {
            super(activity);
            this.result = result;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setAdjustments(float dx, float dy, float sizeFactor) {
            this.dx = dx;
            this.dy = dy;
            this.sizeFactor = sizeFactor;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (result.targetFrame.isRecycled() || result.sourceHead.isRecycled()) return;
            canvas.drawColor(Color.rgb(26, 26, 29));
            int w = result.targetFrame.getWidth();
            int h = result.targetFrame.getHeight();
            if (w <= 0 || h <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
            float displayScale = Math.min(getWidth() / (float) w,
                    getHeight() / (float) h);
            float left = (getWidth() - w * displayScale) * .5f;
            float top = (getHeight() - h * displayScale) * .5f;
            canvas.save();
            canvas.translate(left, top);
            canvas.scale(displayScale, displayScale);
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawBitmap(result.targetFrame, 0, 0, paint);

            RectF target = result.targetHeadBounds;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, 2.2f / displayScale));
            paint.setColor(Color.CYAN);
            paint.setAlpha(255);
            canvas.drawRect(target, paint);

            float centerX = target.centerX() + dx * target.width() * .6f;
            float centerY = target.centerY() + dy * target.height() * .6f;
            float placedWidth = result.sourceHead.getWidth() * result.baseScale * sizeFactor;
            float placedHeight = result.sourceHead.getHeight() * result.baseScale * sizeFactor;
            RectF placed = new RectF(centerX - placedWidth * .5f,
                    centerY - placedHeight * .5f,
                    centerX + placedWidth * .5f,
                    centerY + placedHeight * .5f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setAlpha(205);
            canvas.drawBitmap(result.sourceHead, null, placed, paint);
            paint.setAlpha(255);
            canvas.restore();
        }
    }

    private static int dp(AppCompatActivity activity, int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    private static boolean active(AppCompatActivity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void message(AppCompatActivity activity,
                                String title, String detail) {
        if (!active(activity)) return;
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(detail == null ? "Erreur inconnue" : detail)
                .setPositiveButton("OK", null)
                .show();
    }
}
