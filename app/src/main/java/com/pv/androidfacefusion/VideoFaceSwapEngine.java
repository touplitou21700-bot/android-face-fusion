package com.pv.androidfacefusion;

import android.graphics.Bitmap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android Face Fusion V2 - moteur de face swap pour images issues d'une video.
 *
 * Cette classe prepare UNE SEULE FOIS l'empreinte du visage source, puis
 * reutilise FaceFusionProcessor pour chaque image cible.
 *
 * Elle ne lit pas de video et ne cree pas de MP4 : le lecteur, le decodeur,
 * l'encodeur et la gestion audio seront branches dans des etapes ulterieures.
 *
 * IMPORTANT : utiliser depuis un seul thread de travail, et non du thread UI.
 * Pour la premiere version, une seule personne cible (indice 0) est traitee.
 */
public final class VideoFaceSwapEngine {

    private final FaceDetector faceDetector;
    private final FaceEmbedder faceEmbedder;
    private final FaceFusionProcessor faceFusionProcessor;

    private float[] sourceEmbedding;

    public VideoFaceSwapEngine(
            FaceDetector faceDetector,
            FaceEmbedder faceEmbedder,
            FaceFusionProcessor faceFusionProcessor
    ) {
        if (faceDetector == null || faceEmbedder == null
                || faceFusionProcessor == null) {
            throw new IllegalArgumentException("Les moteurs IA sont requis.");
        }

        this.faceDetector = faceDetector;
        this.faceEmbedder = faceEmbedder;
        this.faceFusionProcessor = faceFusionProcessor;
    }

    /**
     * Detecte le premier visage source et calcule son embedding ArcFace.
     * Le Bitmap source reste la propriete de l'appelant.
     */
    public void prepareSource(Bitmap sourceImage) throws Exception {
        sourceEmbedding = null;
        checkBitmap(sourceImage, "source");

        List<FaceDetector.Face> faces = faceDetector.detectFaces(sourceImage);
        if (faces == null || faces.isEmpty()) {
            throw new Exception("Aucun visage detecte dans l'image source.");
        }

        Bitmap aligned = null;
        try {
            aligned = ImageUtils.alignFace(
                    sourceImage,
                    faces.get(0).landmarks,
                    112
            );
            if (aligned == null) {
                throw new Exception("Impossible d'aligner le visage source.");
            }

            float[] embedding = faceEmbedder.getEmbedding(aligned);
            if (embedding == null || embedding.length == 0) {
                throw new Exception("Empreinte du visage source indisponible.");
            }

            // Garde une copie independante en memoire pour les images suivantes.
            sourceEmbedding = embedding.clone();

        } finally {
            if (aligned != null && aligned != sourceImage
                    && !aligned.isRecycled()) {
                aligned.recycle();
            }
        }
    }

    public boolean isReady() {
        return sourceEmbedding != null && sourceEmbedding.length > 0;
    }

    /**
     * Remplace le premier visage detecte dans une image cible.
     *
     * Le Bitmap d'entree n'est pas recycle par cette classe.
     * Le Bitmap retourne appartient a l'appelant : il doit etre recycle.
     *
     * Pour l'instant, si aucun visage n'est detecte, le moteur photo actuel
     * lance une exception. Le pipeline video gerera ce cas plus tard.
     */
    public Bitmap swapFrame(Bitmap targetFrame) throws Exception {
        if (!isReady()) {
            throw new IllegalStateException(
                    "Appeler prepareSource() avant swapFrame()."
            );
        }
        checkBitmap(targetFrame, "cible");

        // Indice 0 : premier visage de l'image cible.
        Map<Integer, float[]> targetMapping = new HashMap<>();
        targetMapping.put(0, sourceEmbedding);

        Bitmap result = faceFusionProcessor.processFaceFusionWithMapping(
                targetFrame,
                targetMapping
        );

        if (result == null) {
            throw new Exception("Le moteur IA n'a retourne aucune image.");
        }
        return result;
    }

    /** Reinitialise l'empreinte source conservee pour cette session. */
    public void clearSource() {
        sourceEmbedding = null;
    }

    private static void checkBitmap(Bitmap bitmap, String name) {
        if (bitmap == null || bitmap.isRecycled()) {
            throw new IllegalArgumentException(
                    "Image " + name + " absente ou invalide."
            );
        }
    }
}
