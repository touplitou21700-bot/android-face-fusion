package com.pv.androidfacefusion;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/**
 * Etape 33 - Composition guidee par les yeux et la pose 3D (image fixe).
 *
 * Contrairement au collage precedent, le centre de la tete est determine par
 * les yeux, pas par la boite cheveux+peau. Le contenu SOUS le menton est
 * attenue pour eviter de coller une touffe sombre sur le cou/l'epaule.
 * Le changement de teinte reste limite au visage, pas a toute la coiffure.
 * Ne sait pas generer les zones cachees d'une tete tournee en 3D.
 */
public final class HeadSwapPoseCompositor {
    private HeadSwapPoseCompositor() { }

    public static final class Prepared implements AutoCloseable {
        public final HeadSwapAlignmentEngine.Result alignment;
        public final HeadSwapPose3D.Match pose;
        private final int[] original;
        private final int[] distance;
        private final int w, h;
        private Bitmap rendered;
        private int feather = -1;
        private int color = -1;
        private boolean trim = true;
        private boolean closed;

        private Prepared(HeadSwapAlignmentEngine.Result alignment,
                         HeadSwapPose3D.Match pose, int[] pixels, int[] distance) {
            this.alignment = alignment;
            this.pose = pose;
            this.original = pixels;
            this.distance = distance;
            this.w = alignment.sourceHead.getWidth();
            this.h = alignment.sourceHead.getHeight();
        }

        private Bitmap getBitmap(HeadSwapCompositor.Settings settings,
                                 boolean trimNeck) {
            if (closed) throw new IllegalStateException("Apercu deja ferme.");
            if (rendered != null && !rendered.isRecycled()
                    && feather == settings.featherPx
                    && color == settings.edgeColorPercent && trim == trimNeck) {
                return rendered;
            }
            float downX = pose.sourceChinX - pose.sourceEyeX;
            float downY = pose.sourceChinY - pose.sourceEyeY;
            float chinDist = (float) Math.hypot(downX, downY);
            if (chinDist < 5f) {downX = 0f; downY = 1f; chinDist = 1f;}
            else {downX /= chinDist; downY /= chinDist;}
            float sideX = -downY, sideY = downX;
            float eyeDist = Math.max(8f, pose.sourceEyeDistance);
            float trimStart = chinDist + .12f * eyeDist;
            float trimEnd = chinDist + .43f * eyeDist;
            float strength = settings.edgeColorPercent / 100f * .80f;
            int[] out = new int[original.length];
            float featherPixels = Math.max(1, settings.featherPx);
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int i = row + x;
                    int p = original[i];
                    int alpha = Color.alpha(p);
                    if (alpha <= 0) continue;
                    float dy = (y + .5f) - pose.sourceEyeY;
                    float dx = (x + .5f) - pose.sourceEyeX;
                    float belowEyes = dx * downX + dy * downY;
                    float side = dx * sideX + dy * sideY;
                    float opacity = 1f;
                    if (trimNeck && belowEyes > trimStart) {
                        opacity = 1f - smooth((belowEyes - trimStart)
                                / Math.max(1f, trimEnd - trimStart));
                    }
                    if (settings.featherPx > 0) {
                        // Erosion douce a l'interieur du masque d'origine.
                        float d = distance[i] / 10f;
                        opacity *= smooth((d - .4f) / featherPixels);
                    }
                    int a = Math.min(255, Math.max(0, Math.round(alpha * opacity)));
                    if (a <= 0) continue;
                    int r = Color.red(p), g = Color.green(p), b = Color.blue(p);
                    if (strength > 0f) {
                        // Une ellipse orientee selon la ligne yeux->menton.
                        // Le reste, notamment les cheveux, conserve sa couleur.
                        float u = side / (1.28f * eyeDist);
                        float v = (belowEyes - .44f * chinDist) / (1.03f * chinDist);
                        float d2 = u * u + v * v;
                        if (d2 < 1f) {
                            float weight = strength * smooth((1f - d2) / .40f);
                            r = clampColor(r + Math.round(pose.colorR * weight));
                            g = clampColor(g + Math.round(pose.colorG * weight));
                            b = clampColor(b + Math.round(pose.colorB * weight));
                        }
                    }
                    out[i] = Color.argb(a, r, g, b);
                }
            }
            Bitmap updated = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            updated.setPixels(out, 0, w, 0, 0, w, h);
            Bitmap old = rendered;
            rendered = updated;
            feather = settings.featherPx;
            color = settings.edgeColorPercent;
            trim = trimNeck;
            if (old != null && !old.isRecycled()) old.recycle();
            return updated;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (rendered != null && !rendered.isRecycled()) rendered.recycle();
            rendered = null;
        }
    }

    public static Prepared prepare(HeadSwapAlignmentEngine.Result alignment,
                                   HeadSwapPose3D.Match pose) {
        if (alignment == null || pose == null || alignment.sourceHead == null
                || alignment.sourceHead.isRecycled()) {
            throw new IllegalArgumentException("Extraction ou pose indisponible.");
        }
        int w = alignment.sourceHead.getWidth();
        int h = alignment.sourceHead.getHeight();
        int[] pixels = new int[w * h];
        alignment.sourceHead.getPixels(pixels, 0, w, 0, 0, w, h);
        return new Prepared(alignment, pose, pixels, distances(pixels, w, h));
    }

    /**
     * Mode normal: une pose incompatible laisse uniquement la cible.
     * Conserve cette methode pour la compatibilite avec l'ancienne interface.
     */
    public static Bitmap compose(Prepared prepared,
                                 HeadSwapCompositor.Settings settings,
                                 boolean trimNeck) {
        return compose(prepared, settings, trimNeck, false);
    }

    /**
     * force2D=true permet un DIAGNOSTIC de superposition en cas de pose 3D
     * differente. Il ne reconstruit pas la vue manquante de la tete.
     */
    public static Bitmap compose(Prepared prepared,
                                 HeadSwapCompositor.Settings settings,
                                 boolean trimNeck, boolean force2D) {
        Bitmap bitmap = Bitmap.createBitmap(prepared.alignment.targetFrame.getWidth(),
                prepared.alignment.targetFrame.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            draw(new Canvas(bitmap), prepared, settings, trimNeck, force2D);
            return bitmap;
        } catch (RuntimeException error) {
            bitmap.recycle();
            throw error;
        }
    }

    public static void draw(Canvas canvas, Prepared prepared,
                            HeadSwapCompositor.Settings settings,
                            boolean trimNeck) {
        draw(canvas, prepared, settings, trimNeck, false);
    }

    public static void draw(Canvas canvas, Prepared prepared,
                            HeadSwapCompositor.Settings settings,
                            boolean trimNeck, boolean force2D) {
        if (canvas == null || prepared == null || settings == null) {
            throw new IllegalArgumentException("Rendu incomplet.");
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG
                | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        HeadSwapAlignmentEngine.Result a = prepared.alignment;
        canvas.drawBitmap(a.targetFrame, 0f, 0f, paint);
        // Avant: une pose 3D differente donnait silencieusement l'image cible.
        // Le mode 2D force est explicite et ne concerne qu'une image de test.
        if (!prepared.pose.compatible && !force2D) return;
        HeadSwapPose3D.Match pose = prepared.pose;
        float cx = pose.targetEyeX + settings.offsetX * a.targetHeadBounds.width() * .6f;
        float cy = pose.targetEyeY + settings.offsetY * a.targetHeadBounds.height() * .6f;
        float scale = pose.eyeScale * settings.sizeFactor;
        canvas.save();
        try {
            canvas.translate(cx, cy);
            canvas.rotate(settings.rotationDegrees);
            canvas.scale(scale, scale);
            canvas.drawBitmap(prepared.getBitmap(settings, trimNeck),
                    -pose.sourceEyeX, -pose.sourceEyeY, paint);
        } finally {
            canvas.restore();
        }
    }

    private static int[] distances(int[] pixels, int w, int h) {
        int[] d = new int[pixels.length];
        final int FAR = 1000000;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x;
            d[i] = Color.alpha(pixels[i]) < 12 || x == 0 || y == 0
                    || x == w - 1 || y == h - 1 ? 0 : FAR;
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x;
            if (x > 0) d[i] = Math.min(d[i], d[i - 1] + 10);
            if (y > 0) d[i] = Math.min(d[i], d[i - w] + 10);
            if (x > 0 && y > 0) d[i] = Math.min(d[i], d[i - w - 1] + 14);
            if (x + 1 < w && y > 0) d[i] = Math.min(d[i], d[i - w + 1] + 14);
        }
        for (int y = h - 1; y >= 0; y--) for (int x = w - 1; x >= 0; x--) {
            int i = y * w + x;
            if (x + 1 < w) d[i] = Math.min(d[i], d[i + 1] + 10);
            if (y + 1 < h) d[i] = Math.min(d[i], d[i + w] + 10);
            if (x + 1 < w && y + 1 < h) d[i] = Math.min(d[i], d[i + w + 1] + 14);
            if (x > 0 && y + 1 < h) d[i] = Math.min(d[i], d[i + w - 1] + 14);
        }
        return d;
    }

    private static float smooth(float v) {
        v = Math.max(0f, Math.min(1f, v));
        return v * v * (3f - 2f * v);
    }
    private static int clampColor(int c) {return Math.max(0, Math.min(255, c));}
}
