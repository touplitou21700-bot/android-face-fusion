package com.pv.androidfacefusion;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Etape 31 - Composition d'une image cible et d'une tete source detouree.
 *
 * Cette classe realise une fusion alpha 2D reelle (SRC_OVER) sur les pixels
 * de la cible. Elle ne remplace ni le moteur MP4, ni un modele de pose 3D.
 * La source est affichee a pleine opacite a l'interieur du masque ; seul son
 * contour transparent est module par le reglage de fondu.
 * Un ajustement tres leger des couleurs pres du bord est optionnel. Il ne
 * remplace pas une harmonisation de lumiere, de pose 3D ou des ombres.
 */
public final class HeadSwapCompositor {

    public static final int MAX_FEATHER_PX = 24;
    private static final int DISTANCE_INFINITY = 100_000;

    private HeadSwapCompositor() { }

    /** Les ajustements sont exprimes dans le repere de la tete cible. */
    public static final class Settings {
        public final float offsetX;
        public final float offsetY;
        public final float sizeFactor;
        public final float rotationDegrees;
        public final int featherPx;
        /** Intensite du raccord de couleur local au bord : 0..100. */
        public final int edgeColorPercent;

        public Settings(float offsetX, float offsetY, float sizeFactor,
                        float rotationDegrees, int featherPx) {
            this(offsetX, offsetY, sizeFactor, rotationDegrees, featherPx, 0);
        }

        public Settings(float offsetX, float offsetY, float sizeFactor,
                        float rotationDegrees, int featherPx, int edgeColorPercent) {
            this.offsetX = clampFinite(offsetX, -1f, 1f, 0f);
            this.offsetY = clampFinite(offsetY, -1f, 1f, 0f);
            this.sizeFactor = clampFinite(sizeFactor, .5f, 1.5f, 1f);
            this.rotationDegrees = clampFinite(rotationDegrees, -90f, 90f, 0f);
            this.featherPx = Math.max(0, Math.min(MAX_FEATHER_PX, featherPx));
            this.edgeColorPercent = Math.max(0, Math.min(100, edgeColorPercent));
        }

        public static Settings defaults() {
            return new Settings(0f, 0f, 1f, 0f, 12, 45);
        }
    }

    /**
     * Conserve les informations du masque pour rendre les apercus rapidement.
     * L'objet Result de HeadSwapAlignmentEngine appartient toujours a l'appelant.
     * Utiliser Prepared uniquement sur le thread UI apres sa creation.
     */
    public static final class Prepared implements AutoCloseable {
        public final HeadSwapAlignmentEngine.Result alignment;
        private final int[] sourcePixels;
        private final int[] edgeDistances;
        private final int[] targetPixels;
        private final int targetWidth;
        private final int targetHeight;
        private final int width;
        private final int height;
        private Bitmap featheredBitmap;
        private Bitmap edgeMatchedBitmap;
        private int cachedFeather = -1;
        private Settings cachedSettings;
        private boolean closed;

        private Prepared(HeadSwapAlignmentEngine.Result alignment,
                         int[] sourcePixels, int[] edgeDistances,
                         int[] targetPixels) {
            this.alignment = alignment;
            this.sourcePixels = sourcePixels;
            this.edgeDistances = edgeDistances;
            this.targetPixels = targetPixels;
            width = alignment.sourceHead.getWidth();
            height = alignment.sourceHead.getHeight();
            targetWidth = alignment.targetFrame.getWidth();
            targetHeight = alignment.targetFrame.getHeight();
        }

        private Bitmap getHead(int featherPx) {
            if (closed) throw new IllegalStateException("Composition deja fermee.");
            if (featherPx <= 0) return alignment.sourceHead;
            if (cachedFeather == featherPx && featheredBitmap != null
                    && !featheredBitmap.isRecycled()) return featheredBitmap;

            // Recule progressivement l'opacite pres du contour du masque.
            // Aucun pixel totalement transparent n'est rendu opaque.
            int[] softened = new int[sourcePixels.length];
            float radiusTen = featherPx * 10f;
            for (int i = 0; i < sourcePixels.length; i++) {
                int color = sourcePixels[i];
                int alpha = Color.alpha(color);
                if (alpha == 0) {
                    softened[i] = 0;
                    continue;
                }
                float normalized = Math.max(0f,
                        Math.min(1f, (edgeDistances[i] - 5f) / radiusTen));
                // Courbe douce, plus propre qu'une coupure lineaire.
                float smooth = normalized * normalized * (3f - 2f * normalized);
                int newAlpha = Math.max(0, Math.min(255, Math.round(alpha * smooth)));
                softened[i] = (newAlpha << 24) | (color & 0x00FFFFFF);
            }
            Bitmap updated = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            updated.setPixels(softened, 0, width, 0, 0, width, height);
            Bitmap old = featheredBitmap;
            featheredBitmap = updated;
            cachedFeather = featherPx;
            if (old != null && !old.isRecycled()) old.recycle();
            return updated;
        }

        /**
         * Ajuste legerement les teintes pres du bord selon les pixels sous la
         * tete source. Le coeur de l'image et les cheveux restent inchanges.
         * Correction plafonnee a +/- 28 par canal, reversible via le curseur.
         * Cachee pour ne pas recreer les bitmaps si rien n'a change.
         */
        private Bitmap getHeadForPlacement(Settings settings, float cx, float cy) {
            Bitmap base = getHead(settings.featherPx);
            if (settings.edgeColorPercent <= 0) return base;
            if (sameSettings(cachedSettings, settings)
                    && edgeMatchedBitmap != null && !edgeMatchedBitmap.isRecycled()) {
                return edgeMatchedBitmap;
            }
            float displayScale = alignment.baseScale * settings.sizeFactor;
            float radians = (float) Math.toRadians(settings.rotationDegrees);
            float cos = (float) Math.cos(radians);
            float sin = (float) Math.sin(radians);
            float maxEdgeDistance = Math.max(10f, settings.featherPx + 12f);
            float strength = settings.edgeColorPercent / 100f * .70f;
            int[] altered = new int[sourcePixels.length];
            base.getPixels(altered, 0, width, 0, 0, width, height);
            for (int y = 0; y < height; y++) {
                float dy = (y + .5f - height * .5f) * displayScale;
                for (int x = 0; x < width; x++) {
                    int i = y * width + x;
                    int pixel = altered[i];
                    if (Color.alpha(pixel) == 0) continue;
                    float edgeDistance = edgeDistances[i] / 10f;
                    float rim = 1f - Math.min(1f, Math.max(0f,
                            (edgeDistance - 1f) / maxEdgeDistance));
                    if (rim <= 0f) continue;
                    float dx = (x + .5f - width * .5f) * displayScale;
                    int tx = Math.round(cx + cos * dx - sin * dy);
                    int ty = Math.round(cy + sin * dx + cos * dy);
                    if (tx < 0 || ty < 0 || tx >= targetWidth || ty >= targetHeight) {
                        continue;
                    }
                    int underneath = targetPixels[ty * targetWidth + tx];
                    float weight = strength * rim;
                    int r = adjust(Color.red(pixel), Color.red(underneath), weight);
                    int g = adjust(Color.green(pixel), Color.green(underneath), weight);
                    int b = adjust(Color.blue(pixel), Color.blue(underneath), weight);
                    altered[i] = Color.argb(Color.alpha(pixel), r, g, b);
                }
            }
            Bitmap adjusted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            adjusted.setPixels(altered, 0, width, 0, 0, width, height);
            Bitmap old = edgeMatchedBitmap;
            edgeMatchedBitmap = adjusted;
            cachedSettings = settings;
            if (old != null && !old.isRecycled()) old.recycle();
            return adjusted;
        }

        private static int adjust(int source, int target, float strength) {
            int limitedDifference = Math.max(-28, Math.min(28, target - source));
            return Math.max(0, Math.min(255,
                    source + Math.round(limitedDifference * strength)));
        }

        private static boolean sameSettings(Settings a, Settings b) {
            return a != null && a.featherPx == b.featherPx
                    && a.edgeColorPercent == b.edgeColorPercent
                    && a.offsetX == b.offsetX && a.offsetY == b.offsetY
                    && a.sizeFactor == b.sizeFactor
                    && a.rotationDegrees == b.rotationDegrees;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (featheredBitmap != null && !featheredBitmap.isRecycled()) {
                featheredBitmap.recycle();
            }
            featheredBitmap = null;
            if (edgeMatchedBitmap != null && !edgeMatchedBitmap.isRecycled()) {
                edgeMatchedBitmap.recycle();
            }
            edgeMatchedBitmap = null;
            cachedSettings = null;
        }
    }

    /** A effectuer sur le thread de travail : lecture du masque et calcul de distance. */
    public static Prepared prepare(HeadSwapAlignmentEngine.Result alignment) {
        if (alignment == null || alignment.sourceHead == null
                || alignment.targetFrame == null
                || alignment.sourceHead.isRecycled()
                || alignment.targetFrame.isRecycled()) {
            throw new IllegalArgumentException("Alignement ou bitmap indisponible.");
        }
        Bitmap head = alignment.sourceHead;
        int width = head.getWidth();
        int height = head.getHeight();
        int[] pixels = new int[width * height];
        head.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] distances = distanceToTransparent(pixels, width, height);
        int tw = alignment.targetFrame.getWidth();
        int th = alignment.targetFrame.getHeight();
        int[] targetPixels = new int[tw * th];
        alignment.targetFrame.getPixels(targetPixels, 0, tw, 0, 0, tw, th);
        return new Prepared(alignment, pixels, distances, targetPixels);
    }

    /**
     * Fusionne l'image cible avec la tete pour obtenir un NOUVEAU Bitmap.
     * L'appelant devient proprietaire du bitmap renvoye et doit le recycler.
     */
    public static Bitmap compose(Prepared prepared, Settings settings) {
        if (prepared == null || settings == null) {
            throw new IllegalArgumentException("Parametres de composition manquants.");
        }
        Bitmap target = prepared.alignment.targetFrame;
        Bitmap output = Bitmap.createBitmap(target.getWidth(), target.getHeight(),
                Bitmap.Config.ARGB_8888);
        try {
            draw(new Canvas(output), prepared, settings);
            return output;
        } catch (RuntimeException error) {
            output.recycle();
            throw error;
        }
    }

    /**
     * Dessine une fusion identique a compose() dans le repere natif de la cible.
     * Le composant d'aperçu applique a part son zoom d'affichage.
     */
    public static void draw(Canvas canvas, Prepared prepared, Settings settings) {
        if (canvas == null || prepared == null || settings == null) {
            throw new IllegalArgumentException("Rendu incomplet.");
        }
        if (prepared.closed) throw new IllegalStateException("Rendu deja ferme.");
        HeadSwapAlignmentEngine.Result alignment = prepared.alignment;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG
                | Paint.DITHER_FLAG);
        paint.setAlpha(255);

        // La cible est dessinee normalement, puis la tete est fusionnee dessus
        // avec les alphas du masque (compositing Android SRC_OVER par defaut).
        canvas.drawBitmap(alignment.targetFrame, 0f, 0f, paint);

        RectF bounds = alignment.targetHeadBounds;
        float cx = bounds.centerX() + settings.offsetX * bounds.width() * .6f;
        float cy = bounds.centerY() + settings.offsetY * bounds.height() * .6f;
        float headW = alignment.sourceHead.getWidth() * alignment.baseScale
                * settings.sizeFactor;
        float headH = alignment.sourceHead.getHeight() * alignment.baseScale
                * settings.sizeFactor;
        RectF placement = new RectF(cx - headW / 2f, cy - headH / 2f,
                cx + headW / 2f, cy + headH / 2f);

        Bitmap head = prepared.getHeadForPlacement(settings, cx, cy);
        canvas.save();
        try {
            canvas.rotate(settings.rotationDegrees, cx, cy);
            canvas.drawBitmap(head, null, placement, paint);
        } finally {
            canvas.restore();
        }
    }

    private static float clampFinite(float value, float min, float max, float fallback) {
        if (!Float.isFinite(value)) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    /** Distance approx. du pixel opaque au plus proche pixel transparent. */
    private static int[] distanceToTransparent(int[] pixels, int w, int h) {
        int[] dist = new int[pixels.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int index = row + x;
                if (Color.alpha(pixels[index]) == 0) {
                    dist[index] = 0;
                } else if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    // L'exterieur du bitmap est implicitement transparent.
                    dist[index] = 10;
                } else {
                    dist[index] = DISTANCE_INFINITY;
                }
            }
        }
        // Approximation de la distance euclidienne par deux balayages
        // (10 horizontal/vertical, 14 diagonal).
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (x > 0) dist[i] = Math.min(dist[i], dist[i - 1] + 10);
                if (y > 0) dist[i] = Math.min(dist[i], dist[i - w] + 10);
                if (x > 0 && y > 0) dist[i] = Math.min(dist[i], dist[i - w - 1] + 14);
                if (x < w - 1 && y > 0) dist[i] = Math.min(dist[i], dist[i - w + 1] + 14);
            }
        }
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = y * w + x;
                if (x < w - 1) dist[i] = Math.min(dist[i], dist[i + 1] + 10);
                if (y < h - 1) dist[i] = Math.min(dist[i], dist[i + w] + 10);
                if (x < w - 1 && y < h - 1) dist[i] = Math.min(dist[i], dist[i + w + 1] + 14);
                if (x > 0 && y < h - 1) dist[i] = Math.min(dist[i], dist[i + w - 1] + 14);
            }
        }
        return dist;
    }
}
