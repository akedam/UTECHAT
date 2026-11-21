package com.example.utechat.feature.moments;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

final class TinPhotos {
    static final int SIZE = 960;
    enum Tone {
        NORMAL("Tự nhiên", 1, 1, 1), PEACH("Hồng đào", 1.12f, 1.02f, .91f),
        SKY("Xanh mây", .89f, 1.04f, 1.13f), MONO("Đen trắng", 1, 1, 1);
        final String label;
        final float red, green, blue;
        Tone(String label, float red, float green, float blue) {
            this.label = label; this.red = red; this.green = green; this.blue = blue;
        }
        ColorMatrixColorFilter filter() {
            ColorMatrix color = new ColorMatrix();
            if (this == MONO) color.setSaturation(0);
            else color.setScale(red, green, blue, 1f);
            return new ColorMatrixColorFilter(color);
        }
    }

    private TinPhotos() {}

    static byte[] compose(List<File> shots, TinFilters.Filter frame, Tone tone, Bitmap artwork, TinFaces detector) throws Exception {
        Bitmap composite = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(composite);
            canvas.drawColor(frame.fill);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(tone.filter());
            for (int index = 0; index < shots.size(); index++) {
                Bitmap source = decode(shots.get(index));
                try { drawCell(canvas, source, frame, index, paint); }
                finally { source.recycle(); }
            }
            drawArtwork(canvas, frame, artwork, paint);
            if (frame.anchor != null) {
                var faces = detector.detect(composite);
                if (faces.isEmpty()) throw new TinFaces.NoFace();
                TinFaces.place(canvas, artwork, frame, faces);
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                for (int quality : new int[]{82, 68, 52}) {
                    output.reset();
                    if (composite.compress(Bitmap.CompressFormat.JPEG, quality, output)
                            && output.size() <= MomentApi.MAX_PHOTO_BYTES) return output.toByteArray();
                }
            }
            throw new IOException("Photo too large");
        } finally { composite.recycle(); }
    }

    static void drawFrame(Canvas canvas, List<Bitmap> photos, int liveCell, TinFilters.Filter filter, Tone tone, Bitmap artwork) {
        canvas.save();
        if (liveCell >= 0 && liveCell < filter.cells) canvas.clipOutRect(filter.cell(liveCell));
        canvas.drawColor(filter.fill);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(tone.filter());
        for (int index = 0; index < filter.cells; index++) {
            Bitmap source = index < photos.size() ? photos.get(index) : null;
            if (source == null) {
                Paint blank = new Paint();
                blank.setColor(0xFFE7EFF8);
                canvas.drawRect(filter.cell(index), blank);
            } else drawCell(canvas, source, filter, index, paint);
        }
        canvas.restore();
        drawArtwork(canvas, filter, artwork, paint);
    }

    private static void drawCell(Canvas canvas, Bitmap source, TinFilters.Filter filter, int index, Paint paint) {
        var rect = filter.cell(index);
        float scale = Math.max(rect.width() / source.getWidth(), rect.height() / source.getHeight());
        int w = Math.max(1, Math.min(source.getWidth(), Math.round(rect.width() / scale)));
        int h = Math.max(1, Math.min(source.getHeight(), Math.round(rect.height() / scale)));
        int left = (source.getWidth() - w) / 2;
        int top = (source.getHeight() - h) / 2;
        canvas.drawBitmap(source, new Rect(left, top, left + w, top + h), rect, paint);
    }

    private static void drawArtwork(Canvas canvas, TinFilters.Filter filter, Bitmap artwork, Paint paint) {
        if (artwork != null && filter.anchor == null) {
            paint.setColorFilter(null);
            canvas.drawBitmap(artwork, null, new android.graphics.RectF(0, 0, SIZE, SIZE), paint);
        }
    }

    static Bitmap decode(File source) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(source), (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int largest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                if (largest > 1440) {
                    decoder.setTargetSize(Math.max(1, (int) (info.getSize().getWidth() * 1440L / largest)),
                            Math.max(1, (int) (info.getSize().getHeight() * 1440L / largest)));
                }
            });
        }
        var options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getPath(), options);
        options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / 1440);
        options.inJustDecodeBounds = false;
        Bitmap decoded = BitmapFactory.decodeFile(source.getPath(), options);
        if (decoded == null) throw new IOException("Invalid photo");
        int orientation = 1;
        try {
            var exif = new ExifInterface(source.getPath());
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        } catch (IOException noExif) {
            // An album image can decode successfully without EXIF metadata.
        }
        Matrix transform = new Matrix();
        if (orientation == 2 || orientation == 5 || orientation == 7) transform.setScale(-1, 1);
        if (orientation == 4) transform.setScale(1, -1);
        if (orientation == 5 || orientation == 8) transform.postRotate(270);
        if (orientation == 6 || orientation == 7) transform.postRotate(90);
        if (orientation == 3) transform.postRotate(180);
        Bitmap oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), transform, true);
        if (oriented != decoded) decoded.recycle();
        return oriented;
    }
}
