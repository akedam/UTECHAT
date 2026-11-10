package com.example.utechat.data.media;

import java.util.Locale;

/** Shared, SDK-independent checks for image uploads and downloads. */
final class ImageUploadPolicy {
    static final long MAX_IMAGE_BYTES = 8L * 1024L * 1024L;
    static final int MAX_IMAGE_DIMENSION = 12_000;
    static final long MAX_IMAGE_PIXELS = 40_000_000L;

    private ImageUploadPolicy() { }

    static String normalizeMimeType(String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(Locale.ROOT).trim();
        return "image/jpg".equals(normalized) ? "image/jpeg" : normalized;
    }

    static boolean isSupportedMimeType(String value) {
        String mime = normalizeMimeType(value);
        return "image/jpeg".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime);
    }

    static boolean isWithinSizeLimit(long size) {
        return size > 0 && size <= MAX_IMAGE_BYTES;
    }

    static int safeDecodeSampleSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION
                || (long) width * (long) height > MAX_IMAGE_PIXELS) return 0;
        int sample = 1;
        while ((long) (width / sample) * (long) (height / sample) > 1_000_000L) sample *= 2;
        return sample;
    }

    static boolean hasValidSignature(byte[] bytes, String mimeType) {
        if (bytes == null) return false;
        String mime = normalizeMimeType(mimeType);
        if ("image/jpeg".equals(mime)) {
            return bytes.length >= 3 && u(bytes[0]) == 0xff && u(bytes[1]) == 0xd8 && u(bytes[2]) == 0xff;
        }
        if ("image/png".equals(mime)) {
            return bytes.length >= 8 && u(bytes[0]) == 0x89 && bytes[1] == 'P'
                    && bytes[2] == 'N' && bytes[3] == 'G' && u(bytes[4]) == 0x0d
                    && u(bytes[5]) == 0x0a && u(bytes[6]) == 0x1a && u(bytes[7]) == 0x0a;
        }
        return "image/webp".equals(mime) && bytes.length >= 12 && bytes[0] == 'R'
                && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private static int u(byte value) { return value & 0xff; }
}
