package com.example.utechat.feature.conversations.adapter;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.utechat.R;
import com.example.utechat.domain.model.Conversation;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

/** RecyclerView adapter for the latest-activity-sorted conversation page. */
public class ConversationsAdapter extends ListAdapter<Conversation, ConversationsAdapter.ViewHolder> {
    public interface OnConversationClickListener {
        void onConversationClick(Conversation conversation);
    }

    private final OnConversationClickListener listener;
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM", Locale.getDefault());
    private static final SimpleDateFormat DATE_YEAR_FORMAT = new SimpleDateFormat("dd/MM/yy", Locale.getDefault());

    public ConversationsAdapter(OnConversationClickListener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<Conversation> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<Conversation>() {
                @Override
                public boolean areItemsTheSame(@NonNull Conversation oldItem, @NonNull Conversation newItem) {
                    return Objects.equals(oldItem.getId(), newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull Conversation a, @NonNull Conversation b) {
                    return Objects.equals(a.getTitle(), b.getTitle())
                            && Objects.equals(a.getPhotoUrl(), b.getPhotoUrl())
                            && Objects.equals(a.getLastMessage(), b.getLastMessage())
                            && Objects.equals(a.getLastMessageType(), b.getLastMessageType())
                            && a.getLastMessageAt() == b.getLastMessageAt()
                            && a.getUnreadCount() == b.getUnreadCount()
                            && a.getType() == b.getType();
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

    final class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView avatar;
        private final TextView avatarFallback;
        private final TextView title;
        private final TextView preview;
        private final TextView time;
        private final TextView unread;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.iv_conversation_avatar);
            avatarFallback = itemView.findViewById(R.id.tv_avatar_placeholder);
            title = itemView.findViewById(R.id.tv_conversation_title);
            preview = itemView.findViewById(R.id.tv_last_message);
            time = itemView.findViewById(R.id.tv_time);
            unread = itemView.findViewById(R.id.tv_unread_count);
        }

        void bind(Conversation conversation) {
            String safeTitle = hasText(conversation.getTitle())
                    ? conversation.getTitle().trim() : itemView.getContext().getString(R.string.conversation_unknown_title);
            com.example.utechat.feature.chat.ui.ChatPreferences prefs = new com.example.utechat.feature.chat.ui.ChatPreferences(itemView.getContext(), conversation.getId());
            safeTitle = prefs.title(safeTitle);
            if (prefs.enabled("pinned")) safeTitle = "⌁ " + safeTitle;
            else if (prefs.enabled("favorite")) safeTitle = "☆ " + safeTitle;
            title.setText(safeTitle);
            preview.setText(previewText(conversation));
            String formattedTime = formatTime(conversation.getLastMessageAt());
            time.setText(formattedTime);

            String photoUrl = conversation.getPhotoUrl();
            if (hasText(photoUrl) && photoUrl.trim().startsWith("https://")) {
                avatarFallback.setVisibility(View.GONE);
                avatar.setVisibility(View.VISIBLE);
                Glide.with(avatar).load(photoUrl.trim()).circleCrop()
                        .placeholder(R.drawable.bg_avatar_circle_primary)
                        .error(R.drawable.bg_avatar_circle_primary)
                        .into(avatar);
            } else {
                Glide.with(avatar).clear(avatar);
                avatar.setVisibility(View.GONE);
                avatarFallback.setVisibility(View.VISIBLE);
                avatarFallback.setText(conversation.isGroup()
                        ? itemView.getContext().getString(R.string.conversation_group_fallback)
                        : firstInitial(safeTitle));
            }

            int unreadCount = Math.max(0, conversation.getUnreadCount());
            title.setTypeface(null, unreadCount > 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            preview.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.getContext(),
                    unreadCount > 0 ? R.color.utechat_on_surface : R.color.utechat_on_surface_variant));
            if (unreadCount > 0) {
                unread.setVisibility(View.VISIBLE);
                unread.setText(unreadCount > 99
                        ? itemView.getContext().getString(R.string.conversation_unread_many)
                        : String.valueOf(unreadCount));
            } else {
                unread.setVisibility(View.GONE);
            }

            itemView.setContentDescription(itemView.getContext().getString(
                    R.string.conversation_accessibility_item, safeTitle, preview.getText(), formattedTime));
            itemView.setOnClickListener(v -> listener.onConversationClick(conversation));
        }

        private String previewText(Conversation conversation) {
            String message = conversation.getLastMessage();
            String type = conversation.getLastMessageType();
            if ("image".equalsIgnoreCase(type)) return itemView.getContext().getString(R.string.conversation_preview_image);
            if ("file".equalsIgnoreCase(type)) return itemView.getContext().getString(R.string.conversation_preview_file);
            if (hasText(message)) return message.trim();
            return itemView.getContext().getString(R.string.conversation_no_preview);
        }

        private String formatTime(long timestamp) {
            if (timestamp <= 0) return itemView.getContext().getString(R.string.conversation_time_unknown);
            if (DateUtils.isToday(timestamp)) return TIME_FORMAT.format(new Date(timestamp));
            if (DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS)) {
                return itemView.getContext().getString(R.string.conversation_yesterday);
            }
            Date date = new Date(timestamp);
            return android.text.format.DateFormat.format("yyyy", date).toString()
                    .equals(android.text.format.DateFormat.format("yyyy", System.currentTimeMillis()).toString())
                    ? DATE_FORMAT.format(date) : DATE_YEAR_FORMAT.format(date);
        }

        private String firstInitial(String value) {
            if (!hasText(value)) return itemView.getContext().getString(R.string.conversation_unknown_initial);
            return value.trim().substring(0, 1).toUpperCase(Locale.getDefault());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
