package com.pv.androidfacefusion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Android Face Fusion V2 - ETAPE 12.
 *
 * Decodage SEQUENTIEL des vraies images d'une video, via les API Android.
 * Contrairement a MediaMetadataRetriever, ne fait PAS un echantillonnage.
 *
 * Cette classe ne fait aucun face swap et NE PRODUIT PAS de MP4.
 * Elle fournit des Bitmap (une par une) a un traitement futur.
 *
 * Executer uniquement sur un thread de travail. Les Bitmap sont valides
 * pendant l'appel du listener ; ne pas les conserver sans en faire une copie.
 * Usage : VideoSequentialDecoder.decode(context, uri, 480, 900, listener).
 *
 * Limites connues : video non protegee avec decodeur Android compatible,
 * sorties YUV_420_888 accessibles, conversion YUV vers RGB de type BT.601.
 * Certains codecs/telephones peuvent produire un format non pris en charge.
 */
public final class VideoSequentialDecoder {

    private static final String TAG = "VideoSequentialDecoder";
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    private static final int MAX_IDLE_ITERATIONS = 500;

    /**
     * Le Bitmap est recycle immediatement apres onFrame().
     * Le callback ne doit pas garder sa reference apres son retour.
     * Si le traitement requiert de garder une image, faire une copie.
     */
    public interface FrameListener {
        void onFrame(int index, long presentationTimeUs, Bitmap frame)
                throws Exception;
    }

    public static final class Report {
        public final int decodedFrames;
        public final int sourceWidth;
        public final int sourceHeight;
        public final int rotationDegrees;
        public final long lastPresentationTimeUs;
        public final String videoMime;

        private Report(int decodedFrames, int sourceWidth,
                int sourceHeight, int rotationDegrees,
                long lastPresentationTimeUs, String videoMime) {
            this.decodedFrames = decodedFrames;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.rotationDegrees = rotationDegrees;
            this.lastPresentationTimeUs = lastPresentationTimeUs;
            this.videoMime = videoMime;
        }
    }

    private VideoSequentialDecoder() {}

    /**
     * Decode toutes les images de la piste video, dans l'ordre.
     * maxFrames est une barriere de securite (PAS un echantillonnage) :
     * une exception est levee si la video depasse cette limite.
     * maxSide limite la resolution du Bitmap transmis au listener.
     */
    public static Report decode(
            Context context,
            Uri videoUri,
            int maxSide,
            int maxFrames,
            FrameListener listener
    ) throws Exception {

        if (context == null || videoUri == null || listener == null) {
            throw new IllegalArgumentException("Contexte, video et listener requis");
        }
        if (maxSide < 128 || maxSide > 1024) {
            throw new IllegalArgumentException("maxSide : 128 a 1024 pixels");
        }
        if (maxFrames < 1 || maxFrames > 3600) {
            throw new IllegalArgumentException("maxFrames : 1 a 3600");
        }

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        boolean codecStarted = false;
        int sourceWidth = 0;
        int sourceHeight = 0;
        int rotationDegrees = 0;
        String videoMime = "";
        int decodedFrames = 0;
        long lastTimeUs = -1;

        try {
            extractor.setDataSource(context, videoUri, null);
            int videoTrack = -1;
            MediaFormat videoFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    videoFormat = f;
                    videoMime = mime;
                    break;
                }
            }
            if (videoTrack < 0 || videoFormat == null) {
                throw new IOException("Aucune piste video exploitable");
            }

            sourceWidth = videoFormat.containsKey(MediaFormat.KEY_WIDTH)
                    ? videoFormat.getInteger(MediaFormat.KEY_WIDTH) : 0;
            sourceHeight = videoFormat.containsKey(MediaFormat.KEY_HEIGHT)
                    ? videoFormat.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                throw new IOException("Dimensions video invalides");
            }

            rotationDegrees = getRotation(context, videoUri);
            extractor.selectTrack(videoTrack);
            decoder = MediaCodec.createDecoderByType(videoMime);
            decoder.configure(videoFormat, null, null, 0);
            decoder.start();
            codecStarted = true;

            boolean inputEnded = false;
            boolean outputEnded = false;
            int idleIterations = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!outputEnded) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Decodage annule");
                }
                boolean progressed = false;

                if (!inputEnded) {
                    int inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        if (inputBuffer == null) {
                            throw new IOException("Buffer d'entree video indisponible");
                        }
                        inputBuffer.clear();
                        int length = extractor.readSampleData(inputBuffer, 0);
                        if (length < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            long timestampUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputIndex, 0, length,
                                    timestampUs, 0);
                            extractor.advance();
                        }
                        progressed = true;
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(
                        info, DEQUEUE_TIMEOUT_US);
                if (outputIndex >= 0) {
                    progressed = true;
                    Bitmap frame = null;
                    try {
                        if (info.size > 0) {
                            if (decodedFrames >= maxFrames) {
                                throw new IOException("Video trop longue pour ce test : "
                                        + "plus de " + maxFrames + " images. "
                                        + "Choisir un extrait plus court.");
                            }

                            Image decodedImage = decoder.getOutputImage(outputIndex);
                            if (decodedImage == null) {
                                throw new IOException("Sortie YUV inaccessible sur ce decodeur");
                            }
                            try {
                                frame = toScaledBitmap(decodedImage, maxSide);
                            } finally {
                                decodedImage.close();
                            }
                            if (rotationDegrees != 0) {
                                Bitmap rotated = rotateBitmap(frame, rotationDegrees);
                                if (rotated != frame) {
                                    frame.recycle();
                                    frame = rotated;
                                }
                            }

                            // La trame est autonome : le buffer MediaCodec peut
                            // etre rendu au decodeur avant l'appel IA.
                            lastTimeUs = info.presentationTimeUs;
                            listener.onFrame(decodedFrames, lastTimeUs, frame);
                            decodedFrames++;
                        }
                    } finally {
                        if (frame != null && !frame.isRecycled()) {
                            frame.recycle();
                        }
                        decoder.releaseOutputBuffer(outputIndex, false);
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEnded = true;
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Format de sortie : " + decoder.getOutputFormat());
                    progressed = true;
                }

                idleIterations = progressed ? 0 : idleIterations + 1;
                if (idleIterations > MAX_IDLE_ITERATIONS) {
                    throw new IOException("Decodeur bloque : aucune progression");
                }
            }

            return new Report(decodedFrames, sourceWidth, sourceHeight,
                    rotationDegrees, lastTimeUs, videoMime);
        } finally {
            if (decoder != null) {
                if (codecStarted) {
                    try {
                        decoder.stop();
                    } catch (Exception error) {
                        Log.w(TAG, "Impossible d'arreter le decodeur", error);
                    }
                }
                decoder.release();
            }
            extractor.release();
        }
    }

    private static int getRotation(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String value = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (value != null) {
                int degrees = Integer.parseInt(value);
                if (degrees == 90 || degrees == 180 || degrees == 270) {
                    return degrees;
                }
            }
            return 0;
        } catch (Exception e) {
            Log.w(TAG, "Rotation video indisponible", e);
            return 0;
        } finally {
            retriever.release();
        }
    }

    /**
     * Conversion YUV 4:2:0 vers ARGB, directement a resolution reduite.
     * Cette conversion simple emploie BT.601 : les couleurs HD pourront
     * necessiter une correction ulterieure (BT.709, plage couleur).
     */
    private static Bitmap toScaledBitmap(Image image, int maxSide)
            throws IOException {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IOException("Format de sortie video non supporte : "
                    + image.getFormat());
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) {
            throw new IOException("Plans YUV incomplets");
        }
        Rect crop = image.getCropRect();
        int sourceW = crop.width();
        int sourceH = crop.height();
        if (sourceW <= 0 || sourceH <= 0) {
            throw new IOException("Dimensions de trame invalides");
        }
        double scale = Math.min(1.0,
                (double) maxSide / Math.max(sourceW, sourceH));
        int resultW = Math.max(2, (int) Math.round(sourceW * scale));
        int resultH = Math.max(2, (int) Math.round(sourceH * scale));
        int[] pixels = new int[resultW * resultH];

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int uRow = planes[1].getRowStride();
        int vRow = planes[2].getRowStride();
        int yPixel = planes[0].getPixelStride();
        int uPixel = planes[1].getPixelStride();
        int vPixel = planes[2].getPixelStride();

        for (int y = 0; y < resultH; y++) {
            int srcY = crop.top + (int) ((long) y * sourceH / resultH);
            for (int x = 0; x < resultW; x++) {
                int srcX = crop.left + (int) ((long) x * sourceW / resultW);
                int luma = yBuffer.get(srcY * yRow + srcX * yPixel) & 0xff;
                int chromaY = srcY / 2;
                int chromaX = srcX / 2;
                int u = uBuffer.get(chromaY * uRow + chromaX * uPixel) & 0xff;
                int v = vBuffer.get(chromaY * vRow + chromaX * vPixel) & 0xff;
                int c = Math.max(0, luma - 16);
                int d = u - 128;
                int e = v - 128;
                int red = clamp((298 * c + 409 * e + 128) >> 8);
                int green = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                int blue = clamp((298 * c + 516 * d + 128) >> 8);
                pixels[y * resultW + x] = 0xff000000
                        | (red << 16) | (green << 8) | blue;
            }
        }
        return Bitmap.createBitmap(pixels, resultW, resultH,
                Bitmap.Config.ARGB_8888);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static Bitmap rotateBitmap(Bitmap source, int degrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(source, 0, 0,
                source.getWidth(), source.getHeight(), matrix, true);
    }
}
