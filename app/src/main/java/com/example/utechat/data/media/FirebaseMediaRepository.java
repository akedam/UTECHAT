package com.example.utechat.data.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.example.utechat.BuildConfig;
import com.example.utechat.R;
import com.example.utechat.domain.repository.MediaRepository;
import com.google.firebase.storage.FileDownloadTask;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageException;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Uploads bounded image files from app-private cache and stores only protected Storage paths. */
public final class FirebaseMediaRepository implements MediaRepository {
    public static final long MAX_IMAGE_BYTES = ImageUploadPolicy.MAX_IMAGE_BYTES;
    private static final String CACHE_DIR = "utechat-media";
    private final Context context;
    private final FirebaseStorage storage;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public FirebaseMediaRepository(Context context) {
        this.context = context.getApplicationContext();
        storage = FirebaseStorage.getInstance();
        if (BuildConfig.USE_STORAGE_EMULATOR) {
            storage.useEmulator(BuildConfig.STORAGE_EMULATOR_HOST, BuildConfig.STORAGE_EMULATOR_PORT);
        }
        cleanupOldOutbox();
    }

    @Override public Transfer uploadImage(Uri source, String conversationId, String uid,
                                          String messageId, UploadListener listener) {
        TransferJob job = new TransferJob();
        if (source == null || !validSegment(conversationId) || !validSegment(uid) || !validSegment(messageId)) {
            postError(job, listener, context.getString(R.string.image_upload_invalid));
            return job;
        }
        String contentType = ImageUploadPolicy.normalizeMimeType(resolveMimeType(source));
        if (!ImageUploadPolicy.isSupportedMimeType(contentType)) {
            postError(job, listener, context.getString(R.string.image_type_unsupported));
            return job;
        }
        String path = "conversations/" + conversationId + "/" + uid + "/" + messageId;
        StorageReference reference = storage.getReference().child(path);
        reference.getMetadata().addOnSuccessListener(metadata -> {
            if (!job.active) return;
            String storedConversationId = metadata.getCustomMetadata("conversationId");
            String storedOwnerUid = metadata.getCustomMetadata("ownerUid");
            String storedMessageId = metadata.getCustomMetadata("messageId");
            if (ImageUploadPolicy.isWithinSizeLimit(metadata.getSizeBytes()) && metadata.getContentType() != null
                    && contentType.equals(ImageUploadPolicy.normalizeMimeType(metadata.getContentType()))
                    && conversationId.equals(storedConversationId)
                    && uid.equals(storedOwnerUid) && messageId.equals(storedMessageId)) {
                job.active = false;
                listener.onSuccess(path, metadata.getSizeBytes(), ImageUploadPolicy.normalizeMimeType(metadata.getContentType()));
            } else {
                job.active = false;
                listener.onError(context.getString(R.string.image_upload_invalid));
            }
        }).addOnFailureListener(error -> {
            if (!job.active) return;
            if (error instanceof StorageException
                    && ((StorageException) error).getErrorCode() != StorageException.ERROR_OBJECT_NOT_FOUND) {
                job.active = false;
                listener.onError(storageError(error));
                return;
            }
            job.future = io.submit(() -> {
                File staged = stage(source, messageId, contentType);
                if (!job.active) return;
                if (staged == null) {
                    job.active = false;
                    main.post(() -> listener.onError(context.getString(R.string.image_upload_invalid)));
                    return;
                }
                StorageMetadata metadata = new StorageMetadata.Builder().setContentType(contentType)
                        .setCustomMetadata("conversationId", conversationId)
                        .setCustomMetadata("ownerUid", uid)
                        .setCustomMetadata("messageId", messageId).build();
                UploadTask upload = reference.putFile(Uri.fromFile(staged), metadata);
                job.upload = upload;
                upload.addOnProgressListener(snapshot -> {
                    if (!job.active) return;
                    int percent = snapshot.getTotalByteCount() <= 0 ? 0
                            : (int) (100L * snapshot.getBytesTransferred() / snapshot.getTotalByteCount());
                    listener.onProgress(Math.max(0, Math.min(100, percent)));
                }).addOnSuccessListener(snapshot -> {
                    if (!job.active) return;
                    job.active = false;
                    long size = snapshot.getMetadata() == null ? staged.length() : snapshot.getMetadata().getSizeBytes();
                    staged.delete();
                    listener.onSuccess(path, size, contentType);
                }).addOnFailureListener(uploadError -> {
                    if (!job.active) return;
                    job.active = false;
                    listener.onError(storageError(uploadError));
                });
            });
        });
        return job;
    }

    @Override public Transfer downloadImage(String storagePath, File destination, DownloadListener listener) {
        TransferJob job = new TransferJob();
        if (!validStoragePath(storagePath) || destination == null) {
            postDownloadError(job, listener);
            return job;
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            postDownloadError(job, listener);
            return job;
        }
        storage.getReference().child(storagePath).getMetadata().addOnSuccessListener(metadata -> {
            String type = ImageUploadPolicy.normalizeMimeType(metadata.getContentType());
            if (!ImageUploadPolicy.isWithinSizeLimit(metadata.getSizeBytes())
                    || !ImageUploadPolicy.isSupportedMimeType(type)) {
                finishDownloadWithError(job, destination, listener);
                return;
            }
            FileDownloadTask task = storage.getReference().child(storagePath).getFile(destination);
        job.downloadFile = destination;
            job.download = task;
            task.addOnSuccessListener(snapshot -> {
                if (!job.active) { destination.delete(); return; }
                job.future = io.submit(() -> {
                    boolean valid = isValidImageFile(destination, type);
                    main.post(() -> {
                        if (!job.active) { destination.delete(); return; }
                        job.active = false;
                        if (valid) listener.onSuccess(destination);
                        else {
                            destination.delete();
                            listener.onError(context.getString(R.string.image_download_error));
                        }
                    });
                });
            }).addOnFailureListener(error -> finishDownloadWithError(job, destination, listener));
        }).addOnFailureListener(error -> finishDownloadWithError(job, destination, listener));
        return job;
    }

    @Override public File getImageCacheFile(String storagePath) {
        if (!validStoragePath(storagePath)) return null;
        File directory = new File(context.getCacheDir(), CACHE_DIR + "/downloads");
        if (!directory.exists() && !directory.mkdirs()) return null;
        return new File(directory, sha256(storagePath) + ".image");
    }

    private File stage(Uri source, String messageId, String type) {
        File directory = new File(context.getCacheDir(), CACHE_DIR + "/outbox");
        if (!directory.exists() && !directory.mkdirs()) return null;
        File target = new File(directory, messageId + ".source");
        if (target.isFile() && ImageUploadPolicy.isWithinSizeLimit(target.length())
                && isValidImageFile(target, type)) return target;
        target.delete();
        File temporary = new File(directory, messageId + ".tmp");
        long count = 0;
        try (InputStream input = context.getContentResolver().openInputStream(source);
             FileOutputStream output = new FileOutputStream(temporary)) {
            if (input == null) { temporary.delete(); return null; }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > MAX_IMAGE_BYTES) { temporary.delete(); return null; }
                output.write(buffer, 0, read);
            }
            output.flush();
            if (!ImageUploadPolicy.isWithinSizeLimit(count) || !isValidImageFile(temporary, type)) { temporary.delete(); return null; }
            if (!temporary.renameTo(target)) { temporary.delete(); return null; }
            return target;
        } catch (Exception error) {
            temporary.delete();
            return null;
        }
    }

    private boolean isValidImageFile(File file, String type) {
        if (!ImageUploadPolicy.isSupportedMimeType(type) || !ImageUploadPolicy.isWithinSizeLimit(file.length())) return false;
        try (InputStream input = new FileInputStream(file)) {
            byte[] header = new byte[12];
            int count = input.read(header);
            if (count < header.length || !ImageUploadPolicy.hasValidSignature(header, type)) return false;
        } catch (Exception ignored) { }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = ImageUploadPolicy.safeDecodeSampleSize(bounds.outWidth, bounds.outHeight);
            if (sample == 0) return false;
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            Bitmap preview = BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
            if (preview == null) return false;
            preview.recycle();
            return true;
        } catch (RuntimeException ignored) { return false; }
    }

    private void cleanupOldOutbox() {
        File directory = new File(context.getCacheDir(), CACHE_DIR + "/outbox");
        File[] files = directory.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 3L * 24L * 60L * 60L * 1000L;
        for (File file : files) if (file.lastModified() < cutoff) file.delete();
    }

    private boolean validStoragePath(String path) {
        if (path == null || !path.startsWith("conversations/")) return false;
        String[] segments = path.split("/");
        return segments.length == 4 && "conversations".equals(segments[0])
                && validSegment(segments[1]) && validSegment(segments[2]) && validSegment(segments[3]);
    }
    private String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private boolean validSegment(String value) { return value != null && !value.isEmpty() && !value.contains("/"); }
    private void finishDownloadWithError(TransferJob job, File destination, DownloadListener listener) {
        main.post(() -> {
            if (!job.active) return;
            job.active = false;
            destination.delete();
            listener.onError(context.getString(R.string.image_download_error));
        });
    }
    private String storageError(Exception error) {
        if (error instanceof StorageException) {
            int code = ((StorageException) error).getErrorCode();
            if (code == StorageException.ERROR_NOT_AUTHENTICATED) return context.getString(R.string.auth_session_expired);
            if (code == StorageException.ERROR_NOT_AUTHORIZED) return context.getString(R.string.image_permission_error);
            if (code == StorageException.ERROR_QUOTA_EXCEEDED || code == 402) return context.getString(R.string.image_storage_setup_error);
        }
        return context.getString(R.string.image_upload_error);
    }
    private String resolveMimeType(Uri source) {
        String mimeType = context.getContentResolver().getType(source);
        if (mimeType != null || source == null || !"file".equalsIgnoreCase(source.getScheme())) return mimeType;
        String name = source.getLastPathSegment();
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return null;
    }
    private void postError(TransferJob job, UploadListener listener, String message) {
        main.post(() -> { if (job.active) { job.active = false; listener.onError(message); } });
    }
    private void postDownloadError(TransferJob job, DownloadListener listener) {
        main.post(() -> { if (job.active) { job.active = false; listener.onError(context.getString(R.string.image_download_error)); } });
    }
    private final class TransferJob implements Transfer {
        volatile boolean active = true;
        volatile Future<?> future;
        volatile UploadTask upload;
        volatile FileDownloadTask download;
        volatile File downloadFile;
        @Override public void cancel() {
            active = false;
            if (future != null) future.cancel(true);
            if (upload != null) upload.cancel();
            if (download != null) download.cancel();
            if (downloadFile != null) downloadFile.delete();
        }
    }
    @Override public boolean isAvailable() { return true; }
    @Override public String getUnavailableReason() { return ""; }
}
