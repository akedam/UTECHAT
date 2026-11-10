package com.example.utechat.data.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.utechat.R;
import com.example.utechat.domain.model.Conversation;
import com.example.utechat.domain.model.ConversationPage;
import com.example.utechat.domain.repository.ConversationRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Explicitly fake, in-memory list used while conversations are not enabled in Firestore rules. */
public final class MockConversationRepository implements ConversationRepository {
    private static final int MOCK_PAGE_SIZE = 20;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public MockConversationRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void loadPage(String userId, String cursor, int pageSize, Callback callback) {
        // A short delay keeps loading behavior visible in the prototype without implying network IO.
        mainHandler.postDelayed(() -> {
            List<Conversation> all = mockData();
            int start = 0;
            if (cursor != null) {
                start = -1;
                for (int i = 0; i < all.size(); i++) {
                    if (cursor.equals(all.get(i).getId())) {
                        start = i + 1;
                        break;
                    }
                }
                if (start < 0) {
                    callback.onError(appContext.getString(R.string.conversations_cursor_error));
                    return;
                }
            }
            int size = Math.max(1, Math.min(pageSize, MOCK_PAGE_SIZE));
            int end = Math.min(all.size(), start + size);
            List<Conversation> items = new ArrayList<>(all.subList(start, end));
            String nextCursor = end < all.size() && !items.isEmpty()
                    ? items.get(items.size() - 1).getId() : null;
            callback.onSuccess(new ConversationPage(items, nextCursor));
        }, 250L);
    }

    @Override
    public boolean isMock() { return true; }

    private List<Conversation> mockData() {
        long now = System.currentTimeMillis();
        List<Conversation> items = new ArrayList<>();
        items.add(item("mock_direct_01", Conversation.Type.DIRECT,
                R.string.mock_conversation_friend, R.string.mock_preview_friend,
                now - 5 * 60_000L, 3));
        items.add(item("mock_group_01", Conversation.Type.GROUP,
                R.string.mock_conversation_course, R.string.mock_preview_course,
                now - 30 * 60_000L, 12));
        items.add(item("mock_direct_02", Conversation.Type.DIRECT,
                R.string.mock_conversation_friend_two, R.string.mock_preview_friend_two,
                now - 2 * 60 * 60_000L, 0));
        items.add(item("mock_group_02", Conversation.Type.GROUP,
                R.string.mock_conversation_project, R.string.mock_preview_project,
                now - 24 * 60 * 60_000L, 0));
        Collections.sort(items, Comparator.comparingLong(Conversation::getLastMessageAt).reversed());
        return items;
    }

    private Conversation item(String id, Conversation.Type type, int titleRes, int previewRes,
                              long lastMessageAt, int unreadCount) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setType(type);
        conversation.setTitle(appContext.getString(titleRes));
        conversation.setLastMessage(appContext.getString(previewRes));
        conversation.setLastMessageAt(lastMessageAt);
        conversation.setCreatedAt(lastMessageAt);
        conversation.setUpdatedAt(lastMessageAt);
        conversation.setLastMessageType("text");
        conversation.setUnreadCount(unreadCount);
        conversation.setParticipantIds(Collections.singletonList("mock_user"));
        conversation.setPhotoUrl(null);
        return conversation;
    }
}
