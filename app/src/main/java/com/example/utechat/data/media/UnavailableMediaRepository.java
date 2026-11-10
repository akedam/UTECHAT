package com.example.utechat.data.media;

import android.net.Uri;
import com.example.utechat.domain.repository.MediaRepository;
import java.io.File;

/** Used when Firebase has not been configured with a Storage bucket. */
public final class UnavailableMediaRepository implements MediaRepository {
    private final String message;
    public UnavailableMediaRepository(String message) { this.message = message; }
    @Override public Transfer uploadImage(Uri source,String conversationId,String uid,String messageId,UploadListener listener) {
        listener.onError(message); return () -> {};
    }
    @Override public Transfer downloadImage(String path,File file,DownloadListener listener) {
        listener.onError(message); return () -> {};
    }
    @Override public File getImageCacheFile(String path){return null;}
    @Override public boolean isAvailable(){return false;}
    @Override public String getUnavailableReason(){return message;}
}
