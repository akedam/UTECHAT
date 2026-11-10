package com.example.utechat.data.media;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImageUploadPolicyTest {
    @Test public void acceptsOnlyPositiveImagesUpToEightMebibytes() {
        assertFalse(ImageUploadPolicy.isWithinSizeLimit(0));
        assertTrue(ImageUploadPolicy.isWithinSizeLimit(1));
        assertTrue(ImageUploadPolicy.isWithinSizeLimit(8L * 1024L * 1024L));
        assertFalse(ImageUploadPolicy.isWithinSizeLimit(8L * 1024L * 1024L + 1));
    }

    @Test public void normalizesAliasesAndRejectsUnsupportedTypes() {
        assertTrue(ImageUploadPolicy.isSupportedMimeType("IMAGE/JPEG"));
        assertTrue(ImageUploadPolicy.isSupportedMimeType("image/jpg"));
        assertTrue(ImageUploadPolicy.isSupportedMimeType("image/png"));
        assertTrue(ImageUploadPolicy.isSupportedMimeType("image/webp"));
        assertFalse(ImageUploadPolicy.isSupportedMimeType("image/gif"));
        assertFalse(ImageUploadPolicy.isSupportedMimeType("image/heic"));
        assertFalse(ImageUploadPolicy.isSupportedMimeType(null));
    }

    @Test public void checksFileSignaturesInsteadOfTrustingMimeType() {
        assertTrue(ImageUploadPolicy.hasValidSignature(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}, "image/jpeg"));
        assertFalse(ImageUploadPolicy.hasValidSignature(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}, "image/png"));
        assertTrue(ImageUploadPolicy.hasValidSignature(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, "image/png"));
        assertTrue(ImageUploadPolicy.hasValidSignature(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}, "image/webp"));
        assertFalse(ImageUploadPolicy.hasValidSignature(new byte[]{'R', 'I', 'F', 'F'}, "image/webp"));
    }

    @Test public void dimensionCapsAreBoundedToSafeDecodeBudget() {
        assertTrue(ImageUploadPolicy.MAX_IMAGE_DIMENSION == 12_000);
        assertTrue(ImageUploadPolicy.MAX_IMAGE_PIXELS == 40_000_000L);
        assertTrue(ImageUploadPolicy.safeDecodeSampleSize(8_000, 5_000) > 0);
        assertTrue(ImageUploadPolicy.safeDecodeSampleSize(8_000, 5_000) >= 4);
        assertTrue(ImageUploadPolicy.safeDecodeSampleSize(0, 1) == 0);
        assertTrue(ImageUploadPolicy.safeDecodeSampleSize(12_001, 1) == 0);
        assertTrue(ImageUploadPolicy.safeDecodeSampleSize(8_192, 5_000) == 0);
    }
}
