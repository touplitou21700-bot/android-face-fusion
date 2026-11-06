package com.pv.androidfacefusion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;

/**
 * Android Face Fusion V2 - Etape 29 : geometrie d'alignement PRELIMINAIRE.
 *
 * Le moteur extrait les zones cheveux + peau du visage sur la photo source
 * et sur une image cible, puis calcule une position et une echelle initiales.
 *
 * IMPORTANT : pas de points de repere 3D, pas de correction de pose et pas
 * de remplacement de tete dans une video. C'est un apercu de superposition.
 * Appeler prepare() uniquement depuis un thread de travail.
 */
public final class HeadSwapAlignmentEngine {

    private HeadSwapAlignmentEngine() { }

    public static final class Result implements AutoCloseable {
        /** Image cible deja redimensionnee, propriete du Result. */
        public final Bitmap targetFrame;
        /** Tete source avec transparence, propriete du Result. */
        public final Bitmap sourceHead;
        /** Boite de la tete detectee sur la cible, en pixels du targetFrame. */
        public final RectF targetHeadBounds;
        /** Multiplicateur naturel pour afficher sourceHead a la taille de la cible. */
        public final float baseScale;
        public final float sourceHeadPercent;
        public final float targetHeadPercent;

        private boolean closed;

        private Result(Bitmap targetFrame, Bitmap sourceHead, RectF bounds,
                       float baseScale, float sourceHeadPercent,
                       float targetHeadPercent) {
            this.targetFrame = targetFrame;
            this.sourceHead = sourceHead;
            this.targetHeadBounds = bounds;
            this.baseScale = baseScale;
            this.sourceHeadPercent = sourceHeadPercent;
            this.targetHeadPercent = targetHeadPercent;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (sourceHead != null && !sourceHead.isRecycled()) sourceHead.recycle();
            if (targetFrame != null && !targetFrame.isRecycled()) targetFrame.recycle();
        }
    }

    /**
     * @param targetFrame Bitmap ARGB_8888 <= 640px, dont la propriete est
     *                    transferee au resultat UNIQUEMENT en cas de succes.
     *                    En cas d'echec, l'appelant doit le recycler.
     */
    public static Result prepare(Context context, Bitmap sourceBitmap,
                                 Bitmap targetFrame) throws Exception {
        if (context == null || sourceBitmap == null || targetFrame == null
                || sourceBitmap.isRecycled() || targetFrame.isRecycled()) {
            throw new IllegalArgumentException("Photo source ou image cible invalide.");
        }
        if (Math.max(targetFrame.getWidth(), targetFrame.getHeight()) > 640) {
            throw new IllegalArgumentException("L'image cible doit etre limitee a 640 pixels.");
        }

        HeadSwapSourceExtractor.Result src = null;
        HeadSwapSourceExtractor.Result dst = null;
        boolean success = false;
        try {
            // La segmentation reste sequentielle pour limiter la memoire.
            src = HeadSwapSourceExtractor.extract(context, sourceBitmap);
            dst = HeadSwapSourceExtractor.extract(context, targetFrame);

            if (src.cropWidth < 8 || src.cropHeight < 8
                    || dst.cropWidth < 8 || dst.cropHeight < 8) {
                throw new IllegalStateException("Tete source ou cible trop petite.");
            }

            // L'extracteur renvoie des coordonnees sur une image plafonnee a
            // 640 px. Ici, targetFrame a deja ete plafonnee a 640 px.
            // On conserve quand meme un facteur de conversion explicite.
            float rx = targetFrame.getWidth() / (float) dst.sourceWidth;
            float ry = targetFrame.getHeight() / (float) dst.sourceHeight;
            RectF bounds = new RectF(
                    dst.cropLeft * rx,
                    dst.cropTop * ry,
                    (dst.cropLeft + dst.cropWidth) * rx,
                    (dst.cropTop + dst.cropHeight) * ry);

            // Moyenne geometrique des ratios : respecte les proportions de
            // sourceHead (aucun etirement independant sur X et Y).
            float xRatio = bounds.width() / src.cropWidth;
            float yRatio = bounds.height() / src.cropHeight;
            float baseScale = (float) Math.sqrt(xRatio * yRatio);
            if (!Float.isFinite(baseScale) || baseScale <= 0f) {
                throw new IllegalStateException("Echelle d'alignement invalide.");
            }

            Result result = new Result(targetFrame, src.headBitmap, bounds,
                    baseScale, src.headPercent, dst.headPercent);
            success = true;
            return result;
        } finally {
            // L'image extraite sur la cible ne sert qu'a estimer sa boite.
            if (dst != null && dst.headBitmap != null && !dst.headBitmap.isRecycled()) {
                dst.headBitmap.recycle();
            }
            // La tete source est transferee a Result uniquement en cas de succes.
            if (!success && src != null && src.headBitmap != null
                    && !src.headBitmap.isRecycled()) {
                src.headBitmap.recycle();
            }
        }
    }
}
