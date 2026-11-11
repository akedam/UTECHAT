package com.example.utechat.feature.friends.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.utechat.R;
import com.example.utechat.domain.model.User;

/**
 * Adapter cho danh sách bạn bè.
 */
public class FriendsAdapter extends ListAdapter<User, FriendsAdapter.ViewHolder> {

    public interface OnFriendClickListener {
        void onFriendClick(User user);
        void onFriendCall(User user, String mediaType);
    }

    private final OnFriendClickListener listener;

    public FriendsAdapter(OnFriendClickListener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<User> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<User>() {
                @Override
                public boolean areItemsTheSame(@NonNull User a, @NonNull User b) {
                    return a.getUid().equals(b.getUid());
                }
                @Override
                public boolean areContentsTheSame(@NonNull User a, @NonNull User b) {
                    return a.getDisplayName().equals(b.getDisplayName())
                            && a.isOnline() == b.isOnline()
                            && a.getLastSeen() == b.getLastSeen();
                }
            };

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_friend, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
        String letter = section(getItem(position));
        holder.section.setText(letter);
        holder.section.setVisibility(position == 0 || !letter.equals(section(getItem(position - 1))) ? View.VISIBLE : View.GONE);
    }

    private String section(User user) {
        String name = user.getDisplayName();
        if (name == null || name.trim().isEmpty()) return "#";
        return java.text.Normalizer.normalize(name.trim().substring(0, 1), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(java.util.Locale.ROOT);
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvAvatar, tvName, tvStatus;
        private final View onlineIndicator;
        private final TextView section;
        private final android.widget.ImageView avatar;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tv_avatar_placeholder);
            tvName = itemView.findViewById(R.id.tv_friend_name);
            tvStatus = itemView.findViewById(R.id.tv_friend_status);
            onlineIndicator = itemView.findViewById(R.id.view_online_indicator);
            section = itemView.findViewById(R.id.tv_friend_section);
            avatar = itemView.findViewById(R.id.iv_friend_avatar);
        }

        void bind(User user) {
            tvAvatar.setText(user.getInitials());
            tvName.setText(user.getDisplayName());
            String photo = user.getPhotoUrl();
            boolean hasPhoto = photo != null && photo.startsWith("https://");
            avatar.setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
            tvAvatar.setVisibility(hasPhoto ? View.GONE : View.VISIBLE);
            com.bumptech.glide.Glide.with(avatar).clear(avatar);
            if (hasPhoto) com.bumptech.glide.Glide.with(avatar).load(photo).circleCrop()
                    .error(R.drawable.bg_avatar_circle_accent).into(avatar);

            boolean online = user.isOnline();
            onlineIndicator.setVisibility(online ? View.VISIBLE : View.INVISIBLE);
            if (online) {
                tvStatus.setText(R.string.status_online);
            } else if (user.getLastSeen() > 0) {
                CharSequence relative = android.text.format.DateUtils.getRelativeTimeSpanString(
                        Math.min(user.getLastSeen(), System.currentTimeMillis()),
                        System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS);
                tvStatus.setText(itemView.getContext().getString(R.string.status_last_seen, relative));
            } else {
                tvStatus.setText(R.string.status_last_seen_unknown);
            }

            itemView.setOnClickListener(v -> listener.onFriendClick(user));
            itemView.findViewById(R.id.btn_friend_call).setOnClickListener(v -> listener.onFriendCall(user, "audio"));
            itemView.findViewById(R.id.btn_friend_video).setOnClickListener(v -> listener.onFriendCall(user, "video"));
        }
    }
}
