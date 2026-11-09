package com.example.utechat.feature.conversations.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.utechat.R;
import com.example.utechat.domain.model.Conversation;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Adapter cho danh sách hội thoại — dùng ListAdapter + DiffUtil để cập nhật hiệu quả.
 */
public class ConversationsAdapter extends ListAdapter<Conversation, ConversationsAdapter.ViewHolder> {

    public interface OnConversationClickListener {
        void onConversationClick(Conversation conversation);
    }

    private final OnConversationClickListener listener;
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm", Locale.getDefault());
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("dd/MM", Locale.getDefault());

    public ConversationsAdapter(OnConversationClickListener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<Conversation> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<Conversation>() {
                @Override
                public boolean areItemsTheSame(@NonNull Conversation a, @NonNull Conversation b) {
                    return a.getId().equals(b.getId());
                }
                @Override
                public boolean areContentsTheSame(@NonNull Conversation a, @NonNull Conversation b) {
                    return a.getLastMessage().equals(b.getLastMessage())
                            && a.getLastMessageAt() == b.getLastMessageAt()
                            && a.getUnreadCount() == b.getUnreadCount();
                }
            };

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_conversation, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvAvatar, tvTitle, tvLastMessage, tvTime, tvUnread;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tv_avatar_placeholder);
            tvTitle = itemView.findViewById(R.id.tv_conversation_title);
            tvLastMessage = itemView.findViewById(R.id.tv_last_message);
            tvTime = itemView.findViewById(R.id.tv_time);
            tvUnread = itemView.findViewById(R.id.tv_unread_count);
        }

        void bind(Conversation conversation) {
            // Avatar placeholder — chữ cái đầu tên
            String initial = conversation.getTitle() != null && !conversation.getTitle().isEmpty()
                    ? String.valueOf(conversation.getTitle().charAt(0)).toUpperCase()
                    : "?";
            if (conversation.isGroup()) initial = "#";
            tvAvatar.setText(initial);

            tvTitle.setText(conversation.getTitle());
            tvLastMessage.setText(conversation.getLastMessage());

            // Format thời gian
            long ts = conversation.getLastMessageAt();
            if (ts > 0) {
                long now = System.currentTimeMillis();
                long diffMs = now - ts;
                boolean sameDay = diffMs < 24 * 60 * 60 * 1000L;
                tvTime.setText(sameDay
                        ? TIME_FORMAT.format(new Date(ts))
                        : DATE_FORMAT.format(new Date(ts)));
            } else {
                tvTime.setText("");
            }

            // Badge đọc chưa
            int unread = conversation.getUnreadCount();
            if (unread > 0) {
                tvUnread.setVisibility(View.VISIBLE);
                tvUnread.setText(unread > 99 ? "99+" : String.valueOf(unread));
            } else {
                tvUnread.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> listener.onConversationClick(conversation));
        }
    }
}
