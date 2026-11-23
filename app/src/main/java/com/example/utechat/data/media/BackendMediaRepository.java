package com.example.utechat.data.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.example.utechat.R;
import com.example.utechat.core.common.AppLanguage;
import com.example.utechat.data.backend.BackendClient;
import com.example.utechat.domain.repository.MediaRepository;
import com.google.firebase.auth.FirebaseAuth;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Images stay private in Supabase. The Firebase-authenticated backend checks conversation membership. */
public final class BackendMediaRepository implements MediaRepository {
    private static final ExecutorService IO=Executors.newFixedThreadPool(2);
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    public BackendMediaRepository(Context context) {this.context=context.getApplicationContext();}
    @Override public Transfer uploadImage(Uri source,String conversationId,String uid,String messageId,UploadListener listener) {
        Job job=new Job();
        job.future=IO.submit(() -> {
            try {
                if(source==null || !segment(conversationId) || !segment(uid) || !segment(messageId))throw new Exception();
                String type=ImageUploadPolicy.normalizeMimeType(context.getContentResolver().getType(source));
                byte[] bytes;
                try(InputStream input=context.getContentResolver().openInputStream(source)) {bytes=readBounded(input);}
                if ("file".equals(source.getScheme()) && type.isEmpty()) {
                    for (String candidate : new String[]{"image/jpeg", "image/png", "image/webp"}) {
                        if (validImage(bytes, candidate)) { type = candidate; break; }
                    }
                }
                if(!validImage(bytes,type))throw new Exception();
                final String contentType=type;
                if(!job.active)return;
                String path="conversations/"+conversationId+"/"+uid+"/"+messageId;
                main.post(() -> {if(job.active)listener.onProgress(0);});
                BackendClient.Response response=BackendClient.request("PUT",route(path),bytes,type,true);
                if(!response.ok())throw new Exception();
                org.json.JSONObject result=response.json();
                if(!path.equals(result.getString("path")) || result.getLong("size")!=bytes.length
                        || !type.equals(result.getString("contentType")))throw new Exception();
                main.post(() -> {if(job.active){job.active=false;listener.onSuccess(path,bytes.length,contentType);}});
            } catch(Exception error) {
                main.post(() -> {if(job.active){job.active=false;listener.onError(AppLanguage.text(context, R.string.image_upload_error));}});
            }
        });
        return job;
    }
    @Override public Transfer downloadImage(String path,File destination,DownloadListener listener) {
        return download(path, destination, false, listener);
    }

    @Override public boolean supportsVoice() { return isAvailable(); }
    @Override public boolean supportsAttachments() { return isAvailable(); }

    @Override public Transfer uploadAttachment(Uri source, String name, String conversationId, String uid,
                                               String messageId, UploadListener listener) {
        Job job = new Job();
        job.future = IO.submit(() -> {
            try {
                String type = AttachmentPolicy.typeForName(name);
                if (type == null || !segment(conversationId) || !segment(uid) || !segment(messageId)) throw new IllegalArgumentException();
                byte[] bytes;
                try (InputStream input = context.getContentResolver().openInputStream(source)) {
                    bytes = readBounded(input, AttachmentPolicy.MAX_BYTES);
                }
                if (bytes.length == 0) { failUpload(job, listener, R.string.attachment_invalid); return; }
                if (!job.active) return;
                String path = "conversations/" + conversationId + "/" + uid + "/" + messageId;
                var response = BackendClient.request("PUT", attachmentRoute(path) + "&name=" + URLEncoder.encode(name, "UTF-8"),
                        bytes, type, true, percent -> main.post(() -> { if (job.active) listener.onProgress(percent); }));
                if (!response.ok()) {
                    int error = response.status == 400 || response.status == 413 ? R.string.attachment_invalid
                            : response.status == 404 ? R.string.attachment_unavailable : R.string.attachment_send_error;
                    failUpload(job, listener, error);
                    return;
                }
                var result = response.json();
                if (!path.equals(result.getString("path")) || result.getLong("size") != bytes.length
                        || !type.equals(result.getString("contentType"))) throw new IllegalStateException();
                main.post(() -> {
                    if (!job.active) return;
                    job.active = false;
                    listener.onSuccess(path, bytes.length, type);
                });
            } catch (AttachmentTooLarge error) {
                failUpload(job, listener, R.string.attachment_invalid);
            } catch (Exception error) {
                failUpload(job, listener, R.string.attachment_send_error);
            }
        });
        return job;
    }

    @Override public Transfer downloadAttachment(String path, String name, long size, DownloadListener listener) {
        Job job = new Job();
        job.future = IO.submit(() -> {
            File file = null;
            try {
                String type = AttachmentPolicy.typeForName(name);
                String uid = FirebaseAuth.getInstance().getUid();
                if (uid == null || !validPath(path) || type == null || size <= 0 || size > AttachmentPolicy.MAX_BYTES) throw new IllegalArgumentException();
                var response = BackendClient.request("GET", attachmentRoute(path), null, null, true);
                if (!response.ok() || !type.equals(response.contentType) || response.body.length != size
                        || !uid.equals(FirebaseAuth.getInstance().getUid())) throw new IllegalStateException();
                if (!job.active) return;
                File base = new File(context.getCacheDir(), "chat-attachments");
                if (!base.isDirectory() && !base.mkdirs() && !base.isDirectory()) throw new java.io.IOException();
                File directory = java.nio.file.Files.createTempDirectory(base.toPath(), "download-").toFile();
                file = new File(directory, name);
                try (FileOutputStream output = new FileOutputStream(file)) { output.write(response.body); }
                File downloaded = file;
                main.post(() -> {
                    if (!job.active || !uid.equals(FirebaseAuth.getInstance().getUid())) {
                        downloaded.delete(); downloaded.getParentFile().delete(); return;
                    }
                    job.active = false;
                    listener.onSuccess(downloaded);
                });
            } catch (Exception error) {
                if (file != null) { file.delete(); file.getParentFile().delete(); }
                main.post(() -> {
                    if (!job.active) return;
                    job.active = false;
                    listener.onError(AppLanguage.text(context, R.string.attachment_download_error));
                });
            }
        });
        return job;
    }

    @Override public Transfer uploadVoice(File source, String conversationId, String uid, String messageId,
                                          UploadListener listener) {
        Job job = new Job();
        job.future = IO.submit(() -> {
            try {
                if (!segment(conversationId) || !segment(uid) || !segment(messageId)) throw new IllegalArgumentException();
                byte[] bytes;
                try (InputStream input = new java.io.FileInputStream(source)) {
                    bytes = readBounded(input, 512 * 1024);
                }
                String path = "conversations/" + conversationId + "/" + uid + "/" + messageId;
                if (!job.active) return;
                var response = BackendClient.request("PUT", voiceRoute(path), bytes, "audio/mp4", true);
                if (!response.ok()) {
                    failUpload(job, listener, response.status == 404 ? R.string.voice_unavailable
                            : response.status == 403 ? R.string.contact_unavailable : R.string.voice_upload_error);
                    return;
                }
                var result = response.json();
                if (!path.equals(result.getString("path")) || result.getLong("size") != bytes.length
                        || !"audio/mp4".equals(result.getString("contentType"))) throw new IllegalStateException();
                main.post(() -> {
                    if (job.active) {
                        job.active = false;
                        listener.onSuccess(path, bytes.length, "audio/mp4");
                    }
                });
            } catch (Exception error) {
                failUpload(job, listener, R.string.voice_upload_error);
            }
        });
        return job;
    }

    private void failUpload(Job job, UploadListener listener, int error) {
        main.post(() -> {
            if (job.active) {
                job.active = false;
                listener.onError(AppLanguage.text(context, error));
            }
        });
    }

    @Override public Transfer downloadVoice(String path, DownloadListener listener) {
        return download(path, cacheFile(path, ".m4a"), true, listener);
    }

    private Transfer download(String path, File destination, boolean voice, DownloadListener listener) {
        Job job=new Job();
        job.future=IO.submit(() -> {
            File temporary=null;
            try {
                if(!validPath(path) || destination==null)throw new Exception();
                BackendClient.Response response=BackendClient.request("GET",voice ? voiceRoute(path) : route(path),null,null,true);
                String type = response.contentType == null ? "" : response.contentType.split(";")[0];
                boolean valid = voice ? "audio/mp4".equals(type) && response.body.length > 0
                        && response.body.length <= 512 * 1024
                        : validImage(response.body, ImageUploadPolicy.normalizeMimeType(type));
                if(!response.ok() || !valid)throw new Exception();
                if(!job.active)return;
                File parent=destination.getParentFile();
                if(parent==null || (!parent.isDirectory() && !parent.mkdirs()))throw new Exception();
                temporary=File.createTempFile("download-",".tmp",parent);
                try(FileOutputStream output=new FileOutputStream(temporary)){output.write(response.body);}
                if(!job.active){temporary.delete();return;}
                if(destination.exists() && !destination.delete())throw new Exception();
                if(!temporary.renameTo(destination))throw new Exception();
                main.post(() -> {if(job.active){job.active=false;listener.onSuccess(destination);}});
            } catch(Exception error) {
                if(temporary!=null)temporary.delete();
                main.post(() -> {if(job.active){job.active=false;listener.onError(AppLanguage.text(context,
                        voice ? R.string.voice_play_error : R.string.image_download_error));}});
            }
        });
        return job;
    }
    @Override public File getImageCacheFile(String path) {
        return cacheFile(path, ".image");
    }

    private File cacheFile(String path, String extension) {
        if(!validPath(path))return null;
        try {
            String uid=FirebaseAuth.getInstance().getUid();if(uid==null)return null;
            File directory=new File(context.getCacheDir(),"backend-media-"+digest(uid));
            if(!directory.isDirectory() && !directory.mkdirs())return null;
            return new File(directory,digest(path)+extension);
        } catch(Exception ignored){return null;}
    }
    private static String route(String path) throws Exception {return "/api/media?path="+URLEncoder.encode(path,"UTF-8");}
    private static String voiceRoute(String path) throws Exception {return "/api/voice?path="+URLEncoder.encode(path,"UTF-8");}
    private static String attachmentRoute(String path) throws Exception { return "/api/attachment?path=" + URLEncoder.encode(path, "UTF-8"); }
    private static boolean segment(String value) {return value!=null && value.matches("[A-Za-z0-9_-]{1,128}");}
    private static boolean validPath(String value) {
        if(value==null)return false;String[] parts=value.split("/",-1);
        return parts.length==4 && "conversations".equals(parts[0]) && segment(parts[1]) && segment(parts[2]) && segment(parts[3]);
    }
    private static byte[] readBounded(InputStream input) throws Exception {
        return readBounded(input, ImageUploadPolicy.MAX_IMAGE_BYTES);
    }
    private static byte[] readBounded(InputStream input, long limit) throws Exception {
        if(input==null)throw new Exception();ByteArrayOutputStream output=new ByteArrayOutputStream();
        byte[] buffer=new byte[32768];int count;
        while((count=input.read(buffer))!=-1){
            if(output.size()+count>limit)throw new AttachmentTooLarge();output.write(buffer,0,count);
        }
        return output.toByteArray();
    }
    private static boolean validImage(byte[] bytes,String type) {
        if(!ImageUploadPolicy.isWithinSizeLimit(bytes.length) || !ImageUploadPolicy.hasValidSignature(bytes,type))return false;
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        int sample=ImageUploadPolicy.safeDecodeSampleSize(bounds.outWidth,bounds.outHeight);if(sample==0)return false;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(bitmap==null)return false;bitmap.recycle();return true;
    }
    private static String digest(String text) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));StringBuilder result=new StringBuilder();
        for(byte value:bytes)result.append(String.format(Locale.ROOT,"%02x",value));return result.toString();
    }
    private static class Job implements Transfer {
        volatile boolean active=true;volatile Future<?> future;
        @Override public void cancel(){active=false;if(future!=null)future.cancel(true);}
    }
    private static final class AttachmentTooLarge extends java.io.IOException { }
    @Override public boolean isAvailable(){return BackendClient.configured();}
    @Override public String getUnavailableReason(){return AppLanguage.text(context, R.string.image_storage_setup_error);}
}
