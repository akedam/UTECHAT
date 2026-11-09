package com.example.utechat.feature.conversations.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.utechat.domain.model.Conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * ViewModel cho ConversationsFragment.
 * [MOCK] Trả về danh sách hội thoại mẫu.
 * TODO (G3): Inject ConversationRepository và observe Firestore real-time.
 */
public class ConversationsViewModel extends ViewModel {

    private final MutableLiveData<List<Conversation>> conversations = new MutableLiveData<>();

    public ConversationsViewModel() {
        loadMockConversations();
    }

    public LiveData<List<Conversation>> getConversations() {
        return conversations;
    }

    /** [MOCK] Dữ liệu mẫu có nhãn rõ ràng — sẽ được xóa ở G3. */
    private void loadMockConversations() {
        List<Conversation> list = new ArrayList<>();

        long now = System.currentTimeMillis();

        Conversation c1 = new Conversation();
        c1.setId("mock_conv_1");
        c1.setType(Conversation.Type.DIRECT);
        c1.setTitle("[Mock] Trần Thị B");
        c1.setLastMessage("Hẹn chiều nhé!");
        c1.setLastMessageAt(now - 5 * 60 * 1000L);  // 5 phút trước
        c1.setUnreadCount(3);
        list.add(c1);

        Conversation c2 = new Conversation();
        c2.setId("mock_conv_2");
        c2.setType(Conversation.Type.GROUP);
        c2.setTitle("[Mock] Nhóm LTDT K22");
        c2.setLastMessage("Lê: Nộp bài trước 23h nha mọi người");
        c2.setLastMessageAt(now - 30 * 60 * 1000L); // 30 phút trước
        c2.setUnreadCount(12);
        list.add(c2);

        Conversation c3 = new Conversation();
        c3.setId("mock_conv_3");
        c3.setType(Conversation.Type.DIRECT);
        c3.setTitle("[Mock] Phạm Văn C");
        c3.setLastMessage("OK bạn ơi 👍");
        c3.setLastMessageAt(now - 2 * 60 * 60 * 1000L); // 2 giờ trước
        c3.setUnreadCount(0);
        list.add(c3);

        Conversation c4 = new Conversation();
        c4.setId("mock_conv_4");
        c4.setType(Conversation.Type.GROUP);
        c4.setTitle("[Mock] Dự án Capstone");
        c4.setLastMessage("Bạn: Mình đã push code lên rồi");
        c4.setLastMessageAt(now - 24 * 60 * 60 * 1000L); // hôm qua
        c4.setUnreadCount(0);
        list.add(c4);

        conversations.setValue(list);
    }
}
