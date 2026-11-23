package com.example.utechat.feature.chat.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;

import com.example.utechat.R;
import com.example.utechat.app.MediaRepositoryProvider;
import com.example.utechat.data.media.AttachmentPolicy;
import com.example.utechat.domain.model.Message;
import com.example.utechat.domain.repository.MediaRepository;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ChatAttachments {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final ChatActivity activity;
    private final ActivityResultLauncher<String[]> picker;
    private final ActivityResultLauncher<Intent> save;
    private MediaRepository.Transfer download;
    private File export;

    ChatAttachments(ChatActivity activity) {
        this.activity = activity;
        picker = activity.registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onPicked);
        save = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            File file = export;
            export = null;
            if (file == null) return;
            Intent data = result.getData();
            Uri destination = result.getResultCode() == Activity.RESULT_OK && data != null ? data.getData() : null;
            IO.execute(() -> export(file, destination));
        });
    }

    void pick() {
        if (!repository().supportsAttachments()) { toast(R.string.attachment_unavailable); return; }
        picker.launch(new String[]{AttachmentPolicy.PDF, AttachmentPolicy.WORD, AttachmentPolicy.TEXT});
    }

    private void onPicked(Uri uri) {
        if (uri == null) return;
        IO.execute(() -> {
            String name = null;
            long size = -1;
            try (android.database.Cursor cursor = activity.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameColumn >= 0) name = cursor.getString(nameColumn);
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
                }
            } catch (RuntimeException unreadable) { /* Show the attachment validation message below. */ }
            String fileName = name;
            long fileSize = size;
            activity.runOnUiThread(() -> {
                if (!visible()) return;
                if (AttachmentPolicy.typeForName(fileName) == null || fileSize == 0 || fileSize > AttachmentPolicy.MAX_BYTES) {
                    toast(R.string.attachment_invalid);
                } else {
                    activity.sendAttachment(uri, fileName, fileSize);
                }
            });
        });
    }

    void show(Message message) {
        if (download != null || export != null || AttachmentPolicy.typeForName(message.getFileName()) == null) return;
        new MaterialAlertDialogBuilder(activity).setTitle(message.getFileName())
                .setItems(new CharSequence[]{activity.getText(R.string.attachment_open), activity.getText(R.string.attachment_save)},
                        (dialog, index) -> download(message, index == 1))
                .setNegativeButton(R.string.friends_cancel_button, null).show();
    }

    private void download(Message message, boolean saveToDevice) {
        if (!repository().supportsAttachments()) { toast(R.string.attachment_unavailable); return; }
        toast(R.string.attachment_downloading);
        download = repository().downloadAttachment(message.getMediaUrl(), message.getFileName(), message.getFileSize(),
                new MediaRepository.DownloadListener() {
                    @Override public void onSuccess(File file) {
                        download = null;
                        if (!visible()) { remove(file); return; }
                        String type = AttachmentPolicy.typeForName(file.getName());
                        try {
                            if (saveToDevice) {
                                export = file;
                                save.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type)
                                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, file.getName()));
                            } else {
                                Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".friendqr", file);
                                activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                            }
                        } catch (RuntimeException unavailable) {
                            export = null;
                            remove(file);
                            toast(R.string.attachment_open_error);
                        }
                    }
                    @Override public void onError(String error) {
                        download = null;
                        if (visible()) Toast.makeText(activity, error, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void export(File file, Uri destination) {
        if (destination == null) { remove(file); return; }
        int status = R.string.attachment_saved;
        try (java.io.OutputStream output = activity.getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IOException();
            Files.copy(file.toPath(), output);
        } catch (IOException | RuntimeException failure) {
            status = R.string.attachment_save_error;
        } finally { remove(file); }
        int result = status;
        activity.runOnUiThread(() -> { if (visible()) toast(result); });
    }

    void saveState(Bundle state) { if (export != null) state.putString("attachment_export", export.getAbsolutePath()); }
    void restore(Bundle state) {
        String path = state == null ? null : state.getString("attachment_export");
        if (path != null) export = new File(path);
    }

    void close() {
        if (download != null) download.cancel();
        if (activity.isFinishing() && export != null) remove(export);
    }

    private MediaRepository repository() { return MediaRepositoryProvider.get(activity); }
    private boolean visible() { return !activity.isFinishing() && !activity.isDestroyed(); }
    private void toast(int text) { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show(); }
    private static void remove(File file) { file.delete(); file.getParentFile().delete(); }
}
