package com.example.utechat.feature.chat.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.utechat.domain.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * ViewModel cho ChatActivity.
 * [MOCK] Tin nhắn mẫu + mock send.
 * TODO (G3): Inject MessageRepository và Firestore real-time listener.
 */
public class ChatViewModel extends ViewModel {

    private final MutableLiveData<List<Message>> messages = new MutableLiveData<>();
    private String conversationId;

    public void init(String conversationId) {
        this.conversationId = conversationId;
        loadMockMessages();
    }

    public LiveData<List<Message>> getMessages() {
        return messages;
    }

    /** [MOCK] Dữ liệu tin nhắn mẫu. */
    private void loadMockMessages() {
        List<Message> list = new ArrayList<>();
        long now = System.currentTimeMillis();

        Message m1 = new Message();
        m1.setId("msg_1");
        m1.setSenderId("mock_uid_b");
        m1.setSenderName("[Mock] Trần Thị B");
        m1.setType(Message.Type.TEXT);
        m1.setContent("Chào bạn! Hôm nay học có vui không?");
        m1.setSentAt(now - 10 * 60 * 1000L);
        list.add(m1);

        Message m2 = new Message();
        m2.setId("msg_2");
        m2.setSenderId("mock_uid_me");
        m2.setSenderName("[Mock] Nguyễn Văn A");
        m2.setType(Message.Type.TEXT);
        m2.setContent("Có chứ! Mình vừa làm xong bài tập Java 😄");
        m2.setSentAt(now - 9 * 60 * 1000L);
        list.add(m2);

        Message m3 = new Message();
        m3.setId("msg_3");
        m3.setSenderId("mock_uid_b");
        m3.setSenderName("[Mock] Trần Thị B");
        m3.setType(Message.Type.TEXT);
        m3.setContent("Hay quá! Chiều có rảnh không, mình muốn hỏi bài");
        m3.setSentAt(now - 8 * 60 * 1000L);
        list.add(m3);

        Message m4 = new Message();
        m4.setId("msg_4");
        m4.setSenderId("mock_uid_me");
        m4.setSenderName("[Mock] Nguyễn Văn A");
        m4.setType(Message.Type.TEXT);
        m4.setContent("Hẹn chiều nhé!");
        m4.setSentAt(now - 5 * 60 * 1000L);
        list.add(m4);

        messages.setValue(list);
    }

    /** [MOCK] Thêm tin nhắn vào list tạm thời — không persist. */
    public void mockSendMessage(String text) {
        List<Message> current = messages.getValue();
        if (current == null) current = new ArrayList<>();

        Message newMsg = new Message();
        newMsg.setId("msg_" + System.currentTimeMillis());
        newMsg.setSenderId("mock_uid_me");
        newMsg.setSenderName("[Mock] Nguyễn Văn A");
        newMsg.setType(Message.Type.TEXT);
        newMsg.setContent(text);
        newMsg.setSentAt(System.currentTimeMillis());

        List<Message> updated = new ArrayList<>(current);
        updated.add(newMsg);
        messages.setValue(updated);
    }
}
