package com.example.utechat.data.media;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.utechat.BuildConfig;
import com.example.utechat.domain.repository.MediaRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.android.gms.tasks.Tasks;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Storage emulator smoke test. Requires a signed-in Firebase user, but does not read or write
 * Firestore. It leaves a small test-only object in the local emulator because rules disallow deletion.
 */
@RunWith(AndroidJUnit4.class)
public final class FirebaseMediaRepositoryEmulatorSmokeTest {
    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jxioAAAAASUVORK5CYII=";

    @Test
    public void uploadsAndDownloadsTestImageFromStorageEmulator() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FirebaseAuth auth = FirebaseAuth.getInstance();
        auth.useEmulator(BuildConfig.STORAGE_EMULATOR_HOST, BuildConfig.AUTH_EMULATOR_PORT);
        com.google.firebase.auth.AuthResult authResult = Tasks.await(auth.signInAnonymously(), 30, TimeUnit.SECONDS);
        assertNotNull(authResult.getUser());
        String uid = authResult.getUser().getUid();

        String conversationId = "emulator-smoke-" + UUID.randomUUID();
        String messageId = "test-" + UUID.randomUUID();
        byte[] expected = Base64.decode(PNG_BASE64, Base64.DEFAULT);
        File source = new File(context.getCacheDir(), "utechat-live-storage-smoke.png");
        File downloaded = new File(context.getCacheDir(), "utechat-live-storage-smoke-download.png");
        try (FileOutputStream output = new FileOutputStream(source)) { output.write(expected); }

        FirebaseMediaRepository repository = new FirebaseMediaRepository(context);
        AtomicReference<String> uploadedPath = new AtomicReference<>();
        AtomicReference<Exception> uploadError = new AtomicReference<>();
        CountDownLatch uploadDone = new CountDownLatch(1);
        MediaRepository.Transfer upload = repository.uploadImage(Uri.fromFile(source), conversationId, uid,
                messageId, new MediaRepository.UploadListener() {
                    @Override public void onProgress(int percent) { }
                    @Override public void onSuccess(String path, long size, String contentType) {
                        assertEquals(expected.length, size);
                        assertEquals("image/png", contentType);
                        uploadedPath.set(path);
                        uploadDone.countDown();
                    }
                    @Override public void onError(String message) {
                        uploadError.set(new IllegalStateException("Emulator upload failed: " + message));
                        uploadDone.countDown();
                    }
                });
        if (!uploadDone.await(90, TimeUnit.SECONDS)) {
            upload.cancel();
            fail("Timed out waiting for Storage emulator upload. Check emulator host/port.");
        }
        if (uploadError.get() != null) throw uploadError.get();
        assertNotNull(uploadedPath.get());
        assertTrue(uploadedPath.get().contains("/" + uid + "/" + messageId));

        AtomicReference<File> downloadedFile = new AtomicReference<>();
        AtomicReference<Exception> downloadError = new AtomicReference<>();
        CountDownLatch downloadDone = new CountDownLatch(1);
        repository.downloadImage(uploadedPath.get(), downloaded, new MediaRepository.DownloadListener() {
            @Override public void onSuccess(File file) { downloadedFile.set(file); downloadDone.countDown(); }
            @Override public void onError(String message) {
                downloadError.set(new IllegalStateException("Emulator download failed: " + message));
                downloadDone.countDown();
            }
        });
        assertTrue("Timed out waiting for Storage emulator download.", downloadDone.await(90, TimeUnit.SECONDS));
        if (downloadError.get() != null) throw downloadError.get();
        assertNotNull(downloadedFile.get());
        assertArrayEquals(expected, Files.readAllBytes(downloadedFile.get().toPath()));
        source.delete();
        downloaded.delete();
    }

}
