package com.example.utechat.feature.conversations.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.utechat.R;
import com.example.utechat.app.ConversationRepositoryProvider;
import com.example.utechat.core.common.Resource;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.domain.model.Conversation;
import com.example.utechat.domain.model.ConversationPage;
import com.example.utechat.domain.model.User;
import com.example.utechat.domain.repository.ConversationRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConversationsViewModel extends AndroidViewModel {
    public static final int PAGE_SIZE = 20;

    private final ConversationRepository repository;
    private final String userId;
    private final MutableLiveData<Resource<List<Conversation>>> conversations = new MutableLiveData<>();
    private final MutableLiveData<Boolean> loadingMore = new MutableLiveData<>(false);
    private final MutableLiveData<String> pagingError = new MutableLiveData<>();
    private String nextCursor;
    private boolean requestInFlight;

    public ConversationsViewModel(@NonNull Application application) {
        super(application);
        repository = ConversationRepositoryProvider.get(application);
        User currentUser = AuthRepositoryProvider.get(application).getCurrentUser();
        userId = currentUser == null ? null : currentUser.getUid();
        refresh();
    }

    public LiveData<Resource<List<Conversation>>> getConversations() { return conversations; }
    public LiveData<Boolean> getLoadingMore() { return loadingMore; }
    public LiveData<String> getPagingError() { return pagingError; }
    public boolean isMock() { return repository.isMock(); }

    public void refresh() {
        if (requestInFlight) return;
        requestInFlight = true;
        nextCursor = null;
        pagingError.setValue(null);
        loadingMore.setValue(false);
        conversations.setValue(Resource.loading());
        requestPage(null, false);
    }

    public void loadNextPage() {
        if (requestInFlight || nextCursor == null || nextCursor.isEmpty()) return;
        pagingError.setValue(null);
        loadingMore.setValue(true);
        requestPage(nextCursor, true);
    }

    public void retry() {
        Resource<List<Conversation>> current = conversations.getValue();
        if (current == null || !current.isSuccess() || current.getData() == null) {
            refresh();
        } else if (nextCursor != null) {
            loadNextPage();
        } else {
            refresh();
        }
    }

    private void requestPage(String cursor, boolean append) {
        requestInFlight = true;
        repository.loadPage(userId, cursor, PAGE_SIZE, new ConversationRepository.Callback() {
            @Override
            public void onSuccess(ConversationPage page) {
                requestInFlight = false;
                loadingMore.setValue(false);
                List<Conversation> merged = append && conversations.getValue() != null
                        && conversations.getValue().getData() != null
                        ? new ArrayList<>(conversations.getValue().getData())
                        : new ArrayList<>();
                Set<String> ids = new HashSet<>();
                for (Conversation item : merged) if (item.getId() != null) ids.add(item.getId());
                for (Conversation item : page.getItems()) {
                    if (item != null && item.getId() != null && ids.add(item.getId())) merged.add(item);
                }
                merged.sort((left, right) -> Long.compare(right.getLastMessageAt(), left.getLastMessageAt()));
                nextCursor = page.getNextCursor();
                conversations.setValue(Resource.success(merged));
            }

            @Override
            public void onError(String message) {
                requestInFlight = false;
                loadingMore.setValue(false);
                if (append && conversations.getValue() != null
                        && conversations.getValue().getData() != null) {
                    pagingError.setValue(message == null || message.trim().isEmpty()
                            ? getApplication().getString(R.string.conversations_load_error)
                            : message);
                } else {
                    conversations.setValue(Resource.error(message == null || message.trim().isEmpty()
                            ? getApplication().getString(R.string.conversations_load_error)
                            : message));
                }
            }
        });
    }
}
