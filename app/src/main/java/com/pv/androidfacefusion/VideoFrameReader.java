
package com.pv.androidfacefusion;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.IOException;

/**
 * Android Face Fusion V2
 *
 * VideoFrameReader
 *
 * Lit les informations d'une video et extrait
 * une image a un instant donne.
 *
 * Fonctionnement :
 * - Local et hors ligne
 * - Resolution limitee pour economiser la RAM
 * - Compatible Android API 26+
 * - Liberation explicite des ressources
 *
 * Ce composant ne traite pas encore les visages
 * et ne genere pas encore de fichier MP4.
 */
public final class VideoFrameReader
        implements AutoCloseable {

    private static final String TAG = "VideoFrameReader";

    public static final int PREVIEW_480P = 480;
    public static final int PREVIEW_720P = 720;

    private final MediaMetadataRetriever retriever;

    private final long durationMs;
    private final int width;
    private final int height;
    private final int rotationDegrees;

    private boolean closed = false;

    /**
     * Ouvre une video locale depuis une URI Android.
     */
    public VideoFrameReader(
            Context context,
            Uri videoUri
    ) throws IOException {

        if (context == null || videoUri == null) {
            throw new IllegalArgumentException(
                    "Context and video URI are required"
            );
        }

        retriever = new MediaMetadataRetriever();

        try {
            retriever.setDataSource(context, videoUri);

            durationMs = readLongMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
            );

            width = (int) readLongMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            );

            height = (int) readLongMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            );

            rotationDegrees = (int) readLongMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            );

            if (durationMs <= 0 || width <= 0 || height <= 0) {
                throw new IOException(
                        "Invalid or unsupported video metadata"
                );
            }

            Log.i(
                    TAG,
                    "Video opened: "
                            + width + "x" + height
                            + ", duration=" + durationMs
                            + " ms, rotation=" + rotationDegrees
            );

        } catch (Exception e) {

            retriever.release();

            throw new IOException(
                    "Failed to open video: " + e.getMessage(),
                    e
            );
        }
    }

    /**
     * Lecture securisee des metadonnees.
     */
    private long readLongMetadata(int key) {

        String value = retriever.extractMetadata(key);

        if (value == null || value.trim().isEmpty()) {
            return 0;
        }

        try {
            return Long.parseLong(value.trim());

        } catch (NumberFormatException e) {

            Log.w(TAG, "Invalid metadata: " + value);

            return 0;
        }
    }

    /**
     * Duree de la video en millisecondes.
     */
    public long getDurationMs() {
        return durationMs;
    }

    /**
     * Largeur originale.
     */
    public int getWidth() {
        return width;
    }

    /**
     * Hauteur originale.
     */
    public int getHeight() {
        return height;
    }

    /**
     * Orientation indiquee dans les metadonnees.
     */
    public int getRotationDegrees() {
        return rotationDegrees;
    }

    /**
     * Extrait une image proche du temps demande.
     *
     * timeMs : position dans la video en millisecondes
     * maxSide : dimension maximale souhaitee
     *
     * Le Bitmap retourne appartient a l'appelant.
     * Il devra etre recycle apres utilisation.
     */
    public synchronized Bitmap getFrameAtMs(
            long timeMs,
            int maxSide
    ) throws IOException {

        if (closed) {
            throw new IOException(
                    "Video reader is already closed"
            );
        }

        if (maxSide < 128) {
            throw new IllegalArgumentException(
                    "maxSide must be at least 128 pixels"
            );
        }

        long safeTimeMs = Math.max(
                0,
                Math.min(timeMs, durationMs - 1)
        );

        long timeUs = safeTimeMs * 1000L;

        double scale = Math.min(
                1.0,
                (double) maxSide / Math.max(width, height)
        );

        int targetWidth = Math.max(
                1,
                (int) Math.round(width * scale)
        );

        int targetHeight = Math.max(
                1,
                (int) Math.round(height * scale)
        );

        Bitmap frame = null;

        try {

            if (Build.VERSION.SDK_INT
                    >= Build.VERSION_CODES.O_MR1) {

                frame = retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        targetWidth,
                        targetHeight
                );

            } else {

                frame = retriever.getFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST
                );

                if (frame != null
                        && Math.max(
                                frame.getWidth(),
                                frame.getHeight()
                        ) > maxSide) {

                    double resizeScale =
                            (double) maxSide
                                    / Math.max(
                                            frame.getWidth(),
                                            frame.getHeight()
                                    );

                    int newWidth = Math.max(
                            1,
                            (int) Math.round(
                                    frame.getWidth() * resizeScale
                            )
                    );

                    int newHeight = Math.max(
                            1,
                            (int) Math.round(
                                    frame.getHeight() * resizeScale
                            )
                    );

                    Bitmap resized = Bitmap.createScaledBitmap(
                            frame,
                            newWidth,
                            newHeight,
                            true
                    );

                    if (resized != frame) {
                        frame.recycle();
                    }

                    frame = resized;
                }
            }

            if (frame == null) {
                throw new IOException(
                        "Unable to decode video frame"
                );
            }

            if (frame.getConfig() != Bitmap.Config.ARGB_8888) {

                Bitmap converted = frame.copy(
                        Bitmap.Config.ARGB_8888,
                        false
                );

                if (converted == null) {
                    throw new IOException(
                            "Unable to convert frame format"
                    );
                }

                frame.recycle();
                frame = converted;
            }

            return frame;

        } catch (Exception e) {

            if (frame != null && !frame.isRecycled()) {
                frame.recycle();
            }

            throw new IOException(
                    "Frame extraction failed at "
                            + safeTimeMs + " ms",
                    e
            );
        }
    }

    /**
     * Ferme le lecteur et libere les ressources natives.
     */
    @Override
    public synchronized void close() {

        if (closed) {
            return;
        }

        closed = true;

        try {
            retriever.release();

        } catch (Exception e) {
            Log.w(TAG, "Error releasing video reader", e);
        }

        Log.d(TAG, "Video reader closed");
    }
}
