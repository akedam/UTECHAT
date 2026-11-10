package com.example.utechat.domain.repository;

import android.net.Uri;
import java.io.File;

/** Private image objects stored under the authenticated conversation membership policy. */
public interface MediaRepository {
    interface Transfer { void cancel(); }
    interface UploadListener {
        void onProgress(int percent);
        void onSuccess(String storagePath, long sizeBytes, String contentType);
        void onError(String message);
    }
    interface DownloadListener {
        void onSuccess(File file);
        void onError(String message);
    }
    Transfer uploadImage(Uri source, String conversationId, String uid, String messageId,
                        UploadListener listener);
    Transfer downloadImage(String storagePath, File destination, DownloadListener listener);
    File getImageCacheFile(String storagePath);
    boolean isAvailable();
    String getUnavailableReason();
}
