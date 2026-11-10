package com.example.utechat.feature.chat.viewmodel;

import android.app.Application;
import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.utechat.R;
import com.example.utechat.app.MessageRepositoryProvider;
import com.example.utechat.app.MediaRepositoryProvider;
import com.example.utechat.core.common.Resource;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.domain.model.Message;
import com.example.utechat.domain.model.MessagePage;
import com.example.utechat.domain.model.User;
import com.example.utechat.domain.repository.MessageRepository;
import com.example.utechat.domain.repository.MediaRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;

public class ChatViewModel extends AndroidViewModel {
    public static final int MESSAGE_PAGE_SIZE = 30;
    public static final int MAX_MESSAGE_LENGTH = 5000;

    private final MessageRepository repository;
    private final MediaRepository mediaRepository;
    private final MutableLiveData<Resource<List<Message>>> messages = new MutableLiveData<>();
    private final MutableLiveData<Boolean> loadingOlder = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> hasMoreHistory = new MutableLiveData<>(false);
    private final MutableLiveData<String> sendError = new MutableLiveData<>();
    private final Set<String> sendingIds = new HashSet<>();
    private final Map<String, Message> localOutgoing = new LinkedHashMap<>();
    private final Map<String, Uri> pendingImageUris = new LinkedHashMap<>();
    private final Map<String, MediaRepository.Transfer> activeUploads = new LinkedHashMap<>();
    private final String currentUserId;
    private final String currentUserName;
    private MessageRepository.Subscription subscription;
    private List<Message> olderMessages = new ArrayList<>();
    private List<Message> latestMessages = new ArrayList<>();
    private String conversationId;
    private String peerName;
    private boolean loadingHistory;
    private String failedHistoryCursor;
    private long listenerGeneration;
    private long lastSendRequestAt;
    private String lastSendText;
    private boolean cleared;

    public ChatViewModel(@NonNull Application application) {
        super(application);
        repository = MessageRepositoryProvider.get(application);
        mediaRepository = MediaRepositoryProvider.get(application);
        User user = AuthRepositoryProvider.get(application).getCurrentUser();
        currentUserId = user == null ? null : user.getUid();
        currentUserName = user == null || user.getDisplayName() == null
                ? application.getString(R.string.mock_student_name) : user.getDisplayName();
    }

    public LiveData<Resource<List<Message>>> getMessages() { return messages; }
    public LiveData<Boolean> getLoadingOlder() { return loadingOlder; }
    public LiveData<Boolean> getHasMoreHistory() { return hasMoreHistory; }
    public LiveData<String> getSendError() { return sendError; }
    public boolean isMock() { return repository.isMock(); }
    public boolean canSendImage() { return mediaRepository.isAvailable() && !repository.isMock(); }
    public String getCurrentUserId() { return currentUserId; }
    public String getCurrentUserName() { return currentUserName; }

    public void init(String conversationId, String peerName) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            messages.setValue(Resource.error(getApplication().getString(R.string.chat_invalid_conversation)));
            return;
        }
        if (conversationId.equals(this.conversationId) && subscription != null) return;
        removeSubscription();
        this.conversationId = conversationId;
        this.peerName = peerName == null || peerName.trim().isEmpty()
                ? getApplication().getString(R.string.conversation_unknown_title) : peerName;
        olderMessages = new ArrayList<>();
        latestMessages = new ArrayList<>();
        localOutgoing.clear();
        pendingImageUris.clear();
        loadingHistory = false;
        loadingOlder.setValue(false);
        hasMoreHistory.setValue(false);
        sendError.setValue(null);
        messages.setValue(Resource.loading());
        attachListener();
    }

    private void attachListener() {
        long generation = ++listenerGeneration;
        subscription = repository.listenToLatest(conversationId, currentUserId, currentUserName,
                this.peerName, MESSAGE_PAGE_SIZE, new MessageRepository.Listener() {
                    @Override
                    public void onMessages(List<Message> latest) {
                        if (generation != listenerGeneration) return;
                        latestMessages = latest == null ? new ArrayList<>() : new ArrayList<>(latest);
                        for (Message received : latestMessages) {
                            Message local = localOutgoing.get(received.getId());
                            if (local != null && received.getDeliveryStatus() != Message.DeliveryStatus.SENDING) {
                                localOutgoing.remove(received.getId());
                            }
                        }
                        if (latestMessages.size() >= MESSAGE_PAGE_SIZE) hasMoreHistory.setValue(true);
                        else if (olderMessages.isEmpty()) hasMoreHistory.setValue(false);
                        publishMessages();
                    }

                    @Override
                    public void onError(String message) {
                        if (generation != listenerGeneration) return;
                        if (messages.getValue() == null || messages.getValue().getData() == null) {
                            messages.setValue(Resource.error(message == null || message.trim().isEmpty()
                                    ? getApplication().getString(R.string.chat_load_error) : message));
                        } else {
                            sendError.setValue(message == null || message.trim().isEmpty()
                                    ? getApplication().getString(R.string.chat_load_error) : message);
                        }
                    }
                });
    }

    public void retryLoading() {
        if (conversationId == null) return;
        removeSubscription();
        sendError.setValue(null);
        if (currentList().isEmpty()) messages.setValue(Resource.loading());
        attachListener();
    }

    public void loadOlder() {
        if (loadingHistory || !Boolean.TRUE.equals(hasMoreHistory.getValue())) return;
        List<Message> current = currentList();
        if (current.isEmpty()) return;
        String cursor = current.get(0).getId();
        if (cursor == null) {
            hasMoreHistory.setValue(false);
            return;
        }
        requestOlder(cursor);
    }

    public void retryHistory() {
        if (loadingHistory) return;
        if (failedHistoryCursor != null) requestOlder(failedHistoryCursor);
        else loadOlder();
    }

    private void requestOlder(String cursor) {
        loadingHistory = true;
        loadingOlder.setValue(true);
        repository.loadOlder(conversationId, cursor, currentUserId, currentUserName,
                peerName, MESSAGE_PAGE_SIZE, new MessageRepository.PageCallback() {
                    @Override
                    public void onSuccess(MessagePage page) {
                        loadingHistory = false;
                        loadingOlder.setValue(false);
                        failedHistoryCursor = null;
                        for (Message message : page.getItems()) addOrReplace(olderMessages, message);
                        hasMoreHistory.setValue(page.hasMore());
                        publishMessages();
                    }

                    @Override
                    public void onError(String message) {
                        loadingHistory = false;
                        loadingOlder.setValue(false);
                        failedHistoryCursor = cursor;
                        sendError.setValue(message == null || message.trim().isEmpty()
                                ? getApplication().getString(R.string.chat_history_error) : message);
                    }
                });
    }

    public boolean sendText(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String text = value.trim();
        if (text.length() > MAX_MESSAGE_LENGTH) {
            sendError.setValue(getApplication().getString(R.string.chat_message_too_long));
            return false;
        }
        if (currentUserId == null || conversationId == null) {
            sendError.setValue(getApplication().getString(R.string.chat_send_error));
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (text.equals(lastSendText) && now - lastSendRequestAt < 700L) return false;
        lastSendText = text;
        lastSendRequestAt = now;
        sendError.setValue(null);

        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setConversationId(conversationId);
        message.setSenderId(currentUserId);
        message.setSenderName(currentUserName);
        message.setType(Message.Type.TEXT);
        message.setContent(text);
        message.setSentAt(System.currentTimeMillis());
        message.setCreatedAt(message.getSentAt());
        message.setDeliveryStatus(Message.DeliveryStatus.SENDING);
        localOutgoing.put(message.getId(), message);
        addOrReplace(latestMessages, message);
        publishMessages();
        submit(message);
        return true;
    }

    public void retryMessage(String messageId) {
        if (messageId == null || sendingIds.contains(messageId)) return;
        for (Message message : currentList()) {
            if (messageId.equals(message.getId())
                    && message.getDeliveryStatus() == Message.DeliveryStatus.FAILED) {
                message.setDeliveryStatus(Message.DeliveryStatus.SENDING);
                sendError.setValue(null);
                publishMessages();
                if (message.getType() == Message.Type.IMAGE) {
                    Uri source = pendingImageUris.get(messageId);
                    if (source != null) uploadImage(message, source);
                    else if (message.getMediaUrl() != null) submit(message);
                    else { updateStatus(messageId, Message.DeliveryStatus.FAILED); sendError.setValue(getApplication().getString(R.string.image_upload_error)); }
                } else submit(message); // reuse the same document ID for an idempotent retry
                return;
            }
        }
    }

    private void submit(Message message) {
        if (!sendingIds.add(message.getId())) return;
        MessageRepository.SendCallback callback = new MessageRepository.SendCallback() {
            @Override
            public void onSuccess() {
                if (cleared) return;
                sendingIds.remove(message.getId());
                if (message.getType() == Message.Type.IMAGE) {
                    pendingImageUris.remove(message.getId());
                    message.setLocalMediaUri(null);
                }
                updateStatus(message.getId(), Message.DeliveryStatus.SENT);
            }

            @Override
            public void onError(String error) {
                if (cleared) return;
                sendingIds.remove(message.getId());
                updateStatus(message.getId(), Message.DeliveryStatus.FAILED);
            }
        };
        if (message.getType() == Message.Type.IMAGE) repository.sendImage(conversationId, message, callback);
        else repository.sendText(conversationId, message, callback);
    }

    public boolean sendImage(Uri uri) {
        if (uri == null) return false;
        if (!canSendImage()) { sendError.setValue(mediaRepository.getUnavailableReason()); return false; }
        if (currentUserId == null || conversationId == null) { sendError.setValue(getApplication().getString(R.string.chat_send_error)); return false; }
        Message message = new Message();
        message.setId(UUID.randomUUID().toString()); message.setConversationId(conversationId);
        message.setSenderId(currentUserId); message.setSenderName(currentUserName); message.setType(Message.Type.IMAGE);
        message.setContent(""); message.setLocalMediaUri(uri.toString()); message.setSentAt(System.currentTimeMillis());
        message.setCreatedAt(message.getSentAt()); message.setDeliveryStatus(Message.DeliveryStatus.SENDING);
        localOutgoing.put(message.getId(), message); pendingImageUris.put(message.getId(), uri);
        addOrReplace(latestMessages, message); sendError.setValue(null); publishMessages(); uploadImage(message, uri); return true;
    }

    private void uploadImage(Message message, Uri uri) {
        if (cleared || activeUploads.containsKey(message.getId())) return;
        MediaRepository.Transfer transfer = mediaRepository.uploadImage(uri, conversationId, currentUserId,
                message.getId(), new MediaRepository.UploadListener() {
                    @Override public void onProgress(int percent) {
                        if (cleared) return;
                        message.setUploadProgress(percent); publishMessages();
                    }
                    @Override public void onSuccess(String storagePath, long sizeBytes, String contentType) {
                        if (cleared) return;
                        activeUploads.remove(message.getId());
                        message.setMediaUrl(storagePath); message.setFileSize(sizeBytes);
                        message.setContentType(contentType); message.setUploadProgress(100);
                        publishMessages(); submit(message);
                    }
                    @Override public void onError(String error) {
                        if (cleared) return;
                        activeUploads.remove(message.getId());
                        updateStatus(message.getId(), Message.DeliveryStatus.FAILED);
                        sendError.setValue(error == null || error.trim().isEmpty()
                                ? getApplication().getString(R.string.image_upload_error) : error);
                    }
                });
        activeUploads.put(message.getId(), transfer);
    }

    private void updateStatus(String id, Message.DeliveryStatus status) {
        Message outgoing = localOutgoing.get(id);
        if (outgoing != null) outgoing.setDeliveryStatus(status);
        for (Message message : olderMessages) if (id.equals(message.getId())) message.setDeliveryStatus(status);
        for (Message message : latestMessages) if (id.equals(message.getId())) message.setDeliveryStatus(status);
        publishMessages();
    }

    private void publishMessages() {
        List<Message> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Message item : olderMessages) if (seen.add(item.getId())) merged.add(copyMessage(item));
        for (Message item : latestMessages) {
            if (seen.add(item.getId())) merged.add(copyMessage(item));
            else replace(merged, copyMessage(item));
        }
        for (Message item : localOutgoing.values()) {
            if (seen.add(item.getId())) merged.add(copyMessage(item));
            else replace(merged, copyMessage(item));
        }
        merged.sort(Comparator.comparingLong(Message::getSentAt).thenComparing(
                item -> item.getId() == null ? "" : item.getId()));
        messages.setValue(Resource.success(merged));
    }

    private List<Message> currentList() {
        Resource<List<Message>> current = messages.getValue();
        return current == null || current.getData() == null
                ? Collections.emptyList() : current.getData();
    }

    private void addOrReplace(List<Message> list, Message message) {
        for (int i = 0; i < list.size(); i++) {
            if (message.getId() != null && message.getId().equals(list.get(i).getId())) {
                list.set(i, message);
                return;
            }
        }
        list.add(message);
    }

    private void replace(List<Message> list, Message message) {
        for (int i = 0; i < list.size(); i++) {
            if (message.getId().equals(list.get(i).getId())) {
                list.set(i, message);
                return;
            }
        }
    }

    private Message copyMessage(Message source) {
        Message copy = new Message();
        copy.setId(source.getId()); copy.setConversationId(source.getConversationId());
        copy.setSenderId(source.getSenderId()); copy.setSenderName(source.getSenderName());
        copy.setType(source.getType()); copy.setContent(source.getContent()); copy.setMediaUrl(source.getMediaUrl());
        copy.setContentType(source.getContentType()); copy.setLocalMediaUri(source.getLocalMediaUri());
        copy.setFileName(source.getFileName()); copy.setFileSize(source.getFileSize());
        copy.setSentAt(source.getSentAt()); copy.setCreatedAt(source.getCreatedAt()); copy.setUpdatedAt(source.getUpdatedAt());
        copy.setReadBy(source.getReadBy()); copy.setDeliveryStatus(source.getDeliveryStatus());
        copy.setUploadProgress(source.getUploadProgress());
        return copy;
    }

    private void removeSubscription() {
        listenerGeneration++;
        if (subscription != null) {
            subscription.remove();
            subscription = null;
        }
    }

    @Override
    protected void onCleared() {
        cleared = true;
        for (MediaRepository.Transfer upload : activeUploads.values()) upload.cancel();
        activeUploads.clear(); pendingImageUris.clear();
        removeSubscription();
        super.onCleared();
    }
}
