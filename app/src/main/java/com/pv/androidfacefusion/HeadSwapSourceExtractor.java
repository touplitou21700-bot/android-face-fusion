package com.pv.androidfacefusion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.ByteBufferExtractor;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Android Face Fusion V2 - Etape 27 : extraction EXPERIMENTALE de la tete source.
 *
 * Prend les classes MediaPipe 1 (cheveux) et 3 (peau du visage), comble les
 * petits trous, garde la plus grande region connectee, adoucit les contours,
 * puis renvoie une image ARGB_8888 recadree avec une vraie transparence.
 *
 * Cette extraction n'est PAS un remplacement de tete. Le modele ne segmente
 * pas explicitement les oreilles, la nuque, les yeux ni l'ensemble du crane.
 * L'extraction peut etre incomplete sur certaines photos ou certains profils.
 *
 * Utilisation : depuis un thread de travail uniquement. Une photo a la fois.
 */
public final class HeadSwapSourceExtractor {

    private static final int HAIR_CLASS = 1;
    private static final int FACE_CLASS = 3;
    private static final int MAX_SIDE = 640;
    private static final int MORPH_RADIUS = 2;
    private static final int SOFT_RADIUS = 3;

    private HeadSwapSourceExtractor() { }

    public static final class Result {
        /** Bitmap recadre, transparent en dehors de la tete. L'appelant le recycle. */
        public final Bitmap headBitmap;
        public final int sourceWidth;
        public final int sourceHeight;
        public final int cropLeft;
        public final int cropTop;
        public final int cropWidth;
        public final int cropHeight;
        public final float headPercent;

        private Result(Bitmap headBitmap, int sourceWidth, int sourceHeight,
                       int cropLeft, int cropTop, float headPercent) {
            this.headBitmap = headBitmap;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.cropLeft = cropLeft;
            this.cropTop = cropTop;
            this.cropWidth = headBitmap.getWidth();
            this.cropHeight = headBitmap.getHeight();
            this.headPercent = headPercent;
        }
    }

    public static Result extract(Context context, Bitmap source) throws Exception {
        if (context == null || source == null || source.isRecycled()
                || source.getWidth() < 2 || source.getHeight() < 2) {
            throw new IllegalArgumentException("Photo source manquante ou invalide.");
        }

        int originalWidth = source.getWidth();
        int originalHeight = source.getHeight();
        float scale = Math.min(1f, MAX_SIDE / (float) Math.max(originalWidth, originalHeight));
        int width = Math.max(2, Math.round(originalWidth * scale));
        int height = Math.max(2, Math.round(originalHeight * scale));

        // Travailler sur une copie de taille controlee pour limiter la RAM.
        Bitmap frame = Bitmap.createScaledBitmap(source, width, height, true);
        boolean ownFrame = frame != source;
        try {
            byte[] labels;
            int maskWidth;
            int maskHeight;
            BaseOptions base = BaseOptions.builder()
                    .setModelAssetPath(HeadSwapMaskSegmenter.MODEL_ASSET).build();
            ImageSegmenter.ImageSegmenterOptions options =
                    ImageSegmenter.ImageSegmenterOptions.builder()
                            .setBaseOptions(base)
                            .setRunningMode(RunningMode.IMAGE)
                            .setOutputCategoryMask(true)
                            .setOutputConfidenceMasks(false)
                            .build();

            // try-with-resources : libere le modele a la fin de chaque extraction.
            try (ImageSegmenter segmenter = ImageSegmenter.createFromOptions(context, options)) {
                MPImage image = new BitmapImageBuilder(frame).build();
                ImageSegmenterResult segmentation = segmenter.segment(image);
                Optional<MPImage> mask = segmentation.categoryMask();
                if (!mask.isPresent()) {
                    throw new IllegalStateException("Le modele n'a retourne aucun masque.");
                }
                MPImage mpMask = mask.get();
                maskWidth = mpMask.getWidth();
                maskHeight = mpMask.getHeight();
                if (maskWidth < 1 || maskHeight < 1) {
                    throw new IllegalStateException("Dimensions du masque invalides.");
                }
                long maskLengthLong = (long) maskWidth * maskHeight;
                if (maskLengthLong > Integer.MAX_VALUE) {
                    throw new IllegalStateException("Masque trop grand.");
                }
                int maskLength = (int) maskLengthLong;
                ByteBuffer data = ByteBufferExtractor.extract(mpMask).duplicate();
                data.rewind();
                if (data.remaining() < maskLength) {
                    throw new IllegalStateException("Masque incomplet.");
                }
                labels = new byte[maskLength];
                data.get(labels);
            }

            boolean[] selected = new boolean[width * height];
            for (int y = 0; y < height; y++) {
                int my = Math.min(maskHeight - 1, (int) ((long) y * maskHeight / height));
                for (int x = 0; x < width; x++) {
                    int mx = Math.min(maskWidth - 1, (int) ((long) x * maskWidth / width));
                    int category = labels[my * maskWidth + mx] & 255;
                    selected[y * width + x] = (category == HAIR_CLASS || category == FACE_CLASS);
                }
            }

            // Petite fermeture morphologique : corrige des fissures du masque.
            selected = erode(dilate(selected, width, height, MORPH_RADIUS),
                    width, height, MORPH_RADIUS);
            // Evite de prendre des groupes de pixels eloignes du sujet principal.
            selected = largestComponent(selected, width, height);
            // Remplit notamment les trous internes des yeux et de la bouche.
            fillEnclosedHoles(selected, width, height);

            int minX = width, minY = height, maxX = -1, maxY = -1;
            int pixels = 0;
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    if (selected[row + x]) {
                        pixels++;
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (pixels < Math.max(64, width * height / 500)) {
                throw new IllegalStateException("Aucune tete assez nette detectee. "
                        + "Essaie une photo avec un seul visage de face.");
            }

            // Agrandissement tres modere + feather pour eviter les bords cranteles.
            boolean[] expanded = dilate(selected, width, height, 2);
            int[] alpha = soften(expanded, width, height, SOFT_RADIUS);

            // Conserver de l'espace transparent autour de la tete.
            int headSpan = Math.max(maxX - minX + 1, maxY - minY + 1);
            int padding = Math.max(8, Math.round(headSpan * .07f));
            int left = Math.max(0, minX - padding);
            int top = Math.max(0, minY - padding);
            int right = Math.min(width, maxX + padding + 1);
            int bottom = Math.min(height, maxY + padding + 1);
            int cropW = right - left;
            int cropH = bottom - top;

            int[] sourcePixels = new int[width * height];
            frame.getPixels(sourcePixels, 0, width, 0, 0, width, height);
            int[] outPixels = new int[cropW * cropH];
            for (int y = 0; y < cropH; y++) {
                int fromRow = (top + y) * width;
                int toRow = y * cropW;
                for (int x = 0; x < cropW; x++) {
                    int srcIndex = fromRow + left + x;
                    int pixel = sourcePixels[srcIndex];
                    int a = Math.min(Color.alpha(pixel), alpha[srcIndex]);
                    outPixels[toRow + x] = Color.argb(a, Color.red(pixel),
                            Color.green(pixel), Color.blue(pixel));
                }
            }
            Bitmap cut = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888);
            cut.setPixels(outPixels, 0, cropW, 0, 0, cropW, cropH);
            return new Result(cut, width, height, left, top,
                    100f * pixels / (width * height));
        } finally {
            if (ownFrame && !frame.isRecycled()) frame.recycle();
        }
    }

    private static boolean[] dilate(boolean[] src, int w, int h, int radius) {
        boolean[] out = new boolean[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean value = false;
                for (int dy = -radius; dy <= radius && !value; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -radius; dx <= radius; dx++) {
                        int xx = x + dx;
                        if (xx >= 0 && xx < w && src[yy * w + xx]) {
                            value = true;
                            break;
                        }
                    }
                }
                out[y * w + x] = value;
            }
        }
        return out;
    }

    private static boolean[] erode(boolean[] src, int w, int h, int radius) {
        boolean[] out = new boolean[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean value = true;
                for (int dy = -radius; dy <= radius && value; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) { value = false; break; }
                    for (int dx = -radius; dx <= radius; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w || !src[yy * w + xx]) {
                            value = false;
                            break;
                        }
                    }
                }
                out[y * w + x] = value;
            }
        }
        return out;
    }

    private static boolean[] largestComponent(boolean[] src, int w, int h) {
        boolean[] visited = new boolean[src.length];
        int[] queue = new int[src.length];
        int[] best = new int[src.length];
        int bestSize = 0;
        for (int i = 0; i < src.length; i++) {
            if (!src[i] || visited[i]) continue;
            int head = 0, tail = 1;
            queue[0] = i;
            visited[i] = true;
            while (head < tail) {
                int idx = queue[head++];
                int x = idx % w;
                int y = idx / w;
                if (x > 0) tail = visit(idx - 1, src, visited, queue, tail);
                if (x + 1 < w) tail = visit(idx + 1, src, visited, queue, tail);
                if (y > 0) tail = visit(idx - w, src, visited, queue, tail);
                if (y + 1 < h) tail = visit(idx + w, src, visited, queue, tail);
            }
            if (tail > bestSize) {
                bestSize = tail;
                System.arraycopy(queue, 0, best, 0, tail);
            }
        }
        boolean[] out = new boolean[src.length];
        for (int i = 0; i < bestSize; i++) out[best[i]] = true;
        return out;
    }

    private static int visit(int idx, boolean[] src, boolean[] visited,
                             int[] queue, int tail) {
        if (src[idx] && !visited[idx]) {
            visited[idx] = true;
            queue[tail++] = idx;
        }
        return tail;
    }

    private static void fillEnclosedHoles(boolean[] src, int w, int h) {
        // Flood-fill du fond depuis les 4 bords; tout vide non atteint est
        // un trou interieur de la tete et peut etre rempli.
        boolean[] outside = new boolean[src.length];
        int[] queue = new int[src.length];
        int start = 0, end = 0;
        for (int x = 0; x < w; x++) {
            end = addBackground(x, src, outside, queue, end);
            end = addBackground((h - 1) * w + x, src, outside, queue, end);
        }
        for (int y = 1; y < h - 1; y++) {
            end = addBackground(y * w, src, outside, queue, end);
            end = addBackground(y * w + w - 1, src, outside, queue, end);
        }
        while (start < end) {
            int p = queue[start++];
            int x = p % w, y = p / w;
            if (x > 0) end = addBackground(p - 1, src, outside, queue, end);
            if (x + 1 < w) end = addBackground(p + 1, src, outside, queue, end);
            if (y > 0) end = addBackground(p - w, src, outside, queue, end);
            if (y + 1 < h) end = addBackground(p + w, src, outside, queue, end);
        }
        for (int i = 0; i < src.length; i++) {
            if (!src[i] && !outside[i]) src[i] = true;
        }
    }

    private static int addBackground(int idx, boolean[] src, boolean[] visited,
                                     int[] queue, int end) {
        if (!src[idx] && !visited[idx]) {
            visited[idx] = true;
            queue[end++] = idx;
        }
        return end;
    }

    private static int[] soften(boolean[] src, int w, int h, int radius) {
        // Filtre boite separable : O(largeur x hauteur), alpha 0..255.
        int[] hsum = new int[src.length];
        int[] out = new int[src.length];
        int count = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int sum = 0, row = y * w;
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx >= 0 && dx < w && src[row + dx]) sum++;
            }
            for (int x = 0; x < w; x++) {
                hsum[row + x] = sum;
                int left = x - radius;
                int right = x + radius + 1;
                if (left >= 0 && src[row + left]) sum--;
                if (right < w && src[row + right]) sum++;
            }
        }
        int kernelArea = count * count;
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int dy = -radius; dy <= radius; dy++) {
                if (dy >= 0 && dy < h) sum += hsum[dy * w + x];
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = Math.min(255, 255 * sum / kernelArea);
                int top = y - radius;
                int bottom = y + radius + 1;
                if (top >= 0) sum -= hsum[top * w + x];
                if (bottom < h) sum += hsum[bottom * w + x];
            }
        }
        return out;
    }
}
