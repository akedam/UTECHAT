package com.example.utechat.feature.moments;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Detects face positions for stickers locally. No identities or images leave this layer. */
final class TinFaces implements AutoCloseable {
    static final class NoFace extends Exception { }
    private com.google.mlkit.vision.face.FaceDetector detector;
    private boolean closed;

    List<Face> detect(Bitmap image) throws Exception {
        var source = InputImage.fromBitmap(image, 0);
        return Tasks.await(submit(source), 8, TimeUnit.SECONDS);
    }

    private synchronized com.google.android.gms.tasks.Task<List<Face>> submit(InputImage source) {
        if (closed) throw new IllegalStateException("Face detector closed");
        if (detector == null) detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(.13f).build());
        return detector.process(source);
    }

    static void place(Canvas canvas, Bitmap sticker, TinFilters.Filter filter, List<Face> faces) {
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (Face face : faces) {
            var box = face.getBoundingBox();
            if (box.width() <= 0 || box.height() <= 0) continue;
            float width = box.width() * filter.scale;
            float height = width * sticker.getHeight() / sticker.getWidth();
            boolean hair = "hair".equals(filter.anchor);
            var mouth = face.getLandmark(FaceLandmark.MOUTH_BOTTOM);
            float x = box.exactCenterX();
            float y = box.top + box.height() * (hair ? .06f : .72f);
            if (!hair && mouth != null) {
                x = mouth.getPosition().x;
                y = mouth.getPosition().y - box.height() * .035f;
            }
            if ("chin".equals(filter.anchor)) y += box.height() * .17f;
            canvas.save();
            canvas.rotate(-face.getHeadEulerAngleZ(), x, y);
            canvas.drawBitmap(sticker, null, new RectF(x - width / 2, y - height / 2, x + width / 2, y + height / 2), paint);
            canvas.restore();
        }
    }

    @Override public synchronized void close() {
        closed = true;
        if (detector != null) detector.close();
    }
}
