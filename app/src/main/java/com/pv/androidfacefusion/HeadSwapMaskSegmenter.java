package com.pv.androidfacefusion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.framework.image.ByteBufferExtractor;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Android Face Fusion V2 - Etape 25 : diagnostic de segmentation de la tete.
 *
 * Affiche les categories CHEVEUX (1) et PEAU DU VISAGE (3) sur la photo source.
 * N'effectue AUCUN remplacement de tete. La segmentation est une premiere
 * brique, pas un Head Swap complet (oreilles, nuque, pose et raccord manquent).
 *
 * A appeler exclusivement depuis un thread de travail. Une image a la fois.
 */
public final class HeadSwapMaskSegmenter {

    public static final String MODEL_ASSET = "selfie_multiclass_256x256.tflite";
    private static final int HAIR = 1;
    private static final int FACE_SKIN = 3;
    private static final int MAX_PREVIEW_SIDE = 640;

    private HeadSwapMaskSegmenter() { }

    public static final class Result {
        public final Bitmap preview;
        public final float hairPercent;
        public final float facePercent;
        public final float headPercent;

        private Result(Bitmap preview, float hairPercent, float facePercent) {
            this.preview = preview;
            this.hairPercent = hairPercent;
            this.facePercent = facePercent;
            this.headPercent = hairPercent + facePercent;
        }
    }

    public static Result analyze(Context context, Bitmap source) throws Exception {
        if (context == null || source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Photo source manquante ou invalide.");
        }

        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build();
        ImageSegmenter.ImageSegmenterOptions options =
                ImageSegmenter.ImageSegmenterOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.IMAGE)
                        .setOutputCategoryMask(true)
                        .setOutputConfidenceMasks(false)
                        .build();

        ImageSegmenter segmenter = null;
        try {
            segmenter = ImageSegmenter.createFromOptions(context, options);
            // MediaPipe prend un Bitmap ARGB_8888, fourni par MainActivity.
            MPImage input = new BitmapImageBuilder(source).build();
            ImageSegmenterResult output = segmenter.segment(input);
            Optional<MPImage> optionalMask = output.categoryMask();
            if (!optionalMask.isPresent()) {
                throw new IllegalStateException("Le modele n'a retourne aucun masque.");
            }

            MPImage mask = optionalMask.get();
            int maskWidth = mask.getWidth();
            int maskHeight = mask.getHeight();
            if (maskWidth <= 0 || maskHeight <= 0) {
                throw new IllegalStateException("Dimensions de masque invalides.");
            }
            ByteBuffer categoryBuffer = ByteBufferExtractor.extract(mask).duplicate();
            categoryBuffer.rewind();
            int maskLength = maskWidth * maskHeight;
            if (categoryBuffer.remaining() < maskLength) {
                throw new IllegalStateException("Masque tronque : donnees insuffisantes.");
            }
            byte[] classes = new byte[maskLength];
            categoryBuffer.get(classes);

            int hairCount = 0;
            int faceCount = 0;
            for (byte cell : classes) {
                int label = cell & 0xff;
                if (label == HAIR) hairCount++;
                if (label == FACE_SKIN) faceCount++;
            }

            int width = source.getWidth();
            int height = source.getHeight();
            float scale = Math.min(1f, MAX_PREVIEW_SIDE / (float) Math.max(width, height));
            int outWidth = Math.max(1, Math.round(width * scale));
            int outHeight = Math.max(1, Math.round(height * scale));

            Bitmap resized = Bitmap.createScaledBitmap(source, outWidth, outHeight, true);
            try {
                int[] original = new int[outWidth * outHeight];
                resized.getPixels(original, 0, outWidth, 0, 0, outWidth, outHeight);
                int[] visual = new int[original.length];
                for (int y = 0; y < outHeight; y++) {
                    int maskY = Math.min(maskHeight - 1, (int) ((long) y * maskHeight / outHeight));
                    int row = y * outWidth;
                    int maskRow = maskY * maskWidth;
                    for (int x = 0; x < outWidth; x++) {
                        int maskX = Math.min(maskWidth - 1,
                                (int) ((long) x * maskWidth / outWidth));
                        int label = classes[maskRow + maskX] & 0xff;
                        int pixel = original[row + x];
                        if (label == HAIR) {
                            visual[row + x] = mix(pixel, Color.MAGENTA, 0.56f);
                        } else if (label == FACE_SKIN) {
                            visual[row + x] = mix(pixel, Color.CYAN, 0.53f);
                        } else {
                            visual[row + x] = mix(pixel, Color.BLACK, 0.26f);
                        }
                    }
                }
                Bitmap preview = Bitmap.createBitmap(outWidth, outHeight,
                        Bitmap.Config.ARGB_8888);
                preview.setPixels(visual, 0, outWidth, 0, 0, outWidth, outHeight);
                return new Result(preview,
                        100f * hairCount / maskLength,
                        100f * faceCount / maskLength);
            } finally {
                if (resized != source && !resized.isRecycled()) resized.recycle();
            }
        } finally {
            if (segmenter != null) segmenter.close();
        }
    }

    private static int mix(int base, int tint, float amount) {
        float inv = 1f - amount;
        int r = Math.min(255, Math.round(Color.red(base) * inv + Color.red(tint) * amount));
        int g = Math.min(255, Math.round(Color.green(base) * inv + Color.green(tint) * amount));
        int b = Math.min(255, Math.round(Color.blue(base) * inv + Color.blue(tint) * amount));
        return Color.rgb(r, g, b);
    }
}
