package com.pv.androidfacefusion;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Etape 33 - Estimation de pose 3D MediaPipe sur DEUX photos fixes.
 *
 * Le maillage 3D indique la pose du VISAGE (yaw/pitch/roll), pas la forme 3D
 * des cheveux. Pour le rendu, les yeux fournissent les points d'ancrage 2D.
 * Si les poses sont tres differentes, la fusion est desactivee plutot que de
 * fabriquer une tete de profil fictive a partir d'une vue de face.
 *
 * A appeler sur le thread de travail, jamais sur le thread UI.
 */
public final class HeadSwapPose3D {
    public static final String MODEL_ASSET = "face_landmarker.task";
    private static final double RAD_TO_DEG = 180d / Math.PI;
    private static final float MAX_DIFF_YAW = 18f;
    private static final float MAX_DIFF_PITCH = 16f;
    private static final float MAX_ABS_YAW = 38f;
    private static final float MAX_ABS_PITCH = 32f;

    private HeadSwapPose3D() { }

    public static final class Match {
        public final boolean compatible;
        public final String warning;
        public final float sourceYaw, targetYaw, sourcePitch, targetPitch;
        public final float sourceRoll, targetRoll, correctionRoll;
        public final float sourceEyeX, sourceEyeY, targetEyeX, targetEyeY;
        public final float sourceChinX, sourceChinY, sourceEyeDistance;
        public final float eyeScale;
        public final int colorR, colorG, colorB;

        private Match(boolean compatible, String warning,
                      Pose source, Pose target, float sourceEyeX,
                      float sourceEyeY, float sourceChinX, float sourceChinY,
                      float sourceEyeDistance, float eyeScale, int dr, int dg, int db) {
            this.compatible = compatible;
            this.warning = warning;
            this.sourceYaw = source.yaw;
            this.targetYaw = target.yaw;
            this.sourcePitch = source.pitch;
            this.targetPitch = target.pitch;
            this.sourceRoll = source.roll;
            this.targetRoll = target.roll;
            this.correctionRoll = clamp(wrap(target.roll - source.roll), -90f, 90f);
            this.sourceEyeX = sourceEyeX;
            this.sourceEyeY = sourceEyeY;
            this.targetEyeX = target.eyeX;
            this.targetEyeY = target.eyeY;
            this.sourceChinX = sourceChinX;
            this.sourceChinY = sourceChinY;
            this.sourceEyeDistance = sourceEyeDistance;
            this.eyeScale = eyeScale;
            this.colorR = dr;
            this.colorG = dg;
            this.colorB = db;
        }
    }

    private static final class Pose {
        float eyeX, eyeY, eyeDistance, roll;
        float chinX, chinY;
        float yaw, pitch;
        float skinR, skinG, skinB;
    }

    public static Match estimate(Context context, Bitmap source, Bitmap target,
                                 HeadSwapAlignmentEngine.Result alignment) throws Exception {
        if (context == null || source == null || target == null || alignment == null
                || source.isRecycled() || target.isRecycled()) {
            throw new IllegalArgumentException("Images non disponibles pour la pose 3D.");
        }
        // Charger explicitement le modele depuis les assets Android. Un tampon
        // direct evite le chemin natif MediaPipe PathToResourceAsFile() qui
        // peut afficher "doesn't have a slash in it" si l'asset est absent.
        // L'erreur devient claire si le fichier n'est pas empaquete.
        final ByteBuffer modelBuffer = loadLandmarkerModel(context);
        FaceLandmarker.FaceLandmarkerOptions options =
                FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder()
                                .setModelAssetBuffer(modelBuffer).build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumFaces(1)
                        .setOutputFacialTransformationMatrixes(true)
                        .setOutputFaceBlendshapes(false)
                        .build();
        Pose src;
        Pose dst;
        try (FaceLandmarker detector = FaceLandmarker.createFromOptions(context, options)) {
            src = inspect(detector, source, "source");
            dst = inspect(detector, target, "cible");
        }
        float sx = alignment.sourceScaledWidth / (float) source.getWidth();
        float sy = alignment.sourceScaledHeight / (float) source.getHeight();
        float eyeX = src.eyeX * sx - alignment.sourceCropLeft;
        float eyeY = src.eyeY * sy - alignment.sourceCropTop;
        float chinX = src.chinX * sx - alignment.sourceCropLeft;
        float chinY = src.chinY * sy - alignment.sourceCropTop;
        float eyeDistance = src.eyeDistance * (float) Math.sqrt(sx * sy);
        if (!Float.isFinite(eyeDistance) || eyeDistance < 5f || dst.eyeDistance < 5f) {
            throw new IllegalStateException("Ecartement des yeux non fiable.");
        }
        float scale = dst.eyeDistance / eyeDistance;
        if (!Float.isFinite(scale) || scale < .1f || scale > 12f) {
            throw new IllegalStateException("Echelle des yeux non fiable.");
        }
        boolean compatible = Math.abs(src.yaw - dst.yaw) <= MAX_DIFF_YAW
                && Math.abs(src.pitch - dst.pitch) <= MAX_DIFF_PITCH
                && Math.abs(src.yaw) <= MAX_ABS_YAW && Math.abs(dst.yaw) <= MAX_ABS_YAW
                && Math.abs(src.pitch) <= MAX_ABS_PITCH
                && Math.abs(dst.pitch) <= MAX_ABS_PITCH;
        String warning = compatible ? "" : "Pose 3D incompatible : choisis une photo "
                + "source avec une direction du visage proche de la video cible. "
                + "Une simple rotation 2D ne peut pas inventer le profil cache.";
        int dr = Math.round(clamp(dst.skinR - src.skinR, -32f, 32f));
        int dg = Math.round(clamp(dst.skinG - src.skinG, -32f, 32f));
        int db = Math.round(clamp(dst.skinB - src.skinB, -32f, 32f));
        return new Match(compatible, warning, src, dst,
                eyeX, eyeY, chinX, chinY, eyeDistance, scale, dr, dg, db);
    }

    /**
     * Retourne un ByteBuffer DIRECT avec le veritable modele .task.
     * BaseOptions exige un tampon direct ou un MappedByteBuffer.
     *
     * La lecture fonctionne meme si l'asset est compresse dans l'APK. Le
     * controle de taille et de signature detecte aussi un pointeur Git LFS
     * a la place du fichier binaire.
     */
    private static ByteBuffer loadLandmarkerModel(Context context) throws IOException {
        final int maxModelBytes = 64 * 1024 * 1024;
        byte[] data;
        try (InputStream input = context.getAssets().open(
                MODEL_ASSET, AssetManager.ACCESS_STREAMING);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[64 * 1024];
            int read;
            while ((read = input.read(block)) != -1) {
                if (read == 0) continue;
                if (output.size() > maxModelBytes - read) {
                    throw new IOException("Modele Face Landmarker trop volumineux "
                            + "(plus de 64 Mo). Verifie le fichier .task.");
                }
                output.write(block, 0, read);
            }
            data = output.toByteArray();
        } catch (IOException e) {
            throw new IOException("Modele Face Landmarker inaccessible dans l'APK : "
                    + MODEL_ASSET + ". Verifie sa presence dans "
                    + "app/src/main/assets/ sur la branche v2-video, "
                    + "puis lance une nouvelle compilation. "
                    + "Detail : " + e.getMessage(), e);
        }
        String header = new String(data, 0, Math.min(80, data.length),
                StandardCharsets.US_ASCII);
        if (header.startsWith("version https://git-lfs.github.com/spec/v1")) {
            throw new IOException("Modele Face Landmarker invalide : le fichier "
                    + "est un pointeur Git LFS, et non le vrai modele binaire. "
                    + "Ajoute le fichier .task original dans les assets.");
        }
        if (data.length < 100 * 1024) {
            throw new IOException("Modele Face Landmarker invalide ou tronque ("
                    + data.length + " octets). Ajoute le vrai fichier binaire "
                    + MODEL_ASSET + " dans app/src/main/assets/.");
        }
        ByteBuffer direct = ByteBuffer.allocateDirect(data.length)
                .order(ByteOrder.nativeOrder());
        direct.put(data);
        direct.rewind();
        return direct;
    }

    private static Pose inspect(FaceLandmarker detector, Bitmap bitmap, String label) {
        FaceLandmarkerResult result = detector.detect(new BitmapImageBuilder(bitmap).build());
        if (result.faceLandmarks().isEmpty()) {
            throw new IllegalStateException("Visage non reconnu dans l'image " + label + ".");
        }
        List<NormalizedLandmark> points = result.faceLandmarks().get(0);
        if (points.size() <= 454) {
            throw new IllegalStateException("Reperes faciaux incomplets (" + label + ").");
        }
        Optional<List<float[]>> transforms = result.facialTransformationMatrixes();
        if (!transforms.isPresent() || transforms.get().isEmpty()
                || transforms.get().get(0).length < 16) {
            throw new IllegalStateException("Matrice de pose 3D indisponible (" + label + ").");
        }
        float[] m = transforms.get().get(0); // MediaPipe: 4x4 COLUMN-MAJOR
        // R = Rz(roll) * Ry(yaw) * Rx(pitch), indices colonne-majeure.
        // L'angle roll d'affichage est mesure directement via les yeux.
        Pose pose = new Pose();
        pose.yaw = (float) (Math.asin(clamp(-m[2], -1f, 1f)) * RAD_TO_DEG);
        pose.pitch = (float) (Math.atan2(m[6], m[10]) * RAD_TO_DEG);
        float leftX = avgX(points, bitmap, 33, 133);
        float leftY = avgY(points, bitmap, 33, 133);
        float rightX = avgX(points, bitmap, 362, 263);
        float rightY = avgY(points, bitmap, 362, 263);
        // En cas de miroir ou de convention differente, remettre gauche->droite.
        if (leftX > rightX) {
            float t = leftX; leftX = rightX; rightX = t;
            t = leftY; leftY = rightY; rightY = t;
        }
        pose.eyeX = (leftX + rightX) * .5f;
        pose.eyeY = (leftY + rightY) * .5f;
        pose.eyeDistance = (float) Math.hypot(rightX - leftX, rightY - leftY);
        pose.roll = (float) (Math.atan2(rightY - leftY, rightX - leftX) * RAD_TO_DEG);
        pose.chinX = points.get(152).x() * bitmap.getWidth();
        pose.chinY = points.get(152).y() * bitmap.getHeight();
        // Quelques plages joue/front : correction seulement moderatee.
        float[] mean = skinMean(points, bitmap);
        pose.skinR = mean[0]; pose.skinG = mean[1]; pose.skinB = mean[2];
        return pose;
    }

    private static float avgX(List<NormalizedLandmark> points, Bitmap b, int a, int c) {
        return (points.get(a).x() + points.get(c).x()) * .5f * b.getWidth();
    }
    private static float avgY(List<NormalizedLandmark> points, Bitmap b, int a, int c) {
        return (points.get(a).y() + points.get(c).y()) * .5f * b.getHeight();
    }

    private static float[] skinMean(List<NormalizedLandmark> points, Bitmap bmp) {
        // Reperes centraux des joues. Eviter un pixel unique et la barbe sombre.
        int[] ids = {50, 101, 205, 280, 330, 425};
        double r = 0, g = 0, b = 0;
        int count = 0;
        int w = bmp.getWidth(), h = bmp.getHeight();
        for (int id : ids) {
            int cx = Math.round(points.get(id).x() * (w - 1));
            int cy = Math.round(points.get(id).y() * (h - 1));
            for (int dy = -2; dy <= 2; dy++) {
                int y = cy + dy;
                if (y < 0 || y >= h) continue;
                for (int dx = -2; dx <= 2; dx++) {
                    int x = cx + dx;
                    if (x < 0 || x >= w) continue;
                    int pixel = bmp.getPixel(x, y);
                    int cr = (pixel >> 16) & 255;
                    int cg = (pixel >> 8) & 255;
                    int cb = pixel & 255;
                    int luminance = (cr * 3 + cg * 6 + cb) / 10;
                    if (luminance < 45 || luminance > 245) continue;
                    r += cr; g += cg; b += cb; count++;
                }
            }
        }
        if (count == 0) return new float[]{128f, 128f, 128f};
        return new float[]{(float) (r / count), (float) (g / count), (float) (b / count)};
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.min(hi, Math.max(lo, v));
    }
    private static float wrap(float degrees) {
        while (degrees > 180f) degrees -= 360f;
        while (degrees < -180f) degrees += 360f;
        return degrees;
    }
}
