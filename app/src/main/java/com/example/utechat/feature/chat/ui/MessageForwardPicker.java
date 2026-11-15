package com.example.utechat.feature.chat.ui;

import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.example.utechat.R;
import com.example.utechat.app.ConversationRepositoryProvider;
import com.example.utechat.domain.model.Conversation;
import com.example.utechat.domain.model.Message;
import com.example.utechat.domain.model.ConversationPage;
import com.example.utechat.domain.repository.ConversationRepository;
import com.example.utechat.domain.repository.MessageRepository;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;

final class MessageForwardPicker {
    private final ChatActivity host;
    private final Message message;
    private final ArrayList<Conversation> choices = new ArrayList<>();
    private final ArrayAdapter<String> labels;
    private final TextView status;
    private final Button more;
    private final androidx.appcompat.app.AlertDialog dialog;
    private String cursor;
    private boolean sending;

    static void show(ChatActivity host, Message message) { new MessageForwardPicker(host, message).load(); }

    private MessageForwardPicker(ChatActivity host, Message message) {
        this.host = host;
        this.message = message;
        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(host);
        status.setPaddingRelative(MessageActionsMenu.dp(host, 24), MessageActionsMenu.dp(host, 8),
                MessageActionsMenu.dp(host, 24), MessageActionsMenu.dp(host, 8));
        panel.addView(status);
        ListView list = new ListView(host);
        labels = new ArrayAdapter<>(host, android.R.layout.simple_list_item_1);
        list.setAdapter(labels);
        panel.addView(list, new LinearLayout.LayoutParams(-1, MessageActionsMenu.dp(host, 220)));
        more = new com.google.android.material.button.MaterialButton(host);
        more.setText(R.string.message_forward_more);
        more.setOnClickListener(v -> load());
        panel.addView(more);
        list.setOnItemClickListener((parent, view, index, id) -> send(choices.get(index)));
        dialog = new MaterialAlertDialogBuilder(host).setTitle(R.string.message_forward).setView(panel)
                .setNegativeButton(R.string.friends_cancel_button, null).show();
    }

    private void load() {
        status.setText(R.string.chat_loading);
        more.setEnabled(false);
        ConversationRepositoryProvider.get(host).loadPage(host.chatViewModel().getCurrentUserId(), cursor, 20,
                new ConversationRepository.Callback() {
                    @Override public void onSuccess(ConversationPage page) {
                        if (!dialog.isShowing()) return;
                        cursor = page.getNextCursor();
                        for (Conversation conversation : page.getItems()) {
                            if (conversation.isDeleting()) continue;
                            choices.add(conversation);
                            labels.add(title(conversation));
                        }
                        status.setText(choices.isEmpty() ? R.string.message_forward_empty : R.string.message_forward_destination);
                        more.setVisibility(cursor == null ? View.GONE : View.VISIBLE);
                        more.setEnabled(true);
                    }
                    @Override public void onError(String error) {
                        if (!dialog.isShowing()) return;
                        status.setText(error);
                        more.setEnabled(true);
                    }
                });
    }

    private void send(Conversation destination) {
        if (sending) return;
        new MaterialAlertDialogBuilder(host).setTitle(R.string.message_forward)
                .setMessage(host.getString(R.string.message_forward_confirm, title(destination)))
                .setNegativeButton(R.string.friends_cancel_button, null)
                .setPositiveButton(R.string.message_forward, (confirm, button) -> {
                    sending = true;
                    status.setText(R.string.message_sending);
                    host.chatViewModel().forward(message, destination.getId(), new MessageRepository.SendCallback() {
                        @Override public void onSuccess() {
                            if (host.isDestroyed() || host.isFinishing()) return;
                            dialog.dismiss();
                            Toast.makeText(host, R.string.message_forward_sent, Toast.LENGTH_SHORT).show();
                        }
                        @Override public void onError(String error) {
                            sending = false;
                            if (dialog.isShowing()) status.setText(error);
                        }
                    });
                }).show();
    }

    private String title(Conversation conversation) {
        String title = conversation.getTitle();
        return title == null || title.isBlank() ? host.getString(R.string.conversation_unknown_title) : title;
    }
}
